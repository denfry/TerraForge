# TerraForge support

TerraForge is community-maintained preview software. Public support is provided through GitHub
issues; there is no guaranteed response time.

## Before opening an issue

1. Confirm you are using the Paper, Java and TerraForge versions listed in the README.
2. Read the [installation guide](docs/installation.md) and
   [troubleshooting guide](docs/troubleshooting.md).
3. Run the CLI validator against prepared data:

   ```bash
   java -jar terraforge-cli-<version>.jar validate \
       --database ./server/plugins/TerraForge/terraforge.db \
       --strict
   ```

4. Reproduce the problem on a clean Paper server with only TerraForge and required integrations.
5. Remove passwords, tokens, player IP addresses and private filesystem paths from logs.

## Where to ask

- Reproducible defect: open a bug report.
- Proposed behavior: open a feature request.
- Security vulnerability: use the private process in [SECURITY.md](SECURITY.md).
- General Paper, Towny, BlueMap or dataset licensing questions: use the upstream project's support
  channel when the issue is not specific to TerraForge.

Include the exact TerraForge version, Paper build, Java version, operating system, relevant config
with secrets removed, steps to reproduce, and the smallest useful log excerpt.
