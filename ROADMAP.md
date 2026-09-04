# Goblin Roadmap

> Chaos engineering extension for Quarkus -- inject latency, exceptions, HTTP failures, and dependency degradation into your running application.

Current status: **experimental** (v0.0.2)

---

## v0.1.0 -- Stabilization & Robustness

- [ ] **Persist Dev UI config across restarts**
  Currently all Dev UI changes are lost on restart. Serialize `MutableAssaultConfig` to a `.goblin-state.json` file in the project directory on each change, and reload it at startup.

- [ ] **Thread-safe assault history**
  `AssaultEngine.history` uses a plain `ArrayList` which is not thread-safe. Concurrent HTTP requests can corrupt the list. Replace with `CopyOnWriteArrayList` or wrap access in synchronized blocks.

- [ ] **Validate assault parameters at startup**
  Reject invalid configurations early: ensure `minLatency <= maxLatency`, HTTP status code is in 100-599 range, and the configured exception class exists and has a `String` constructor. Log clear error messages on misconfiguration.

- [ ] **Log reflection fallback on exception instantiation**
  When `AssaultEngine.createException()` fails to instantiate the configured exception class, it silently falls back to `RuntimeException`. Add a `WARN` log with the original error to help users diagnose misconfigured exception types.

- [ ] **Integration tests for package-based targeting**
  The `include-packages` and `exclude-packages` targeting logic has no dedicated integration tests. Add test cases that verify requests to endpoints in included/excluded packages are correctly affected or spared.

- [ ] **Integration tests for annotation-based exclusion**
  The `exclude-annotations` targeting logic is untested at the integration level. Add a sample resource annotated with a custom annotation, configure exclusion, and verify the endpoint is not intercepted.

---

## v0.2.0 -- New Injection Capabilities

- [ ] **Client-side assault via REST Client**
  Intercept outgoing calls made with MicroProfile REST Client or Quarkus REST Client Reactive. Inject latency and exceptions on the client side to simulate downstream failures without touching the remote service.

- [ ] **Client-side assault via Vert.x Web Client**
  Extend client-side chaos to Vert.x `WebClient` calls, which are common in reactive Quarkus applications. Use Vert.x handlers to inject delays and failures before the request is dispatched.

- [ ] **Response body injection**
  Add a new assault type that truncates or inflates the response body. Useful for testing how clients handle partial JSON, oversized payloads, or unexpected content lengths.

- [ ] **HTTP header injection**
  Inject, modify, or remove HTTP response headers. Simulate rate-limiting (`Retry-After`, `X-RateLimit-Remaining`), cache headers, or custom error headers returned by upstream proxies.

- [ ] **Predefined composite assault modes**
  Bundle common assault combinations into named profiles: `SLOW_FAILURE` (latency + exception), `INTERMITTENT` (percentage-based random HTTP 500), `TIMEOUT` (very high latency). Configurable via `quarkus.goblin.assault.profile`.

---

## v0.3.0 -- Dev UI & Observability

- [ ] **Micrometer/Prometheus metrics**
  Expose assault counters and latency histograms via Micrometer so they appear in existing Prometheus/Grafana dashboards. Metrics: `goblin_assaults_total` (tagged by type), `goblin_latency_injected_seconds` (histogram), `goblin_active` (gauge).

- [ ] **History filtering in Dev UI**
  Add filter controls to the History panel: filter by assault type (checkboxes), by endpoint method (text input), and by date range (time pickers). Useful when the history buffer is large.

- [ ] **OpenTelemetry tracing integration**
  Create an OTel span for each injected assault, with attributes for assault type, target method, and injected value. Link the assault span to the parent request span for end-to-end trace correlation.

- [ ] **Real-time charts in Dev UI**
  Add lightweight charts to the dashboard: a histogram of injected latency values and a time-series of assault rate. Use a minimal charting library compatible with Lit web components.

- [ ] **Saved scenarios**
  Allow users to save the current assault configuration as a named scenario (e.g. "circuit breaker test", "high latency scenario") and reload it later. Store scenarios in a `.goblin/scenarios/` directory as JSON files.

---

## v0.4.0 -- Extensions & Ecosystem

- [ ] **gRPC support**
  Implement gRPC `ServerInterceptor` and `ClientInterceptor` to inject latency and exceptions on gRPC calls. Cover both unary and streaming RPCs.

- [ ] **GraphQL support**
  Instrument Quarkus GraphQL `DataFetcher`s to inject failures on specific GraphQL fields. Allow targeting by field name or parent type.

- [ ] **Reactive Routes support**
  Extend Goblin beyond JAX-RS to cover Vert.x reactive routes (`@Route`-annotated methods). Use Vert.x route handlers for injection.

- [ ] **GraalVM native compilation**
  Validate that Goblin works correctly in native mode. Fix any reflection or serialization issues. Add a native compilation integration test to the CI pipeline.

- [ ] **Chaos as Code (CI mode)**
  Support declarative YAML/TOML scenario files that can be executed non-interactively in CI pipelines. Enable teams to run chaos experiments in staging environments without a browser or Dev UI.

---

## v1.0.0 -- Maturity

- [ ] **Stable public API**
  Define a stable Java API for the core engine (`AssaultEngine`, `AssaultType`, `AssaultRecord`) with `@Experimental` annotations removed. Document the API contract and versioning policy for third-party extensions.

- [ ] **Controlled test-mode activation**
  Allow Goblin to be activated in `@QuarkusTest` with explicit opt-in (`quarkus.goblin.test-mode.enabled=true`). Add safeguards: fail the build if Goblin config is detected in production profiles.

- [ ] **Structured audit trail**
  Replace plain-text `WARN` logs with structured JSON logging for each assault event. Include timestamps, method, type, config snapshot, and request metadata for log aggregation and compliance.

- [ ] **Advanced scenario-based documentation**
  Write dedicated guides for common resilience testing patterns: testing circuit breakers with `@CircuitBreaker`, testing retries with `@Retry`, testing fallbacks with `@Fallback`, and establishing performance baselines.
