# Security Policy

## Supported versions

ccx is pre-1.0. Security fixes are applied to the latest released version only.

| Version | Supported          |
|---------|--------------------|
| 0.5.x   | :white_check_mark: |
| < 0.5   | :x:                |

## Reporting a vulnerability

Please report security issues privately — do **not** open a public GitHub issue
for a suspected vulnerability.

Preferred channel: open a private advisory via
[GitHub Security Advisories](https://github.com/aneveux/ccx/security/advisories/new)
on this repository.

If you cannot use Security Advisories, contact the maintainer directly through
their GitHub profile: [@aneveux](https://github.com/aneveux).

When reporting, please include:

- A description of the issue and its impact.
- Steps to reproduce, or a proof-of-concept.
- The ccx version and platform.
- Any suggested remediation, if you have one.

## Disclosure process

We follow responsible (coordinated) disclosure:

1. Acknowledge the report within a reasonable timeframe.
2. Investigate and confirm the issue.
3. Develop and test a fix.
4. Release the fix and credit the reporter (unless anonymity is requested).
5. Publish a public advisory after users have had a chance to update.

Please give us a reasonable window to address the issue before any public
disclosure.

## Scope notes

ccx reads and writes Claude Code configuration files and performs path-security
boundary checks before any file operation (it refuses to write outside the
project or the user's ccx configuration directories). Reports that demonstrate a
bypass of these path-security boundaries — for example, writing outside the
intended directories via crafted profile names, fragment names, or `--project-dir`
/ `--output` arguments — are especially welcome.

### File permissions

Two of the files ccx writes can carry credentials: `.claude/settings.json`
round-trips MCP `env` and `headers` blocks, and `.ccx/context.json` records
profile `env` values. ccx creates both mode `600`. Every other file it writes
takes the process umask default.

ccx never changes the mode of a file that already exists — narrowing a file that
someone widened on purpose would break a shared-group or container setup
silently. That means a file created by ccx 0.5.0 or earlier, by Claude Code, or
by a git checkout keeps its original mode (commonly `664` under `umask 002`).
`ccx doctor` reports such a file with the `chmod` to run; it does not fail the
check, so upgrading ccx cannot break an existing pipeline.

### Pinned dependencies

`org.jline:jline` is frozen at `3.25.1` in `dependencyManagement`. Versions
`3.30.16`, `4.2.1` and `4.3.1` all break the GraalVM native build (an FFM
`linkToNative` failure at image-build time), so the pin is what makes a native
release possible at all.

The consequence is that the matching `ignore` entry in
[`.github/dependabot.yml`](.github/dependabot.yml) suppresses Dependabot pull
requests for jline *security* updates as well as version updates — GitHub's
`ignore` applies to both. Remediating a jline vulnerability is therefore a manual
job: unpin, verify `mvn -Dnative package` still builds, and release. The
compensating control is that the pin must be re-tested at every ccx release, and
jline advisories checked by hand for as long as it stands.
