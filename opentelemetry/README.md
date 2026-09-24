# Goblin OpenTelemetry

Optional OpenTelemetry tracing for the Goblin chaos engineering extension.

## What it does

One `@ApplicationScoped` bean (`GoblinTracingObserver`) implements the engine's `AssaultObserver` SPI and emits one
`goblin.assault` span per applied assault, linked to the request span that triggered it (parent-child relationship
through the current tracing context). Every assault span uses the INTERNAL span kind (the OpenTelemetry default): the
span annotates an in-process moment -- the application of the injected delay or failure -- and performs no network
call of its own, so labelling it `CLIENT` or `SERVER` would fabricate phantom dependency edges in service graphs or
duplicate the request's own `SERVER` span topology.

Span attributes (prefix `goblin.assault.*`):

- `goblin.assault.type` -- latency / exception / http-status / dependency-degradation / response-body-* / response-header-*.
- `goblin.assault.source` -- `server`, `service`, `rest-client`, `webclient`, `database` or `messaging` (recorded by the
  engine with each assault).
- `goblin.assault.target.method` -- the recorded method identifier (e.g. `SampleResource.hello`, `WebClient GET http://...`).
- `goblin.assault.latency_ms` -- the delay actually injected (latency assaults only).
- `goblin.assault.status_code` -- the forced status (HTTP status / dependency degradation assaults).
- `goblin.assault.exception` -- the configured exception class (exception assaults, which also mark the span `ERROR`).
- `goblin.assault.config` -- the active configuration snapshot that fired the assault.

For latency assaults the span start timestamp is back-dated by the injected delay, so the delay itself is attributed
to the span in the trace waterfall -- the injected latency is a span attribute, never a replayed call. Back-dating
only applies to strictly positive delays and the start timestamp is never negative.

The module depends on `quarkus-opentelemetry`, so the OTel SDK is available as soon as the module is on the classpath
(the application continues to configure its exporters as usual).

## The AssaultObserver SPI

Same SPI as `quarkus-goblin-metrics` (`io.quarkiverse.goblin.AssaultObserver` in `runtime`). Metrics and tracing each
subscribe separately; the engine notifies every observer without breaking the assault if one fails.

## Adding a new attribute

Derive it from the `AssaultRecord` (method, type, latencyMs, timestamp, configSnapshot) or, for injected values that
the record does not carry (status code, exception class), from the engine's `MutableAssaultConfig`. Keep the
`goblin.assault.*` prefix and the exact value names documented above.

## Testing

- `GoblinTracingObserverTest` -- unit test on an in-process `SdkTracerProvider` with `InMemorySpanExporter`
  (`opentelemetry-sdk-testing`), asserting span name, kind, attributes, latency back-dating and parent linkage.
- `GoblinTracingIntegrationTest` (in `integration-tests`) -- end-to-end with `quarkus-opentelemetry` and
  `quarkus.otel.traces.exporter=cdi`, capturing spans through a CDI `SpanExporter` bean.