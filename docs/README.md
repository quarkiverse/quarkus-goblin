# Goblin Documentation

Antora sources for the user-facing Goblin guide. The rendered documentation is published to
`docs.quarkiverse.io/quarkus-goblin/` as part of the Quarkiverse release pipeline.

## Layout

- `antora.yml` -- component descriptor (`name: quarkus-goblin`, `title: Goblin`); the published version comes from the
  branch (`main` is published as `dev`, a maintenance branch such as `0.3.x` under its own name).
- `modules/ROOT/nav.adoc` -- navigation tree.
- `modules/ROOT/pages/*.adoc` -- the guide, one page per topic (`index.adoc` is the entry point and FAQ; see
  `nav.adoc` for the order).
- `modules/ROOT/partials/attributes.adoc` -- shared AsciiDoc attributes.
- `modules/ROOT/assets/images/` -- Dev UI screenshots and the extension logo.

`pages/configuration-reference.adoc` is written by hand: keep it in sync with the `@ConfigMapping` interfaces of
`runtime` (`GoblinConfig`, `GoblinTargetingConfig`). The build also runs `quarkus-config-doc-maven-plugin`, whose
generated output lands in `target/` and is not included in the published pages.

## Conventions

- Keep the guide aligned with [runtime/README.md](../runtime/README.md): the project page documents the user-facing
  behavior, the runtime README documents the SPI for contributors.
- Version-specific details (e.g. "latest release version" in the docs) are updated automatically during the Quarkiverse
  release, driven by `docs/pom.xml` and the `Prepare Release` workflow (see
  [deployment of the release](../.github/workflows/release-prepare.yml)).
- New screenshots go in `modules/ROOT/assets/images/` (prefer PNG, keep them reasonably sized).