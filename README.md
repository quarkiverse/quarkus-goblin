# quarkus-goblin

[![Java](https://img.shields.io/badge/Java-25+-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.38+-4695EB?logo=quarkus&logoColor=white)](https://quarkus.io/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Status](https://img.shields.io/badge/status-experimental-orange)]()

Chaos engineering extension for Quarkus -- inject latency, exceptions, HTTP failures, and dependency degradation into your running application without touching a single line of source code.

## Why?

Quarkus has excellent resilience primitives (MicroProfile Fault Tolerance, Mutiny reactive timeouts, health probes), but no built-in way to **trigger** the failures these mechanisms are supposed to handle. Goblin fills that gap.

## Features

- **Latency injection** -- Artificial delay before processing (configurable min/max ms)
- **Exception injection** -- Throw configurable exceptions before method execution
- **HTTP status forcing** -- Return specific HTTP status codes (503, 500, etc.)
- **Dependency degradation** -- Simulate downstream service failures
- **Multiple types simultaneously** -- Enable latency + exception together for slow failure simulation
- **Targeting** -- By package, by annotation, by percentage of requests
- **Dev UI** -- Toggle assaults, edit config, view history -- all in real time
- **Markdown report export** -- Generate a factual report of config + assault history, ready to hand to an LLM for resilience review
- **Dev mode only** -- Chaos artifacts are physically absent from production builds

## Quick start

Add the dependency:

```xml
<dependency>
    <groupId>io.quarkiverse.goblin</groupId>
    <artifactId>quarkus-goblin</artifactId>
    <version>${goblin.version}</version>
</dependency>
```

Start in dev mode:

```bash
./mvnw quarkus:dev
```

Open the Dev UI at `http://localhost:8080/q/dev` and look for the Goblin card.

## Configuration

```properties
# Enable/disable (default: true, only active in dev mode)
quarkus.goblin.enabled=true

# Assault type enabled at startup (can be changed at runtime via Dev UI)
quarkus.goblin.assault.type=LATENCY

# Latency settings
quarkus.goblin.assault.latency.min-milliseconds=100
quarkus.goblin.assault.latency.max-milliseconds=5000

# Exception settings
quarkus.goblin.assault.exception.type=java.lang.RuntimeException
quarkus.goblin.assault.exception.message=Goblin chaos: simulated exception

# HTTP status settings
quarkus.goblin.assault.http-status.code=503
quarkus.goblin.assault.http-status.message=Service Unavailable (Goblin chaos)

# Target level (0-100% of requests affected)
quarkus.goblin.target.level=100

# Optional: include/exclude packages
# quarkus.goblin.target.include-packages=com.example.api
# quarkus.goblin.target.exclude-packages=com.example.health

# Optional: exclude annotated methods
# quarkus.goblin.target.exclude-annotations=org.eclipse.microprofile.faulttolerance.Timeout
```

## Dev UI

The Chaos Dashboard provides:

- **Master toggle** -- Activate/deactivate all chaos
- **Assault type toggles** -- Independent on/off for Latency, Exception, HTTP Status, Dependency Degradation
- **Config sections** -- Edit parameters per type (disabled with placeholders when type is off)
- **Target level** -- Adjust percentage of affected requests
- **History** -- Real-time log of every assault triggered (with the applied latency duration)
- **Markdown report** -- "Export Markdown" button in the History panel generates a factual report of the current configuration and assault history, handy for pasting into an LLM assistant (e.g. Claude) for a resilience review

All changes apply instantly with WARN logs in the console.

## Safety

- **Dev mode only** -- Chaos only exists in `quarkus:dev`. Physically absent in production.
- **Zero code modification** -- No annotations needed. Fully automatic instrumentation.
- **Explicit logging** -- WARN log emitted when chaos is active.

## Documentation

The full AsciiDoc guide lives in [`docs/src/main/asciidoc/`](docs/src/main/asciidoc/) (`index.adoc`), covering assault types, targeting, the Dev UI (with screenshots), an end-to-end example, a JSON-RPC reference, and a FAQ.

Screenshots of the Dev UI are stored in [`docs/src/main/asciidoc/assets/`](docs/src/main/asciidoc/assets/). See the `README.md` there for how to (re)capture them.

## Requirements

- Java 25+
- Quarkus 3.38+

## License

Apache License 2.0
