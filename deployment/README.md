# Goblin Deployment -- build steps and Dev UI wiring

The deployment module turns the runtime into a real Quarkus extension: it registers the feature, wires the runtime
beans, controls when chaos is active, and connects the Dev UI. It is only present at build time and never lands in a
user application.

## Build steps (`GoblinBuildStep`)

- `feature()` -- registers the `goblin` feature so `quarkus.extension.features` lists it and the extension shows up
  in the extension catalog.
- `registerAssaultBeans(CombinedIndexBuildItem, ...)` -- scans the index for all implementations of
  `io.quarkiverse.goblin.assault.Assault` using `getAllKnownImplementations(...)` and registers each as an
  **unremovable** Arc bean. New assaults are picked up automatically; no manual registration.
- `registerClientFilterBean(...)` -- registers `GoblinChaosClientFilter` so the REST Client applies it to every
  outgoing call.
- `registerServiceInterceptor(...)` -- registers `GoblinServiceInterceptor` and weaves the `@GoblinServiceAssault`
  binding onto the eligible methods of the application beans (root archive only, `quarkus.goblin.target.*` rules
  through the shared `TargetRules`).

## Optional layer hooks (`GoblinLayerHooksProcessor`)

- `registerDatabaseHook(...)` -- when `quarkus-agroal` is present, one `GoblinAgroalPoolInterceptor` synthetic bean
  per JDBC datasource (`@Default` or `@DataSource("name")`).
- `registerMessagingHook(...)` -- when `quarkus-messaging` is present, registers `GoblinMessagingInterceptor` and
  weaves `@GoblinMessagingAssault` onto the eligible `@Incoming` methods.
- `declareOptionalHooks(...)` -- exposes the installed optional layers as the `GoblinLayerHooks` synthetic bean read by
  `AssaultEngine`.

## Keeping chaos out of production

Two independent guards:

- every chaos build step above (and the Dev UI JSON-RPC registration) is annotated
  `@BuildStep(onlyIfNot = IsProduction.class)`: a production build carries no interceptor binding, no database hook
  and no JSON-RPC service;
- at runtime, `AssaultEngine.initialize(LaunchMode)` leaves the engine inactive and without configuration in any launch
  mode other than `DEVELOPMENT` and `TEST`; in `TEST` it loads its configuration but stays inactive unless
  `quarkus.goblin.test.enabled=true`.

The runtime jar is Jandex-indexed, so the runtime beans themselves (engine, assault beans, JAX-RS filters) are still
discovered in a production build; they stay inert because the engine never activates there.

## Dev UI wiring (`GoblinDevUIProcessor`)

- `registerJsonRPCService()` -- `@BuildStep(onlyIfNot = IsProduction.class)`, exposes
  [`GoblinJsonRPCService`](../runtime-dev/src/main/java/io/quarkiverse/goblin/dev/GoblinJsonRPCService.java) (from the
  `runtime-dev` module) to the Dev UI front end.
- `createCardPages(...)` -- `@BuildStep(onlyIf = IsLocalDevelopment.class)` builds the Dev UI card with the dark/light
  logo and two web-component pages:
  - **Chaos Dashboard** (`qwc-goblin-dashboard.js`),
  - **History** (`qwc-goblin-history.js`).

## Static Dev UI resources

The Lit web components and logos live in `src/main/resources/dev-ui/`:

- `qwc-goblin-dashboard.js` -- master toggle, assault toggles, per-type parameter editors, target level;
- `qwc-goblin-history.js` -- live assault history console (auto-refresh, filters, summary band, expandable Active Config cells) with the exported Markdown report panel;
- `goblin-dark.svg` / `goblin-light.svg` -- extension card logos.

The build-step classes are listed in `META-INF/quarkus-build-steps.list` by the Quarkus extension annotation processor
during the build (the committed file is merged with the classes it detects).

## Adding a new assault

After implementing the assault bean in `runtime` (see [runtime/README.md](../runtime/README.md)), `deployment`
requires **no changes**: `registerAssaultBeans` picks the new implementation up via Jandex, and the Dev UI panels are
added in `runtime-dev` + `qwc-goblin-dashboard.js` (see [runtime-dev/README.md](../runtime-dev/README.md)).

## Conventions

- Keep `GoblinBuildStep` free of Dev UI logic (it lives in the processor) and vice versa.
- Rely on the build index (`CombinedIndexBuildItem`) rather than hard-coding bean class names.
- Any new chaos wiring must be a `@BuildStep(onlyIfNot = IsProduction.class)` step, and any new runtime behavior must
  stay behind the dev/test launch-mode check of `AssaultEngine.initialize`.