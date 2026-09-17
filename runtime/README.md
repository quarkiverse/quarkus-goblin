# Goblin Runtime -- Assaults

This module hosts the runtime of the Goblin chaos extension. This guide documents the **assault abstraction**: the
existing assault types, how they are executed, and how to add a new one. If you plan to contribute a new assault, read
the [Adding a new assault](#adding-a-new-assault) section.

## Execution model

Requests go through `GoblinChaosFilter` (a JAX-RS `ContainerRequestFilter`). When the engine is active and the request
is eligible (`quarkus.goblin.*` targeting rules, percentage based on `quarkus.goblin.target.level`), the filter:

1. injects every `Assault` CDI bean available (`Instance<Assault>`);
2. sorts them by ascending `order()`;
3. applies each enabled assault in sequence;
4. stops as soon as an assault returns `AssaultOutcome.ABORTED`.

Assaults are discovered automatically: no manual registration is required, adding a bean implements the SPI is enough.

The same `GoblinChaosFilter` also implements a JAX-RS `ContainerResponseFilter`: on eligible responses it rewrites
the entity when the **response body** assault is enabled (truncate/inflate), so the transformation composes with every
other assault instead of replacing them.

Outgoing MicroProfile / Quarkus REST Client calls are handled by `GoblinChaosClientFilter` (a JAX-RS
`ClientRequestFilter`, registered globally as an unremovable bean by the deployment build step). It reuses the
`engine.shouldAssaultClient()` level gate (same `quarkus.goblin.target.level` percentage as the server side, no package
targeting) and the client-side toggles from `MutableAssaultConfig` (`clientLatencyEnabled` / `clientExceptionEnabled`).
When enabled it applies latency before the request is dispatched and/or throws the configured exception before the call
leaves the application -- the remote service is never reached for exception assaults. History records use the
`REST-Client <METHOD> <URI>` method format.

## Existing assaults

| Assault | Class | `order()` | Enabled via | Behavior | Config keys (`quarkus.goblin.*`) |
|---|---|---|---|---|---|
| Latency | `LatencyAssault` | 10 | `MutableAssaultConfig.isLatencyEnabled()` | Random delay between `min-` and `max-milliseconds` (inclusive), then continues the chain | `assault.latency.min-milliseconds`, `assault.latency.max-milliseconds` |
| Exception | `ExceptionAssault` | 20 | `MutableAssaultConfig.isExceptionEnabled()` | Throws the configured exception class (String constructor) before the method runs; falls back to `RuntimeException` with a WARN log | `assault.exception.type`, `assault.exception.message` |
| HTTP status | `HttpStatusAssault` | 30 | `MutableAssaultConfig.isHttpStatusEnabled()` | Aborts the request with the configured status code and body (`ABORTED`) | `assault.http-status.code`, `assault.http-status.message` |
| Dependency degradation | `DependencyDegradationAssault` | 40 | `MutableAssaultConfig.isDependencyDegradationEnabled()` | Aborts the request with a fixed 503 response, to exercise outbound `@Fallback`/`@Retry` (`ABORTED`) | none (fixed values) |
| Response body | `GoblinChaosFilter` (response phase) | n/a | `MutableAssaultConfig.isResponseBodyEnabled()` | Rewrites the emitted entity via `ResponseBodyTransformer`: `TRUNCATE` keeps the first `percentage`% of the body, `INFLATE` pads it with a `[goblin-response-inflated]` marker up to `percentage`% of the original size | `assault.body.mode`, `assault.body.percentage` |

All classes live in `io.quarkiverse.goblin.assault`, except the response body assault which runs directly in the
`ContainerResponseFilter` phase of `GoblinChaosFilter` (it operates on the emitted entity, not on the inbound request).
The chain order convention is: latency first (10), then request-aborting assaults by increasing severity (20, 30, 40).

### Client-side assaults

Client-facing latency and exception assaults are not part of the `Assault` chain above; they run in
`GoblinChaosClientFilter`. They are driven by the runtime-only toggles `clientLatencyEnabled` and
`clientExceptionEnabled` on `MutableAssaultConfig` (default `false`, persisted by `GoblinStatePersistence`,
no static `GoblinConfig` key), reusing the latency range and exception class/message configured for the server side.

### Profiles

`quarkus.goblin.assault.profile` (`NONE|SLOW_FAILURE|INTERMITTENT|TIMEOUT`, default `NONE`) bundles common
combinations into one config line. `MutableAssaultConfig.setProfile(...)` resets the toggles to the profile's defaults
and `describeAssaults()` mentions the active profile; individual assaults remain user-overridable afterwards.
`GoblinStatePersistence.restoreProfile(...)` restores only the label when loading persisted state, so per-assault
overrides survive restarts.

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

The vertical slice of an assault touches four places plus tests:

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
   - add the matching toggle to `MutableAssaultConfig` (e.g. `isRandomPayloadEnabled()`/`setRandomPayloadEnabled(...)`),
     making sure it calls `notifyChange()` and is included in `describeAssaults()`/`hasAnyAssaultEnabled()`.
4. **Dev UI (optional but expected for parity)**: add the toggle and parameter editors to the Dev UI card, mirroring
   the existing panels.

Then add unit tests in the `runtime` module (the `Assault` contract is testable with a plain `AssaultContext` and a
real `MutableAssaultConfig`) and integration tests in `integration-tests`. All new or modified methods must carry
Javadoc.

## Conventions

- Keep `type()`, `recordLabel()` and the config keys stable: they are part of the user-facing surface (config, history
  and Markdown report).
- Log a `WARN` (not a failure) when a configured parameter cannot be honored; fall back to a safe default.
- Follow the project's zero-code-modification philosophy: an assault must not require users to touch their application
  code.