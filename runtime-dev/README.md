# Goblin Runtime Dev -- Dev UI backend

Companion module loaded **only in dev mode** (the runtime extension declares it via `conditional-dev-dependencies`,
visible in `runtime/target/classes/META-INF/quarkus-extension.properties`). It hosts the service the Dev UI front end
talks to, and nothing it contains ever ships to production.

See [runtime/README.md](../runtime/README.md) for the assault SPI this module builds on, and
[deployment/README.md](../deployment/README.md) for how the Dev UI card is registered at build time.

## The JSON-RPC service

`io.quarkiverse.goblin.dev.GoblinJsonRPCService` is a `@ApplicationScoped` bean exposed to the Dev UI through
[`JsonRPCProvidersBuildItem`](../deployment/src/main/java/io/quarkiverse/goblin/deployment/GoblinDevUIProcessor.java).
Every **public method** becomes a JSON-RPC endpoint callable from the front-end components
(`qwc-goblin-dashboard.js`, `qwc-goblin-history.js`) by method name, e.g. `this.jsonRpc.toggleLatency()`.

| Group | Method | Purpose |
|---|---|---|
| Status | `getStatus()` | Active flag, pending auto-off, all toggles, layers, target level |
| Status | `toggleActive()` / `setActive(boolean)` | Master on/off |
| Status | `disableAll()` | Kill switch: chaos off, every assault off, profile `NONE` |
| Status | `startAutoOff(minutes)` / `cancelAutoOff()` | Schedule or cancel the engine-side auto-off (remaining time in `getStatus().autoOffRemainingMs`) |
| Config | `getConfig()` | Full mutable configuration snapshot |
| Config | `setProfile(profile)` | Apply a predefined assault profile |
| Config | `applyConfig(config)` | Apply a (partial) configuration at once: import, custom profiles, chaos layers |
| Config | `resetDefaults()` | Restore the built-in defaults |
| Toggles | `toggleLatency()` / `toggleException()` / `toggleHttpStatus()` / `toggleDependencyDegradation()` / `toggleResponseBody()` / `toggleResponseHeader()` | Flip a single assault |
| Toggles | `toggleClientLatency()` / `toggleClientException()` | Flip a client-side assault |
| Editors | `setLatencyRange(minMs, maxMs)` | Update latency bounds |
| Editors | `setExceptionConfig(type, message)` | Update exception class/message |
| Editors | `setHttpStatusConfig(code, message)` | Update status code/body |
| Editors | `setResponseBodyConfig(mode, percentage)` | Update body transformation mode/size |
| Editors | `setResponseHeaderInfo(name, action, value)` / `removeResponseHeader(name)` | Set or drop a response header rule |
| Editors | `setTargetLevel(level)` | Update percentage of affected requests |
| History | `getHistory()` / `clearHistory()` | Read/clear the assault history |
| Counters | `getCounters()` / `resetCounters()` | Read (total, per type, per source) / reset the assault counters |
| Report | `getMarkdownReport()` | Export the Markdown resilience report |

Setter methods may return a `warning` field carrying human-readable corrections when the request could not be applied
verbatim (e.g. an inverted latency range is swapped, an invalid status code falls back to 503). Mutations go through
`MutableAssaultConfig`, whose setters publish the change and notify the change listener, so it is persisted to
`.goblin-state.json` and survives a restart automatically -- as long as the field is serialized by
`GoblinStatePersistence`.

## Extending the Dev UI for a new assault

1. Add the toggle + parameter editors to `GoblinJsonRPCService` following the existing pattern (a `toggleX()` method
   and a `setXConfig(...)` editor returning a `warning`).
2. Wire the corresponding section into `qwc-goblin-dashboard.js` in
   [deployment/src/main/resources/dev-ui/](../deployment/src/main/resources/dev-ui/) using `jsonRpc` calls that match
   the new method names.
3. Also expose the new toggle in `getStatus`, `getConfig` and `applyConfig`, and switch it off in `disableAll`.
4. Persistence is not automatic for a new field: every setter notifies the change listener, but the field must also be
   written and read by `GoblinStatePersistence` (and have a place in `AssaultSettings`, `resetToDefaults()` and
   `MutableAssaultConfig.fromConfig(...)`, see [runtime/README.md](../runtime/README.md#adding-a-new-assault)).

## Conventions

- Log a `WARN` whenever the user changes the runtime configuration through the Dev UI.
- Every public method is part of the front-end contract: keep them stable or bump them in sync with the
  `qwc-*.js` components.
- The module must stay dependency-light: it only depends on the runtime, Vert.x JSON, and the engine.