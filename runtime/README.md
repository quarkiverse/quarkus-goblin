# Goblin Runtime -- Assaults

This module hosts the runtime of the Goblin chaos extension. This guide documents the **assault abstraction**: the
existing assault types, how they are executed, and how to add a new one. If you plan to contribute a new assault, read
the [Adding a new assault](#adding-a-new-assault) section.

## Packages

- `io.quarkiverse.goblin` -- the public API, covered by semantic versioning: `AssaultEngine`, `AssaultConfig` (read-only) and
  `MutableAssaultConfig` (interfaces), `AssaultObserver`, `AssaultConfigChange`, `GoblinWebClient` and the enums. A public type there is a
  promise to users: add one only on purpose.
- `io.quarkiverse.goblin.assault` -- the `Assault` SPI for custom assaults, nothing else; public and stable.
- `io.quarkiverse.goblin.internal` and its sub-packages -- everything else, free to change in any version:
  - `internal` -- the engine core: `AssaultEngineImpl` and `MutableAssaultConfigImpl` (state and the plumbing the hooks
    call), `AssaultSettings`, the per-request `ChaosRequestContext`, the installed `GoblinLayerHooks`, the Markdown
    report;
  - `internal.filter` -- the JAX-RS server and REST Client filters;
  - `internal.assault` -- the built-in assaults and what they share: abort responses, latency, layer faults, the
    response body and header transformers;
  - `internal.config` -- the `quarkus.goblin.*` mappings and the targeting rules;
  - `internal.state` -- the `.goblin-state.json` persistence and the saved scenarios;
  - `internal.route`, `internal.service`, `internal.database`, `internal.messaging` -- the hook of each layer.

## Execution model

Requests go through `GoblinChaosFilter` (a JAX-RS `ContainerRequestFilter`). When the engine is active, the request
is eligible (`quarkus.goblin.target.*` targeting rules) and the request resolves to the `HTTP_IN` layer (see the chaos
layers in the documentation: a deeper armed layer that wins the `quarkus.goblin.target.level` draw makes the filter
stand down), the filter:

1. injects every `Assault` CDI bean available (`Instance<Assault>`);
2. sorts them by ascending `order()`;
3. applies each enabled assault in sequence;
4. stops as soon as an assault returns `AssaultOutcome.ABORTED`.

Assaults are discovered automatically: no manual registration is required, adding a bean implements the SPI is enough.

Reactive routes (`@Route`) never go through the JAX-RS filter. When `quarkus-reactive-routes` is present, the deployment
module weaves a call to `internal/route/GoblinRouteHook` into Quarkus's `RouteHandler.handle()`, right before the route method
runs. The hook resolves the layer and applies the same targeting rules, but it does **not** use the `Assault` SPI: it
applies the six built-in assault types itself (latency on a Vert.x timer when on the event loop, exception through
`RoutingContext.fail()`, HTTP status, dependency degradation, and through `internal/route/RouteResponse` the response header
rules in a headers-end handler and the response body on a single-shot `end(...)`), with the same history labels.
Custom `Assault` beans do not apply to reactive routes. The hook keeps its decision in the routing context
(`GoblinRouteHook.RouteDecision`): when a `@Route` acting as a pass-through filter sits in front of a REST resource,
`GoblinChaosFilter` applies that decision on the resource thread (layer or shield, and the response body) instead of
drawing a second one.

The same `GoblinChaosFilter` also implements a JAX-RS `ContainerResponseFilter`: on eligible responses it rewrites
the entity when the **response body** assault is enabled (truncate/inflate) and applies the configured **response
header** rules, so the transformations compose with every other assault instead of replacing them.

Outgoing MicroProfile / Quarkus REST Client calls are handled by `GoblinChaosClientFilter` (a JAX-RS
`ClientRequestFilter`, registered globally as an unremovable bean by the deployment build step). It reuses the
`engine.shouldAssaultClient()` level gate (same `quarkus.goblin.target.level` percentage as the server side, no package
targeting) and the client-side toggles from `MutableAssaultConfig` (`clientLatencyEnabled` / `clientExceptionEnabled`).
It only runs when the `HTTP_OUT` layer is armed. When enabled it applies latency before the request is dispatched
(skipped, with a WARN logged once, on a Vert.x event-loop thread) and/or throws the configured exception before the call
leaves the application -- the remote service is never reached for exception assaults. History records use the
`REST-Client <METHOD> <URI>` method format.

Outgoing Vert.x `WebClient` calls are handled by the static utility `GoblinWebClient`, in the same package. Vert.x 4.x
exposes no public interceptor hook on `WebClient`, so in dev and test builds the deployment module rewrites every
`WebClient.create(...)` / `WebClient.wrap(...)` call of the application classes to hand the new client to
`internal.WebClientArming.arm(...)`, which calls `GoblinWebClient.enable(...)`. The utility casts to Vert.x's internal
`WebClientInternal` and registers an interceptor (the same mechanism Vert.x uses for its `OAuth2WebClient` /
`CachingWebClient` / `WebClientSession` decorators); repeated calls with the same instance are idempotent. The
interceptor applies the same client-side toggles, on `PREPARE_REQUEST` (before dispatch), with the latency wait done
via a Vert.x timer when a context is active (blocking `Thread.sleep` fallback otherwise). History records use the
`WebClient <METHOD> <URI>` method format.

## Existing assaults

| Assault | Class | `order()` | Enabled via | Behavior | Config keys (`quarkus.goblin.*`) |
|---|---|---|---|---|---|
| Latency | `LatencyAssault` | 10 | `AssaultConfig.isLatencyEnabled()` | Random delay between `min-` and `max-milliseconds` (inclusive), then continues the chain; skipped (WARN logged once) on a Vert.x event-loop thread | `assault.latency.min-milliseconds`, `assault.latency.max-milliseconds` |
| Exception | `ExceptionAssault` | 20 | `AssaultConfig.isExceptionEnabled()` | Throws the configured exception class (String constructor) before the method runs; falls back to `RuntimeException` with a WARN log | `assault.exception.type`, `assault.exception.message` |
| HTTP status | `HttpStatusAssault` | 30 | `AssaultConfig.isHttpStatusEnabled()` | Aborts the request with the configured status code and body (`ABORTED`) | `assault.http-status.code`, `assault.http-status.message` |
| Dependency degradation | `DependencyDegradationAssault` | 40 | `AssaultConfig.isDependencyDegradationEnabled()` | Aborts the request with a fixed 503 response, to exercise outbound `@Fallback`/`@Retry` (`ABORTED`) | none (fixed values) |
| Response body | `GoblinChaosFilter` (response phase) | n/a | `AssaultConfig.isResponseBodyEnabled()` | Rewrites the emitted `String` / `CharSequence` / `byte[]` entity via `ResponseBodyTransformer`: `TRUNCATE` keeps the first `percentage`% of the body and sets `Content-Length` to the truncated size, while `INFLATE` pads it with a `[goblin-response-inflated]` marker up to `percentage`% of the original size (at most 1 MiB added) but keeps advertising the original length | `assault.body.mode`, `assault.body.percentage` |
| Response header | `GoblinChaosFilter` (response phase) | n/a | `AssaultConfig.isResponseHeaderEnabled()` | Applies each configured rule via `ResponseHeaderTransformer`: `SET` forces the header (`putSingle`, replacing an existing value or adding it when absent), `REMOVE` deletes it when present. `REMOVE` is recorded in history only when the header existed | `assault.headers.<name>.action`, `assault.headers.<name>.value` |

The built-in assaults live in `io.quarkiverse.goblin.internal.assault`, except the response body and response header assaults which run
directly in the `ContainerResponseFilter` phase of `GoblinChaosFilter` (they operate on the emitted response, not on
the inbound request). The chain order convention is: latency first (10), then request-aborting assaults by increasing
severity (20, 30, 40).

### Client-side assaults

Client-facing latency and exception assaults are not part of the `Assault` chain above; they run in
`GoblinChaosClientFilter` (REST Client, global) and `GoblinWebClient` (Vert.x WebClient, armed at build time through `internal.WebClientArming`). They are driven by the
runtime-only toggles `clientLatencyEnabled` and `clientExceptionEnabled` on `MutableAssaultConfig` (default `false`,
persisted by `GoblinStatePersistence`, no static `GoblinConfig` key), reusing the latency range and exception
class/message configured for the server side. Both record their history with a dedicated source prefix
(`REST-Client ...` / `WebClient ...`) that the Dev UI history renders as a source badge.

### Profiles

`quarkus.goblin.assault.profile` (`NONE|SLOW_FAILURE|INTERMITTENT|TIMEOUT`, default `NONE`) bundles common
combinations into one config line. `MutableAssaultConfig.setProfile(...)` turns every server-side toggle off
(response body and response header included), then enables the profile's assaults with their defaults and messages;
the client-side toggles and the chaos layers are never touched, and `NONE` changes nothing. `describeAssaults()`
mentions the active profile; individual assaults remain user-overridable afterwards. When loading persisted state,
`GoblinStatePersistence` calls `MutableAssaultConfigImpl.restoreProfile(...)`, which restores only the label, so
per-assault overrides survive restarts.

At startup a non-`NONE` profile takes precedence: `MutableAssaultConfigImpl.fromConfig(...)` first copies the static
`assault.type`/parameter values, then applies the profile defaults over them -- so static toggles and parameters are
overridden by the profile's defaults.

## The `Assault` SPI

```java
public interface Assault {
    AssaultType type();                    // enum constant this implementation represents
    boolean isEnabled(AssaultConfig config); // run it for this request's configuration snapshot?
    String recordLabel();                  // stable label used in history and Markdown report
    int order();                           // chain position, ascending
    AssaultOutcome apply(AssaultContext context);  // perform the assault, return CONTINUE or ABORTED
}
```

An `Assault` is an `@ApplicationScoped` CDI bean -- the scope Goblin registers it with when the class declares none; it
runs in the JAX-RS filter only, never on reactive routes. `AssaultContext` carries the JAX-RS
`ContainerRequestContext` (may be `null` in unit tests), the request's read-only `AssaultConfig` snapshot, the `AssaultEngine` and the
targeted method name; `context.record(this)` or `context.record(this, latencyMs)` appends the assault to the history with
its `type()` and `recordLabel()`. The SPI is public and stable (see the public API page of the documentation).

`AssaultOutcome` has two values: `CONTINUE` (let the next enabled assault run) and `ABORTED` (short-circuit the chain;
use it whenever the request must not reach the endpoint).

## Adding a new assault

The vertical slice of an assault touches the following places plus tests:

1. **Add the enum constant** to `AssaultType`, e.g. `RANDOM_PAYLOAD`.
2. **Implement the assault** in `io.quarkiverse.goblin.internal.assault` (`io.quarkiverse.goblin.assault` holds the public
   SPI only: a class there is published as public API):
   - `@ApplicationScoped` class implementing `Assault`;
   - return the new constant from `type()`, a descriptive label from `recordLabel()`, and a chain position from
     `order()` (stick to 10/20/30/40 increments to keep the ordering predictable);
   - read the parameters from the config and return `CONTINUE` or `ABORTED` as appropriate (see the existing assaults
     for the `context.record(this)` pattern).
3. **Wire the configuration**:
   - add a `@ConfigGroup` section to `GoblinConfig` (the `AssaultConfig` group) holding the new parameters, with
     defaults and `@WithDefault`;
   - add the field to the immutable `AssaultSettings` (field, builder field, `toBuilder()`) with its built-in default;
   - add the matching toggle to the public interfaces -- the getter to `AssaultConfig`, the setter to
     `MutableAssaultConfig` (e.g. `isRandomPayloadEnabled()`/`setRandomPayloadEnabled(...)`) -- and implement both in
     `MutableAssaultConfigImpl`,
     writing through `update(...)` so the change is published atomically and persisted, and include it in
     `fromConfig(...)`, `resetToDefaults()`, `applyProfileDefaults(...)` (profiles turn every server-side toggle off),
     `describeAssaults()` and `hasAnyAssaultEnabled()`;
   - persist it in `GoblinStatePersistence` (both `save` and `load`, with a default for older state files).
4. **Dev UI (optional but expected for parity)**: expose the toggle and parameters in `GoblinJsonRPCService`
   (`getStatus`, `getConfig`, a toggle method, `applyConfig`, and the `disableAll` kill switch), then add the editors to
   the Dev UI card, mirroring the existing panels (see [runtime-dev/README.md](../runtime-dev/README.md)).

Then add unit tests in the `runtime` module (the `Assault` contract is testable with a plain `AssaultContext`, a
`MutableAssaultConfigImpl` and an `AssaultEngineImpl` from `io.quarkiverse.goblin.internal`) and integration tests in `integration-tests`. All new or modified methods must carry
Javadoc.

## Conventions

- Keep `type()`, `recordLabel()` and the config keys stable: they are part of the user-facing surface (config, history
  and Markdown report).
- Log a `WARN` (not a failure) when a configured parameter cannot be honored; fall back to a safe default.
- Follow the project's zero-code-modification philosophy: an assault must not require users to touch their application
  code.