# Goblin Integration Tests

End-to-end `@QuarkusTest` suite exercising the extension through a real JAX-RS application
(`io.quarkiverse.goblin.it.SampleResource`) with the endpoints `/api/hello`, `/api/slow` and `/api/unstable`.
`src/main/resources/application.properties` enables Goblin with latency defaults so the engine is active at boot.

## Test classes

| Test | Coverage |
|---|---|
| `GoblinIntegrationTest` | Endpoint basics, each assault type (latency, exception, HTTP status, dependency degradation), target-level percentage behavior |
| `GoblinJsonRPCServiceTest` | The Dev UI JSON-RPC contract (status, toggles, editors, history, Markdown report) |
| `AbstractPackageTargetingTest` + `ExcludePackageTargetingTest`, `IncludeNonMatchingPackageTargetingTest`, `IncludeMatchingPackageTargetingTest`, `ExcludeOverridesIncludeTargetingTest` | Package-based targeting via `include-packages` / `exclude-packages` |

## The targeting-test pattern

`GoblinChaosFilter.isTargetEligible()` reads the package filters from the **static** `GoblinConfig`, so targeting is
exercised with a dedicated `@QuarkusTest` class per scenario, each declaring its own `@TestProfile` that sets the
`quarkus.goblin.target.*` properties. A shared fixture (`AbstractPackageTargetingTest`) resets the engine on every
test: it enables the HTTP status assault with 503 at 100% level so that a request which passes the filters is
observably short-circuited, and spares requests that do not.

Everything else (assault toggles, parameters, level) is mutable at runtime and is configured through the injected
`AssaultEngine` / `MutableAssaultConfig` inside each `@BeforeEach`.

## Running

```bash
./mvnw -pl integration-tests test          # only this module
./mvnw clean install -Dno-format           # full build (unit + integration)
```

The JaCoCo **aggregate** coverage report for the whole project is generated in this module
(`target/site/jacoco-aggregate/`) because it depends on both `runtime` and `deployment`; the CI publishes the derived
percentage to the `badges` branch (see [README.md](../README.md#coverage)).

## Adding a test for a new assault

1. Enable the assault in `@BeforeEach` via the mutable config, keep the other toggles off, and assert the observable
   HTTP effect with RestAssured.
2. If the assault reads static config, follow the targeting pattern: a new `@QuarkusTest` class with a dedicated
   `@TestProfile`.
3. Always reset the engine state and clear the history at the start of each test so scenarios stay independent.