# Releasing TerraForge

TerraForge releases are immutable, tag-driven GitHub releases containing the Paper plugin, offline
CLI and SHA-256 checksums. The same tag publishes the Paper plugin to Modrinth.

## One-time Modrinth setup

1. Create the TerraForge project on Modrinth and submit its project page for review.
2. Create a Modrinth personal access token with the `CREATE_VERSION` scope.
3. Add these GitHub Actions repository secrets:
   - `MODRINTH_TOKEN` — the personal access token; never commit it.
   - `MODRINTH_PROJECT_ID` — the immutable Modrinth project ID.

The release workflow uploads only `TerraForge-<version>.jar`. The CLI remains attached to the
GitHub release because it is an operator tool, not a server plugin.

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
   extracts release notes from `CHANGELOG.md`, publishes the plugin to Modrinth, creates checksums
   and publishes the GitHub release artifacts.
9. Download the published jars and verify their checksums.
10. Smoke-test the release on a clean Paper server before announcing it.

Never move or replace a published tag. If a release artifact is wrong, fix the source and publish a
new patch release.
