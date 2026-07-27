# Contributing to TerraForge

TerraForge welcomes focused fixes, tests, documentation and carefully scoped features that preserve
its natural-geography-only contract.

## Before you start

- Search existing issues and pull requests before opening a duplicate.
- Use a feature request for user-facing changes or a bug report for reproducible defects.
- For large architectural changes, open an issue before investing in an implementation.
- Do not include downloaded geodata, generated worlds, server logs with private data, or credentials.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Development setup

You need JDK 21 and Git. Use the checked-in Gradle wrapper:

```bash
git clone https://github.com/denfry/TerraForge.git
cd TerraForge
./gradlew build
```

Windows PowerShell users can run `.\gradlew.bat build`.

Platform and dependency versions belong in `gradle.properties`; do not duplicate them in source or
module build scripts. Read [`docs/development.md`](docs/development.md) before changing module
boundaries, terrain generation or data formats.

## Project rules

- Preserve deterministic output: identical input and configuration must produce identical terrain.
- Keep Paper, Towny and BlueMap types out of `terraforge-core` and `terraforge-geo`.
- Never add roads, buildings, railways, bridges, airports, power lines or other man-made generation.
- Keep blocking file, database and network work off the Paper server thread.
- Treat source data, config values and paths as untrusted; validate before use and fail closed.
- Add or update tests for every behavioral change.
- Document user-visible configuration, commands and compatibility changes.

## Pull request checklist

1. Create a focused branch from `main`.
2. Make the smallest coherent change.
3. Run:

   ```bash
   ./gradlew build
   ```

4. Update `CHANGELOG.md` under `Unreleased` for user-visible changes.
5. Update documentation and default configuration when behavior changes.
6. Open a pull request using the repository template.

Pull requests should explain the problem, the chosen design, verification performed and any effect
on existing worlds or prepared-data formats. Screenshots are useful for BlueMap or terrain changes;
small synthetic fixtures are preferred over real datasets in tests.

## Commit style

Use short, imperative subjects. Conventional Commit prefixes are welcome:

```text
feat(generator): add alpine snow transition
fix(cli): reject paths outside the output directory
docs: clarify regional data preparation
```

Do not commit build output, IDE metadata, server runtime directories or geodata. The repository's
`.gitignore` intentionally blocks common raster, vector and prepared-data formats.

## Reporting security issues

Do not disclose vulnerabilities in public issues. Follow [`SECURITY.md`](SECURITY.md) instead.
