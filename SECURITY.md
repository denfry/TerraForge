# Security policy

## Supported versions

TerraForge is pre-1.0 software. Security fixes are released for the latest published version only.
Older previews should be upgraded before requesting a backport.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | No |
| Unreleased forks | No |

## Reporting a vulnerability

Do **not** open a public issue or discussion for a suspected vulnerability.

Use GitHub's private vulnerability reporting page:

<https://github.com/denfry/TerraForge/security/advisories/new>

If private reporting is unavailable, contact the maintainer through the address published on the
[`denfry` GitHub profile](https://github.com/denfry) and include only enough information to arrange
a private channel.

Please include:

- affected TerraForge version and Paper build;
- impact and realistic attack scenario;
- reproduction steps or a minimal proof of concept;
- relevant configuration with all credentials and private data removed;
- any suggested mitigation.

You can expect an acknowledgement when the report is reviewed. Please allow time for validation,
coordination and a patched release before public disclosure.

## Scope

High-value reports include path traversal, unsafe archive or geodata parsing, SQL injection,
permission bypass, server-thread denial of service, malicious configuration handling, dependency
compromise and leakage of sensitive server data.

TerraForge does not collect telemetry and does not require network access at runtime. Server
operators remain responsible for the provenance and licensing of the datasets they prepare.
