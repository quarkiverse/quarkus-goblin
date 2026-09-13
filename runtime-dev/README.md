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
| Status | `getStatus()` | Active flag, all toggles, target level |
| Status | `toggleActive()` / `setActive(boolean)` | Master on/off |
| Config | `getConfig()` | Full mutable configuration snapshot |
| Toggles | `toggleLatency()` / `toggleException()` / `toggleHttpStatus()` / `toggleDependencyDegradation()` | Flip a single assault |
| Editors | `setLatencyRange(minMs, maxMs)` | Update latency bounds |
| Editors | `setExceptionConfig(type, message)` | Update exception class/message |
| Editors | `setHttpStatusConfig(code, message)` | Update status code/body |
| Editors | `setTargetLevel(level)` | Update percentage of affected requests |
| History | `getHistory()` / `clearHistory()` | Read/clear the assault history |
| Report | `getMarkdownReport()` | Export the Markdown resilience report |

Setter methods may return a `warning` field carrying human-readable corrections when the request could not be applied
verbatim (e.g. an inverted latency range is swapped, an invalid status code falls back to 503). Mutations go through
`MutableAssaultConfig`, which calls `notifyChange()` so the change is persisted to `.goblin-state.json` and survives a
restart automatically.

## Extending the Dev UI for a new assault

1. Add the toggle + parameter editors to `GoblinJsonRPCService` following the existing pattern (a `toggleX()` method
   and a `setXConfig(...)` editor returning a `warning`).
2. Wire the corresponding section into `qwc-goblin-dashboard.js` in
   [deployment/src/main/resources/dev-ui/](../deployment/src/main/resources/dev-ui/) using `jsonRpc` calls that match
   the new method names.
3. Because `MutableAssaultConfig.notifyChange()` runs on every setter, no extra work is needed for persistence.

## Conventions

- Log a `WARN` whenever the user changes the runtime configuration through the Dev UI.
- Every public method is part of the front-end contract: keep them stable or bump them in sync with the
  `qwc-*.js` components.
- The module must stay dependency-light: it only depends on the runtime, Vert.x JSON, and the engine.