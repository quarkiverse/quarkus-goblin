# Goblin Documentation

Antora sources for the user-facing Goblin guide. The rendered documentation is published to
`docs.quarkiverse.io/quarkus-goblin/` as part of the Quarkiverse release pipeline.

## Layout

- `antora.yml` -- component descriptor (`name: quarkus-goblin`, `title: Goblin`, `version: dev`).
- `modules/ROOT/nav.adoc` -- navigation tree.
- `modules/ROOT/pages/index.adoc` -- the full guide (assault types, targeting, configuration validation, Dev UI with
  screenshots, end-to-end example, JSON-RPC reference, FAQ).
- `modules/ROOT/partials/attributes.adoc` -- shared AsciiDoc attributes.
- `modules/ROOT/assets/images/` -- Dev UI screenshots and the extension logo.

The configuration reference is generated at build time by `quarkus-config-doc-maven-plugin` from the
`@ConfigRoot`/`@ConfigMapping` Javadoc in `runtime`, then processed by `asciidoctor-maven-plugin`.

## Conventions

- Keep the guide aligned with [runtime/README.md](../runtime/README.md): the project page documents the user-facing
  behavior, the runtime README documents the SPI for contributors.
- Version-specific details (e.g. "latest release version" in the docs) are updated automatically during the Quarkiverse
  release, driven by `docs/pom.xml` and the `Prepare Release` workflow (see
  [deployment of the release](../.github/workflows/release-prepare.yml)).
- New screenshots go in `modules/ROOT/assets/images/` (prefer PNG, keep them reasonably sized).