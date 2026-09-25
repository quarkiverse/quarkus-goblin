# Goblin Metrics

Optional Micrometer / Prometheus metrics for the Goblin chaos engineering extension.

## What it does

One `@ApplicationScoped` bean (`GoblinMetricsObserver`) implements the engine's `AssaultObserver` SPI and registers
three meters on the application's `MeterRegistry`:

- `goblin.assaults.total` -- counter tagged by `type` + `source` (`server`, `service`, `rest-client`, `webclient`, `database`, `messaging`)
- `goblin.latency.injected.seconds` -- timer of the delays actually injected, tagged by `source`, with a percentile
  histogram (`_bucket` series in Prometheus)
- `goblin.active` -- functional gauge over `AssaultEngine.isActive()`

The module depends on the Micrometer API only (`quarkus-micrometer`): the application picks its registry (e.g.
`quarkus-micrometer-registry-prometheus` for `/q/metrics`), and every Micrometer backend receives the same meters.

## The AssaultObserver SPI

`io.quarkiverse.goblin.AssaultObserver` (in `runtime`) is the notification hook the engine fires on every recorded
assault and active-state change. Micrometer metrics and OpenTelemetry tracing (`quarkus-goblin-opentelemetry`) both
consume it; post-assault assertions will reuse it. Observers run on the request path -- implementations must not
throw, and the engine guards against a failing observer without breaking the assault.

## Adding a new metric

Add a meter in `GoblinMetricsObserver` and derive it from the `AssaultRecord` (method, type, latencyMs, timestamp)
or engine state. Keep the name and tag conventions: dots for Micrometer (Prometheus normalizes to `_`), lower-case
tag values. The one exception is `type` for the response header assaults (`response-header-set:<name>`), which carries
the header name as configured: one series per header rule.

## Testing

- `GoblinMetricsObserverTest` -- unit test on a `SimpleMeterRegistry` (immediate values).
- `GoblinMetricsIntegrationTest` (in `integration-tests`) -- end-to-end with a real Prometheus backend; read carefully:
  Prometheus-backed counters/timers are step-based, so the suite asserts the settled step value (polling) and runs with
  `quarkus.micrometer.export.prometheus.step=PT1S`.