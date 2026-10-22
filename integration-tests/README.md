# Goblin Integration Tests

End-to-end `@QuarkusTest` suite exercising the extension through a real JAX-RS application
(`io.quarkiverse.goblin.it.SampleResource`) with the endpoints:

- `/api/hello`, `/api/slow`, `/api/unstable` -- plain endpoints for the `HTTP_IN` assaults;
- `/api/service/*` (`hello`, `slow`, `flaky`, `fallback`, `retry-fallback`, `timeout`, `guarded`, `nested`) -- calls
  into `SampleService` / `SampleDelegate`, guarded by Fault Tolerance annotations, for the `SERVICE` layer;
- `/api/db/ping` and `/api/db/retry` -- `SampleRepository` on an in-memory H2 datasource, without and with
  `@Retry` + `@Fallback`, for the `DATABASE` layer;
- `/api/proxy` (a proxy that calls `SampleClient` internally) and `/api/web-proxy` (a proxy that calls back through a
  Vert.x `WebClient`, armed by Goblin without any Goblin call in `SampleResource`) and `/api/mutiny-web-proxy` (the same
  with the Mutiny `WebClient`), handy to exercise client-side assaults in dev mode.

Reactive routes (`quarkus-reactive-routes`) cover the `HTTP_IN` layer on `@Route` methods: `SampleRoutes` serves
`/routes/hello`, `/routes/thread` (answers `event-loop` or `worker`), `/routes/blocking`, `/routes/json` and
`/routes/service` (calls `SampleService`), plus a `type = FAILURE` handler on `/routes/*` that answers injected
exceptions, as well as `/routes/trace` (reads the request-scoped `RouteTrace` set by a `@RouteFilter`) and the
`POST /routes/echo` route, and the routes exercising the response body wrapper: `/routes/fluent` (fluent
`putHeader(...).end(...)`), `/routes/json-default` (`RoutingContext.json`), `/routes/uni`, `/routes/multi` (streamed,
left untouched) and `/routes/boom` (throws). `SampleRouteFilters` holds that `@RouteFilter` and a pass-through `@Route` in front of the
REST endpoints `/api/passthrough/hello` and `/api/passthrough/service`, plus a `@NonBlocking` pass-through route in front of `/api/spared/hello`; `excluded/ExcludedRoutes` serves `/routes/excluded/service` for the excluded-entry-point tests.

`SampleConsumer` consumes the in-memory `orders` and `audits` channels for the `MESSAGING` layer.
`src/main/resources/application.properties` enables Goblin with latency defaults so the engine is active at boot, and
opts the tests in with `quarkus.goblin.test.enabled=true`.

The suite also exercises **client-side assaults**: `SampleClient` (`@RegisterRestClient(configKey = "sample-client")`,
targeting `http://localhost:8081`) verifies that the globally-registered `GoblinChaosClientFilter` attacks outbound
REST Client calls with latency and exceptions, and `GoblinWebClientIntegrationTest` verifies the same for Vert.x
`WebClient` calls, on a client armed with `GoblinWebClient.enable(...)` in the test itself and on the clients the
application creates, armed by the build-time rewrite.

## Test classes

| Test | Coverage |
|---|---|
| `GoblinIntegrationTest` | Endpoint basics, each assault type (latency, exception, HTTP status, dependency degradation, response body truncate/inflate, response header set/remove), target-level percentage behavior |
| `GoblinClientAssaultIntegrationTest` | Client-side latency and exception on outgoing REST Client calls (incl. interplay with the target level and isolation from incoming-request assaults) |
| `GoblinWebClientIntegrationTest` | Client-side latency and exception on outgoing Vert.x WebClient calls, against the same 8081 test-port endpoint: on a client armed with `GoblinWebClient.enable(...)` (created reflectively, out of the build-time rewrite), and on the core and Mutiny clients `SampleResource` creates without any Goblin call (`/api/web-proxy`, `/api/mutiny-web-proxy`) |
| `GoblinMetricsIntegrationTest` | The optional `quarkus-goblin-metrics` module end-to-end: meters registered with the expected names/tags on the live Prometheus registry (server, REST Client and WebClient sources), settled-step counter/timer values, and the `goblin.active` gauge |
| `GoblinTracingIntegrationTest` | The optional `quarkus-goblin-opentelemetry` module end-to-end: a `goblin.assault` span per assault (server latency, HTTP status, REST Client / WebClient latency, client exception) with the `goblin.assault.*` attributes, INTERNAL kind and parent linkage to the request span, captured through the CDI `SpanExporter` bean (`InMemoryTraceSpanExporter`) |
| `GoblinJsonRPCServiceTest` | The Dev UI JSON-RPC contract (status, toggles, editors, history, Markdown report, response body and response header config self-service) |
| `GoblinServiceLayerIntegrationTest` | The `SERVICE` layer: latency and exception injected on the bean, inside Fault Tolerance (`@Retry`, `@Fallback`, `@Timeout` observe the faults), inert when not armed |
| `GoblinDatabaseLayerIntegrationTest` | The `DATABASE` layer: JDBC connection acquisition failed or delayed by the Agroal interceptor, `@Retry` then `@Fallback` answering, no assault outside an armed request |
| `GoblinMessagingLayerIntegrationTest` | The `MESSAGING` layer: a consumed message faulted at the `@Incoming` consumer or deeper, and no stale HTTP decision leaking into a consumer |
| `GoblinReactiveRoutesIntegrationTest` | The `HTTP_IN` layer on reactive routes: latency (event loop and blocking), exception answered by the failure handler, HTTP status (text and JSON), dependency degradation, response body truncate/inflate (text and JSON, after a delay too), response header set/remove (on aborts too), aborts after the delay, request body and request context kept across the delay, `@RouteFilter` never assaulted, a pass-through route assaulting a REST request once and passing its decision (SERVICE layer, response body) to the resource behind it, a 0 ms latency draw, abort bodies rewritten, fluent chains, `RoutingContext` default methods, `Uni` and `Multi` routes, HEAD requests, the SERVICE layer below a route, REST requests assaulted once, inert when chaos is off, HTTP_IN not armed or level 0 |
| `ReactiveRouteEventLoopIntegrationTest` | With a single event loop, concurrent delayed reactive-route requests overlap: the latency never blocks the loop |
| `SparedPassThroughRouteTargetingTest` | `exclude-annotations` spares a pass-through `@Route` only: the REST resource behind it (`/api/spared/hello`) takes the HTTP_IN draw |
| `TestModeWithoutOptInTest` | The test-mode default: without `quarkus.goblin.test.enabled`, a `@QuarkusTest` suite is never assaulted |
| `AbstractPackageTargetingTest` + `ExcludePackageTargetingTest`, `IncludeNonMatchingPackageTargetingTest`, `IncludeMatchingPackageTargetingTest`, `ExcludeOverridesIncludeTargetingTest` | Package-based targeting via `include-packages` / `exclude-packages` |

`GoblinClientAssaultIntegrationTest` needs `quarkus-rest-client` (declared in this module's `pom.xml`) and the
`sample-client` URL pointing at the same app that the `@QuarkusTest` boots (`http://localhost:8081`).
`GoblinWebClientIntegrationTest` needs `vertx-core` + `vertx-web-client` (declared in this module's `pom.xml`) for
its real `Vertx` `WebClient`, and targets the same test-port URL.

## The targeting-test pattern

The package and annotation filters come from `GoblinTargetingConfig`, which is fixed at build time (it also drives the
weaving of the service and messaging interceptors), so targeting is exercised with a dedicated `@QuarkusTest` class per
scenario, each declaring its own `@TestProfile` that sets the
`quarkus.goblin.target.*` properties. A shared fixture (`AbstractPackageTargetingTest`) resets the engine on every
test: it enables the HTTP status assault with 503 at 100% level so that a request which passes the filters is
observably short-circuited, and spares requests that do not.

Everything else (assault toggles, parameters, level) is mutable at runtime and is configured through the injected
`AssaultEngine` / `MutableAssaultConfig` inside each `@BeforeEach`.

## Running

```bash
mvn -pl integration-tests test            # only this module (after a first install)
mvn clean install -Dno-format             # full build (unit + integration)
```

The JaCoCo **aggregate** coverage report for the whole project is generated in this module
(`target/site/jacoco-aggregate/`) because it depends on every module (`runtime`, `deployment`, `metrics` and
`opentelemetry`); the CI publishes the derived
percentage to the `badges` branch (see [README.md](../README.md#coverage)).

## Adding a test for a new assault

1. Enable the assault in `@BeforeEach` via the mutable config, keep the other toggles off, and assert the observable
   HTTP effect with RestAssured.
2. If the assault reads static config, follow the targeting pattern: a new `@QuarkusTest` class with a dedicated
   `@TestProfile`.
3. Always reset the engine state and clear the history at the start of each test so scenarios stay independent.