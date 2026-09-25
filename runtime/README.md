# Goblin Runtime -- Assaults

This module hosts the runtime of the Goblin chaos extension. This guide documents the **assault abstraction**: the
existing assault types, how they are executed, and how to add a new one. If you plan to contribute a new assault, read
the [Adding a new assault](#adding-a-new-assault) section.

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
exposes no public interceptor hook on `WebClient`, so the application opts in once with
`WebClient client = GoblinWebClient.enable(WebClient.create(vertx))`. The utility casts to Vert.x's internal
`WebClientInternal` and registers an interceptor (the same mechanism Vert.x uses for its `OAuth2WebClient` /
`CachingWebClient` / `WebClientSession` decorators); repeated calls with the same instance are idempotent. The
interceptor applies the same client-side toggles, on `PREPARE_REQUEST` (before dispatch), with the latency wait done
via a Vert.x timer when a context is active (blocking `Thread.sleep` fallback otherwise). History records use the
`WebClient <METHOD> <URI>` method format.

## Existing assaults

| Assault | Class | `order()` | Enabled via | Behavior | Config keys (`quarkus.goblin.*`) |
|---|---|---|---|---|---|
| Latency | `LatencyAssault` | 10 | `MutableAssaultConfig.isLatencyEnabled()` | Random delay between `min-` and `max-milliseconds` (inclusive), then continues the chain; skipped (WARN logged once) on a Vert.x event-loop thread | `assault.latency.min-milliseconds`, `assault.latency.max-milliseconds` |
| Exception | `ExceptionAssault` | 20 | `MutableAssaultConfig.isExceptionEnabled()` | Throws the configured exception class (String constructor) before the method runs; falls back to `RuntimeException` with a WARN log | `assault.exception.type`, `assault.exception.message` |
| HTTP status | `HttpStatusAssault` | 30 | `MutableAssaultConfig.isHttpStatusEnabled()` | Aborts the request with the configured status code and body (`ABORTED`) | `assault.http-status.code`, `assault.http-status.message` |
| Dependency degradation | `DependencyDegradationAssault` | 40 | `MutableAssaultConfig.isDependencyDegradationEnabled()` | Aborts the request with a fixed 503 response, to exercise outbound `@Fallback`/`@Retry` (`ABORTED`) | none (fixed values) |
| Response body | `GoblinChaosFilter` (response phase) | n/a | `MutableAssaultConfig.isResponseBodyEnabled()` | Rewrites the emitted `String` / `CharSequence` / `byte[]` entity via `ResponseBodyTransformer`: `TRUNCATE` keeps the first `percentage`% of the body and sets `Content-Length` to the truncated size, while `INFLATE` pads it with a `[goblin-response-inflated]` marker up to `percentage`% of the original size (at most 1 MiB added) but keeps advertising the original length | `assault.body.mode`, `assault.body.percentage` |
| Response header | `GoblinChaosFilter` (response phase) | n/a | `MutableAssaultConfig.isResponseHeaderEnabled()` | Applies each configured rule via `ResponseHeaderTransformer`: `SET` forces the header (`putSingle`, replacing an existing value or adding it when absent), `REMOVE` deletes it when present. `REMOVE` is recorded in history only when the header existed | `assault.headers.<name>.action`, `assault.headers.<name>.value` |

All classes live in `io.quarkiverse.goblin.assault`, except the response body and response header assaults which run
directly in the `ContainerResponseFilter` phase of `GoblinChaosFilter` (they operate on the emitted response, not on
the inbound request). The chain order convention is: latency first (10), then request-aborting assaults by increasing
severity (20, 30, 40).

### Client-side assaults

Client-facing latency and exception assaults are not part of the `Assault` chain above; they run in
`GoblinChaosClientFilter` (REST Client, global) and `GoblinWebClient` (Vert.x WebClient, opt-in). They are driven by the
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
`GoblinStatePersistence` calls `MutableAssaultConfig.restoreProfile(...)`, which restores only the label, so
per-assault overrides survive restarts.

At startup a non-`NONE` profile takes precedence: `MutableAssaultConfig.fromConfig(...)` first copies the static
`assault.type`/parameter values, then applies the profile defaults over them -- so static toggles and parameters are
overridden by the profile's defaults.

## The `Assault` SPI

```java
public interface Assault {
    AssaultType type();                    // enum constant this implementation represents
    boolean isEnabled(MutableAssaultConfig config); // run it for the current configuration?
    String recordLabel();                  // stable label used in history and Markdown report
    int order();                           // chain position, ascending
    AssaultOutcome apply(AssaultContext context);  // perform the assault, return CONTINUE or ABORTED
}
```

`Assault` must be implemented as a `@ApplicationScoped` CDI bean. `AssaultContext` carries the JAX-RS
`ContainerRequestContext` (may be `null` in unit tests), the current `MutableAssaultConfig`, the `AssaultEngine` (use
`engine.recordAssault(method, label)` or `recordAssault(method, label, latencyMs)` to append to the history), and the
targeted method name.

`AssaultOutcome` has two values: `CONTINUE` (let the next enabled assault run) and `ABORTED` (short-circuit the chain;
use it whenever the request must not reach the endpoint).

## Adding a new assault

The vertical slice of an assault touches the following places plus tests:

1. **Add the enum constant** to `AssaultType`, e.g. `RANDOM_PAYLOAD`.
2. **Implement the assault** in `io.quarkiverse.goblin.assault`:
   - `@ApplicationScoped` class implementing `Assault`;
   - return the new constant from `type()`, a descriptive label from `recordLabel()`, and a chain position from
     `order()` (stick to 10/20/30/40 increments to keep the ordering predictable);
   - read the parameters from the config and return `CONTINUE` or `ABORTED` as appropriate (see the existing assaults
     for the `engine.recordAssault(...)` pattern).
3. **Wire the configuration**:
   - add a `@ConfigGroup` section to `GoblinConfig` (the `AssaultConfig` group) holding the new parameters, with
     defaults and `@WithDefault`;
   - add the field to the immutable `AssaultSettings` (field, builder field, `toBuilder()`) with its built-in default;
   - add the matching toggle to `MutableAssaultConfig` (e.g. `isRandomPayloadEnabled()`/`setRandomPayloadEnabled(...)`),
     writing through `update(...)` so the change is published atomically and persisted, and include it in
     `fromConfig(...)`, `resetToDefaults()`, `applyProfileDefaults(...)` (profiles turn every server-side toggle off),
     `describeAssaults()` and `hasAnyAssaultEnabled()`;
   - persist it in `GoblinStatePersistence` (both `save` and `load`, with a default for older state files).
4. **Dev UI (optional but expected for parity)**: expose the toggle and parameters in `GoblinJsonRPCService`
   (`getStatus`, `getConfig`, a toggle method, `applyConfig`, and the `disableAll` kill switch), then add the editors to
   the Dev UI card, mirroring the existing panels (see [runtime-dev/README.md](../runtime-dev/README.md)).

Then add unit tests in the `runtime` module (the `Assault` contract is testable with a plain `AssaultContext` and a
real `MutableAssaultConfig`) and integration tests in `integration-tests`. All new or modified methods must carry
Javadoc.

## Conventions

- Keep `type()`, `recordLabel()` and the config keys stable: they are part of the user-facing surface (config, history
  and Markdown report).
- Log a `WARN` (not a failure) when a configured parameter cannot be honored; fall back to a safe default.
- Follow the project's zero-code-modification philosophy: an assault must not require users to touch their application
  code.