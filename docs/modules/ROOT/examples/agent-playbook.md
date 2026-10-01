# Goblin agent playbook

Goblin injects faults into a Quarkus application running in dev mode, so you can check that its resilience
mechanisms hold. Goblin produces the signals; you conclude. Read this before your first experiment.

The tools below are the Goblin JSON-RPC methods. Over Dev MCP they carry the extension prefix, e.g.
`quarkus-goblin_getStatus`.

## Safety rules

1. **Dev mode only.** Chaos never runs in a production build. Never try to make it.
2. **Auto-off first.** Call `startAutoOff` (a few minutes) *before* arming anything, and every time you re-arm.
3. **Record, then restore.** Read `getConfig` before the first change. When you are done, put that configuration
   back and call `disableAll`. Configuration changes are saved to `.goblin-state.json` and survive restarts: a
   configuration you leave behind is the developer's next session.
4. **One variable at a time.** One assault, one layer, one hypothesis per experiment.
5. **Only arm what the application has and owns.** Arm only layers listed in `availableLayers` (`getStatus`), and
   nothing `RESILIENCE.md` puts out of bounds.
6. **Do not work around a disabled tool.** The tools that arm or change chaos are opt-in. If one is missing or fails
   with `Method not found`, ask the developer to enable it: Dev UI, Settings (gear icon, top right), Dev MCP tab,
   Tools page. Never arm chaos any other way: not by editing `application.properties` or `.goblin-state.json`, and
   not through another tool that changes the configuration (e.g. the Dev UI configuration tools).
7. **Keep the evidence.** Read `getHistory` and `getCounters` before `clearHistory` or `resetCounters`.

## What each layer means for Fault Tolerance

A request is assaulted on at most one armed layer, the deepest one whose `level` draw passes: DATABASE, then
MESSAGING, then SERVICE, then HTTP_IN. Arm only the layer under test, or you will not know which one fired.

| Layer | Where the fault is raised | Fault Tolerance reacts? |
|---|---|---|
| SERVICE | On application bean methods, *inside* the Fault Tolerance interceptor (priority 4100 vs 4010) | Yes: this is the layer that exercises `@Timeout`, `@Retry`, `@CircuitBreaker`, `@Fallback`, `@Bulkhead` |
| DATABASE | On JDBC connection acquisition (Agroal), inside the method that queries | Yes, for the guarded method that acquires the connection |
| HTTP_OUT | On outgoing REST Client and Vert.x WebClient calls, with `clientLatencyEnabled` / `clientExceptionEnabled` | Yes, for a guarded REST Client interface or a guarded method making the call |
| HTTP_IN | On inbound JAX-RS requests, before the resource method | No: the request is aborted before any annotation can react. Use it for unprotected endpoints and client-visible behaviour |
| MESSAGING | On `@Incoming` consumers, outside Fault Tolerance | No: it tests the channel failure strategy |

- Latency and exception fire on every layer; HTTP status, dependency degradation, response body and response
  headers on HTTP_IN only.
- `level` is the percentage of eligible calls assaulted. On SERVICE, each retry attempt draws `level` again: at 100
  every attempt fails and `@Fallback` answers; below 100 some retries recover.
- SERVICE assaults only the *first* application bean a request enters, not every bean below it. If the guarded
  method is called by another bean, the fault lands on that caller and never reaches the guard. Then use a fault
  raised inside the guarded method instead (HTTP_OUT for its REST calls, DATABASE for its JDBC connections), or ask the
  developer to exclude the caller with `quarkus.goblin.target.exclude-packages` / `exclude-annotations`.
- `@Timeout` only fires if the injected latency exceeds its value: pick the latency range from the inventory.
- Latency that would block is skipped on a Vert.x event-loop thread (a WARN is logged once).

## Where to start: the resilience inventory

The `resilienceInventory` resource lists every guarded method: its annotations with their effective parameters,
`metricMethodTag`, and `reachableBy`, the layers known to reach it inside Fault Tolerance. `reachableBy` never lists
DATABASE, which an annotation index cannot see: DATABASE also reaches any guarded method that acquires a JDBC
connection, so its absence is not a finding. SERVICE in `reachableBy` means the method is woven, not that a request
reaches it first: check which bean the endpoint calls. A guarded method with an empty `reachableBy` cannot be exercised through
SERVICE or HTTP_OUT: report it, do not force it. A single entry whose
class is `-` means the application declares no Fault Tolerance annotation.
`metricMethodTag` is checked for application beans; for a REST Client interface (`reachableBy` HTTP_OUT), confirm the
tag in `/q/metrics` before relying on it.

The application's own promises (its SLOs, what "healthy" means, which dependency each mechanism protects) are not in
Goblin. Read `RESILIENCE.md` at the project root if it exists; if it does not, ask the developer.

## How to observe

- **What Goblin did**: `getHistory` (method, type, `latencyMs`, `source`: server, service, rest-client, webclient,
  database, messaging), `getCounters`, `getMarkdownReport`.
- **What Goblin reported elsewhere**: the `goblin_assaults_total{type, source}` and
  `goblin_latency_injected_seconds` metrics (with `quarkus-goblin-metrics`), and one `goblin.assault` span per assault
  with `goblin.assault.*` attributes, linked to the request span (with `quarkus-goblin-opentelemetry`).
- **What the client saw**: call the endpoint yourself. Record the status, the body and the elapsed time.
- **What Fault Tolerance did**: the `ft_*` metrics at `/q/metrics` (present when the application has
  `quarkus-micrometer` with a registry, e.g. Prometheus). Filter on `method="<metricMethodTag>"`.
  - `ft_invocations_total{result, fallback}`: `fallback="applied"` means the fallback answered.
  - `ft_retry_calls_total{retried, retryResult}` and `ft_retry_retries_total`.
  - `ft_timeout_calls_total{timedOut}`.
  - Circuit breaker: `ft_circuitbreaker_state_current{state="open"}` is `1` while open;
    `ft_circuitbreaker_opened_total` counts openings; `ft_circuitbreaker_calls_total{circuitBreakerResult}` shows
    `circuitBreakerOpen` for calls rejected without running.
  - `ft_bulkhead_calls_total{bulkheadResult}`, `ft_ratelimit_calls_total{rateLimitResult}`.
- **A circuit breaker without metrics**: once it is open, the method no longer runs, so SERVICE assaults on it stop
  appearing in `getHistory` while the client keeps failing fast.

## The loop

1. **Hypothesis**: one sentence from the inventory and the promises. "With 800 ms latency on SERVICE, `@Timeout(500)`
   on `OrderService.place` fires and the client gets the fallback in under 1 s."
2. **Assault**: `startAutoOff`, arm one assault on one layer, set `level`, then `setActive(true)`.
3. **Observation**: drive enough calls (a circuit breaker needs its `requestVolumeThreshold`), then read the
   history, the client outcome and the `ft_*` metrics.
4. **Conclusion**: held, did not hold, or inconclusive (say why: wrong layer, not enough calls, latency below the
   timeout).
5. **Restore**: `disableAll`, put the recorded configuration back.

## The report

For each experiment: the hypothesis, the exact configuration (`getConfig`, or the saved scenario name), the
observations (Goblin history, client outcome, `ft_*` values), and the verdict for each guarded method of the
inventory you tested. List the guarded methods you could not exercise, and why, and what you left alone because it
is out of bounds or belongs to a layer the application does not own. Attach `getMarkdownReport`.
