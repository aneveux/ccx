# Security Policy

## Supported versions

ccx is pre-1.0. Security fixes are applied to the latest released version only.

| Version | Supported          |
|---------|--------------------|
| 0.4.x   | :white_check_mark: |
| < 0.4   | :x:                |

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
