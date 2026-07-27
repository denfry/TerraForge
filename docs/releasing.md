# Releasing TerraForge

TerraForge releases are immutable, tag-driven GitHub releases containing the Paper plugin, offline
CLI and SHA-256 checksums.

## Versioning

TerraForge follows Semantic Versioning. While the major version is `0`, a minor release may change
world generation or the prepared-data format. Such changes must be called out explicitly in the
changelog.

- Patch: compatible bug fixes and documentation.
- Minor: features or pre-1.0 compatibility changes.
- Major: stable breaking API, configuration or data-format changes.
- Pre-release: `0.2.0-beta.1`, `0.2.0-rc.1`.

The Git tag is `v<version>` and must exactly match `version` in `gradle.properties`.

## Release checklist

1. Ensure `main` is green and the working tree is clean.
2. Update `version` in `gradle.properties`.
3. Move relevant entries from `Unreleased` to `## [<version>] - YYYY-MM-DD`.
4. State whether the release changes world generation, configuration or prepared-data formats.
5. Run:

   ```bash
   ./gradlew build
   ```

6. Commit the release metadata.
7. Create an annotated tag:

   ```bash
   git tag -a v0.2.0 -m "TerraForge 0.2.0"
   ```

8. Push the commit and tag. The release workflow validates the version, rebuilds from the tag,
   extracts release notes from `CHANGELOG.md`, creates checksums and publishes the artifacts.
9. Download the published jars and verify their checksums.
10. Smoke-test the release on a clean Paper server before announcing it.

Never move or replace a published tag. If a release artifact is wrong, fix the source and publish a
new patch release.
