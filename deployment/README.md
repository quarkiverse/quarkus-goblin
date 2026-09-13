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
- `activateChaos(...)` -- a `@Record(RUNTIME_INIT)` build step that records the config into the runtime
  `GoblinRecorder`, but **only when the launch mode is `DEVELOPMENT` or `TEST`**. This is what physically keeps
  Goblin out of production.

## Dev UI wiring (`GoblinDevUIProcessor`)

- `registerJsonRPCService()` -- exposes
  [`GoblinJsonRPCService`](../runtime-dev/src/main/java/io/quarkiverse/goblin/dev/GoblinJsonRPCService.java) (from the
  `runtime-dev` module) to the Dev UI front end.
- `createCardPages(...)` -- `@BuildStep(onlyIf = IsLocalDevelopment.class)` builds the Dev UI card with the dark/light
  logo and two web-component pages:
  - **Chaos Dashboard** (`qwc-goblin-dashboard.js`),
  - **History** (`qwc-goblin-history.js`).

## Static Dev UI resources

The Lit web components and logos live in `src/main/resources/dev-ui/`:

- `qwc-goblin-dashboard.js` -- master toggle, assault toggles, per-type parameter editors, target level;
- `qwc-goblin-history.js` -- real-time assault history with the exported Markdown report panel;
- `goblin-dark.svg` / `goblin-light.svg` -- extension card logos.

`META-INF/quarkus-build-steps.list` is generated during the build and lists the build-step classes.

## Adding a new assault

After implementing the assault bean in `runtime` (see [runtime/README.md](../runtime/README.md)), `deployment`
requires **no changes**: `registerAssaultBeans` picks the new implementation up via Jandex, and the Dev UI panels are
added in `runtime-dev` + `qwc-goblin-dashboard.js` (see [runtime-dev/README.md](../runtime-dev/README.md)).

## Conventions

- Keep `GoblinBuildStep` free of Dev UI logic (it lives in the processor) and vice versa.
- Rely on the build index (`CombinedIndexBuildItem`) rather than hard-coding bean class names.
- Any new runtime behavior must remain gated behind the dev/test launch-mode check in `activateChaos`.