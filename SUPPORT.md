# TerraForge support

TerraForge is community-maintained preview software. Public support is provided through the
[wiki](https://github.com/denfry/TerraForge/wiki), [Discussions](https://github.com/denfry/TerraForge/discussions)
and GitHub issues; there is no guaranteed response time.

## Before opening an issue

1. Confirm you are using the Paper, Java and TerraForge versions listed in the README.
2. Read the [Installation](https://github.com/denfry/TerraForge/wiki/Installation) and
   [Troubleshooting](https://github.com/denfry/TerraForge/wiki/Troubleshooting) wiki pages, and the
   [FAQ](https://github.com/denfry/TerraForge/wiki/FAQ).
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
- General question, "how do I...", or showing off a world: use
  [Discussions](https://github.com/denfry/TerraForge/discussions) instead of an issue.
- Security vulnerability: use the private process in [SECURITY.md](SECURITY.md).
- General Paper, Towny, BlueMap or dataset licensing questions: use the upstream project's support
  channel when the issue is not specific to TerraForge.

Include the exact TerraForge version, Paper build, Java version, operating system, relevant config
with secrets removed, steps to reproduce, and the smallest useful log excerpt.
