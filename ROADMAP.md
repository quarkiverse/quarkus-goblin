# Goblin Roadmap

> Chaos engineering extension for Quarkus -- inject latency, exceptions, HTTP failures, and dependency degradation into your running application.

Current status: **preview** (v0.4.0 released, v0.5.0 in development)

---

## v0.1.0 -- Stabilization & Robustness

- [x] **Persist Dev UI config across restarts**
  Currently all Dev UI changes are lost on restart. Serialize `MutableAssaultConfig` to a `.goblin-state.json` file in the project directory on each change, and reload it at startup.

- [x] **Thread-safe assault history**
  `AssaultEngine.history` uses a plain `ArrayList` which is not thread-safe. Concurrent HTTP requests can corrupt the list. Replace with `CopyOnWriteArrayList` or wrap access in synchronized blocks.

- [x] **Validate assault parameters at startup**
  Reject invalid configurations early: ensure `minLatency <= maxLatency`, HTTP status code is in 100-599 range, and the configured exception class exists and has a `String` constructor. Log clear error messages on misconfiguration.

- [x] **Log reflection fallback on exception instantiation**
  When `ExceptionAssault.createException()` fails to instantiate the configured exception class, it silently falls back to `RuntimeException`. Add a `WARN` log with the original error to help users diagnose misconfigured exception types.

- [x] **Integration tests for package-based targeting**
  The `include-packages` and `exclude-packages` targeting logic has no dedicated integration tests. Add test cases that verify requests to endpoints in included/excluded packages are correctly affected or spared.

> Dropped: integration tests for annotation-based exclusion. Adding a custom `@ChaosExcluded` annotation to the application under test would violate the project's zero-code-modification principle. The `exclude-annotations` feature stays, but only in its non-intrusive form (reusing annotations already present, e.g. MicroProfile Fault Tolerance types). See issue #21.

---

## v0.2.0 -- New Injection Capabilities

- [x] **Client-side assault via REST Client**
  Intercept outgoing calls made with MicroProfile REST Client or Quarkus REST Client Reactive. Inject latency and exceptions on the client side to simulate downstream failures without touching the remote service.

- [x] **Client-side assault via Vert.x Web Client**
  Extend client-side chaos to Vert.x `WebClient` calls, which are common in reactive Quarkus applications. The application arms a client once with `GoblinWebClient.enable(webClient)`; an interceptor attached through Vert.x's internal `WebClientInternal` mechanism applies delays and failures before the request is dispatched (the remote service is never reached for exceptions).

- [x] **Response body injection**
  Add a new assault type that truncates or inflates the response body. Useful for testing how clients handle partial JSON, oversized payloads, or unexpected content lengths.

- [x] **HTTP response header injection**
  Inject, modify, or remove HTTP response headers. Simulate rate-limiting (`Retry-After`, `X-RateLimit-Remaining`), cache headers, or custom error headers returned by upstream proxies.

- [x] **Predefined composite assault modes**
  Bundle common assault combinations into named profiles: `SLOW_FAILURE` (latency + exception), `INTERMITTENT` (percentage-based random HTTP 500), `TIMEOUT` (very high latency). Configurable via `quarkus.goblin.assault.profile`.

- [x] **History panel refactor**
  Turn the Assault History screen from a "debug table" into a "chaos testing console". Today it loads once on mount (no live refresh), renders in insertion order (oldest first), has no filters, a long `Active Config` column that hurts readability, and a bare `toLocaleTimeString()` timestamp. Add: periodic auto-refresh while the tab is open, newest-first ordering, filtering by type and method text (+ date range), a compact config cell expandable on click, and a summary band (totals per assault type, average injected latency). Follow-ups: confirmation on Clear History, richer timestamp (date/ms/timezone), Markdown export in a dedicated panel instead of an inline block.

---

## v0.3.0 -- Observability & Multi-layer chaos

- [x] **Micrometer/Prometheus metrics**
  Expose assault counters and latency histograms via Micrometer so they appear in existing Prometheus/Grafana dashboards. Metrics: `goblin_assaults_total` (tagged by type), `goblin_latency_injected_seconds` (histogram), `goblin_active` (gauge). Delivered as the optional `quarkus-goblin-metrics` module (#46), shipped early in 0.2.1.

- [x] **OpenTelemetry tracing integration**
  Create an OTel span for each injected assault, with attributes for assault type, target method, and injected value. Link the assault span to the parent request span for end-to-end trace correlation. Delivered as the optional `quarkus-goblin-opentelemetry` module (#47), shipped early in 0.2.1.

- [x] **Multi-layer chaos assaults** (#54)
  - [x] Phase 0 -- layer model, persistence and Dev UI check-boxes.
  - [x] Phase 1 -- SERVICE layer: CDI interceptor at `@Priority(4100)`, inside MicroProfile Fault Tolerance.
  - [x] Phase 2 -- DATABASE layer: Agroal pool interceptor on JDBC connection acquisition (JDBC, Hibernate ORM, Panache).
  - [x] Phase 3 -- MESSAGING layer: interceptor on `@Incoming` consumers, outside Fault Tolerance; each consumed message
    resolves its own layer.
  - Follow-ups: reactive consumers and Hibernate Reactive / reactive SQL clients, outgoing messages (`Emitter`).

- [x] **Controlled test-mode activation**
  Chaos stays off in `@QuarkusTest` unless `quarkus.goblin.test.enabled=true` (or `AssaultEngine.setActive(true)` from a
  test).

---

## v0.4.0 -- Resilience verification, human or agent-driven

> Theme: move Goblin from chaos *injection* to resilience *verification*, driven from the Dev UI by a human or through Dev MCP by an AI agent. Scope informed by the [quarkus-goblin-demo](https://github.com/ErwanLT/quarkus-goblin-demo) application and a live test where an AI agent, given only an `AGENTS.md`, found a resilience defect in the demo on its own. Saved scenarios were moved from v0.3.0; post-assault assertions were dropped in favour of the agent playbook below, which now covers the conclusion step.

- [x] **Keep chaos off across live reloads after a manual deactivation**
  Deactivating chaos (master toggle, `setActive(false)`) is lost on the next dev-mode live reload: the active flag is not persisted and comes back from `quarkus.goblin.enabled`, while a fired auto-off already survives a reload. Persist the manual deactivation the same way, so fixing code during a chaos session never wakes the goblin up. Found by the AI agent during the live test.

- [x] **Consistent `Content-Type` for the HTTP status and dependency degradation assaults**
  Both assaults abort the request with a plain-text body but no media type, so Quarkus REST negotiates it from the resource method: a resource producing JSON answers `Service Unavailable (Goblin chaos)` with a `Content-Type: application/json` header. Declare the media type of the body the assault actually sends. Found by the AI agent during the live test.

- [x] **Dev MCP tools**
  Expose the Goblin JSON-RPC methods as Quarkus Dev MCP tools, so an AI agent connected to `/q/dev-mcp` can read the status, arm layers, set an auto-off, read the history and the counters. Every method and parameter now carries a `@JsonRpcDescription` written for an agent; the description alone decides the MCP exposure, since Quarkus 3.38 serves a described method to both the Dev UI and MCP (and keeps an undescribed one in the Dev UI only, so `@JsonRpcUsage` is not needed). The default split follows one rule: reading the state or stopping chaos is enabled by default (`@DevMCPEnableByDefault`), arming or mutating chaos stays opt-in and is enabled by the developer in the Dev UI. A test guards the split, the descriptions and the parameter names, so a new method cannot be added without deciding which side of the line it falls on.

- [x] **Configuration change notifications**
  Add `AssaultObserver.onConfigChange` so observers see every configuration change, not only assaults and activation changes. Lets an application record the exact attack it went through (e.g. to replay it after a fix) when the configuration changes during an incident.

- [x] **Saved scenarios**
  Allow users to save the current assault configuration as a named scenario (e.g. "circuit breaker test", "high latency scenario") and reload it later, from the Dev UI, JSON-RPC and Dev MCP. Store scenarios in a `.goblin/scenarios/` directory as JSON files. The Dev UI custom profiles cover part of it today, but they live in the browser only.

- [x] **Active goblin in the Dev UI**
  Show an animated goblin in the bottom-right corner of the Chaos Dashboard and History pages while chaos is active, and hide it as soon as chaos is off, so the state reads at a glance and not only from the status dot. A still image replaces the animation under reduced motion. Showing it in the attacked application's own pages is a separate step, not scheduled here.

- [x] **Agent playbook and resilience inventory** ([#72](https://github.com/quarkiverse/quarkus-goblin/issues/72))
  Ship a generic playbook for AI agents with the extension: safety rules (dev mode only, auto-off before every experiment, one variable at a time, back to the initial state), what each layer means for Fault Tolerance, how to observe, the hypothesis / assault / observation / conclusion loop, and the report. Exposed as a Dev MCP resource so agents discover it on their own, and published as a documentation page with a template for the application-specific part (the resilience promises each application makes). Includes how to see a circuit breaker's state (the `ft_*` metrics), which the live test showed was missing.
  Next to it, a second Dev MCP resource lists where the application believes it is protected: the MicroProfile Fault Tolerance annotations (`@Timeout`, `@Retry`, `@CircuitBreaker`, `@Fallback`, `@Bulkhead`, `@RateLimit`) of the application's methods and classes, indexed at build time with their main parameters, so an agent can derive its experiments without reading the code -- the resilience inventory of the "Option B" proposed in [#7](https://github.com/quarkiverse/quarkus-goblin/issues/7). An agent can then check its conclusion against the protection each method declares.

  The verification report has no pass/fail engine behind it: the agent writes it -- hypothesis, configuration, observations, then a verdict for each guarded method of the inventory -- and the Markdown export is its raw material. Goblin provides the signals, the agent or the human concludes. The companion pieces are the saved scenarios ([#49](https://github.com/quarkiverse/quarkus-goblin/issues/49)), which give a rerunnable configuration, and the configuration change notifications ([#71](https://github.com/quarkiverse/quarkus-goblin/issues/71)), which record the exact attack an application went through.

---

## v0.5.0 -- Reactive Routes & native build

> Two items, both close to the core: one more inbound HTTP entry point, and the guarantee that the extension does not break a native build. The other injection layers wait under *On demand*.

- [ ] **Reactive Routes support** ([#64](https://github.com/quarkiverse/quarkus-goblin/issues/64))
  Extend Goblin beyond JAX-RS to cover Vert.x reactive routes (`@Route`-annotated methods). Use Vert.x route handlers for injection. The closest thing to the core of the project here: the same role as JAX-RS on the inbound HTTP side.

- [ ] **GraalVM native build: non-regression only** ([#65](https://github.com/quarkiverse/quarkus-goblin/issues/65))
  Goblin is a dev-mode tool, so it does not have to *work* in native mode. What matters is that the extension does not break a native build when it sits in the dependencies. One non-regression test compiling a native application with the extension present, and nothing more.

---

## v1.0.0 -- Maturity

- [ ] **Stable public API**
  Define a stable Java API for the core engine (`AssaultEngine`, `AssaultType`, `AssaultEngine.AssaultRecord`, the `Assault` and `AssaultObserver` SPIs), which is currently preview and may change between minor versions. Document the API contract and versioning policy for third-party extensions.

- [ ] **Production-profile safeguard**
  Fail the build (or warn loudly) when Goblin assault configuration is detected in a production profile.

- [ ] **Structured assault logs**
  Replace the plain-text `WARN` logs with structured events whoever runs the experiment can read -- human or agent: timestamp, method, assault type, injected value, configuration snapshot. The goal is a log you can reason about during an experiment, not log aggregation for compliance, which is a production concern Goblin does not serve.

- [ ] **Advanced scenario-based documentation**
  Write dedicated guides for common resilience testing patterns: testing circuit breakers with `@CircuitBreaker`, testing retries with `@Retry`, testing fallbacks with `@Fallback`, and establishing performance baselines.

---

## On demand

> None of these betrays what Goblin is: each is a new injection layer, or a new Dev UI surface. What they share is coupling to a Quarkus area, so they are picked up when someone asks for them in an issue, not scheduled here.

- [ ] **gRPC support** ([#62](https://github.com/quarkiverse/quarkus-goblin/issues/62))
  Implement gRPC `ServerInterceptor` and `ClientInterceptor` to inject latency and exceptions on gRPC calls. Cover both unary and streaming RPCs.

- [ ] **GraphQL support** ([#63](https://github.com/quarkiverse/quarkus-goblin/issues/63))
  Instrument Quarkus GraphQL `DataFetcher`s to inject failures on specific GraphQL fields. Allow targeting by field name or parent type.

- [ ] **Reactive coverage follow-ups** ([#67](https://github.com/quarkiverse/quarkus-goblin/issues/67))
  Reactive consumers, Hibernate Reactive / reactive SQL clients, and outgoing messages (`Emitter`). Follow-ups of the multi-layer work in v0.3.0.

- [ ] **Real-time charts in the Dev UI dashboard** ([#48](https://github.com/quarkiverse/quarkus-goblin/issues/48))
  A new Dev UI surface to maintain, and a debatable one: the counters and the history table may be enough to read an experiment.

---

## What Goblin is not

> Recorded so the next feature request does not relitigate them. Each of these was proposed and set aside on purpose.

- **Not a CI tool.** No scenario files run non-interactively, no pipeline runs ([#66](https://github.com/quarkiverse/quarkus-goblin/issues/66)). Chaos belongs in the hands of someone who is watching the application, in dev mode, with an auto-off set. A pipeline runs where nobody is watching.
- **Not a rules engine.** No declarative expectations evaluated into pass/fail ([#50](https://github.com/quarkiverse/quarkus-goblin/issues/50)). Goblin produces the signals; the agent or the human concludes. The playbook in v0.4.0 owns that step, so an experiment stays something you run rather than something you configure.
