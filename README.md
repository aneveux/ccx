# ccx

[![CI](https://github.com/aneveux/ccx/actions/workflows/ci.yml/badge.svg)](https://github.com/aneveux/ccx/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/aneveux/ccx)](https://github.com/aneveux/ccx/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

CLI for managing fine-grained Claude Code configuration per project.
Handles `CLAUDE.md` composition, plugin enablement, MCP servers, skill overrides, and rules — without manually juggling files.

<!-- TUI screencast placeholder - asset must be recorded before launch -->
<!-- ![ccx TUI](docs/media/tui.gif) -->
<!-- Recording instructions: docs/media/README.md -->

## Install

**Linux x86_64 — one line (no sudo required):**
```bash
curl -fsSL https://raw.githubusercontent.com/aneveux/ccx/master/install.sh | bash
```

The installer drops the `ccx` binary into `~/.local/bin` by default. Make sure that
directory is on your `$PATH`:

```bash
export PATH="$HOME/.local/bin:$PATH"   # add to ~/.bashrc or ~/.zshrc
```

Override the install directory with `CCX_INSTALL_DIR`:

```bash
CCX_INSTALL_DIR=/usr/local/bin curl -fsSL https://raw.githubusercontent.com/aneveux/ccx/master/install.sh | bash
```

The installer downloads the latest Linux x86_64 binary, **verifies its SHA-256
checksum before installing (fail-closed — it aborts on a missing or mismatched
checksum)**, then moves the binary into the install directory. Set
`CCX_SKIP_CHECKSUM=1` only if you understand the risk.

Or download a binary directly from [Releases](https://github.com/aneveux/ccx/releases/latest).

## Quick start

ccx ships no profiles in the binary — author your own with:

```bash
ccx profiles new my-profile
ccx profiles enable my-profile
```

## Build from source

**Prerequisites:** Java 25, Maven 3.9+

**JVM (development):**
```bash
mvn verify
java -jar target/quarkus-app/quarkus-run.jar --help
```

**Native binary (requires GraalVM 25 with `native-image`):**
```bash
mvn package -Pnative
./target/ccx --help
```

## Commands

| Command      | Description                                        |
|--------------|----------------------------------------------------|
| `clean`      | Remove ccx-managed config from project             |
| `doctor`     | Validate project configuration                     |
| `status`     | Show current configuration summary                 |
| `plugins`    | Enable/disable/list plugins                        |
| `profiles`   | Manage profiles                                    |
| `skills`     | Manage skill overrides                             |
| `mcp`        | Configure MCP servers                              |
| `claude-md`  | Manage CLAUDE.md fragment references               |
| `config`     | Manage ccx configuration (global fragments)        |
| `tui`        | Interactive terminal dashboard                     |

**Global options.** Most commands accept `--project-dir` (the project to act on,
defaults to cwd). Read-only commands that resolve global config also accept
`--global-dir` (the Claude config dir, defaults to `~/.claude`): `plugins list`,
`plugins diff`, `plugins tags`, `plugins refresh`, `skills list`, `skills show`.
`doctor` also accepts `--global-dir`, but defaults it to your home directory
(it resolves `.claude/settings.json` underneath).

`doctor` additionally probes for `git`, `jq`, and `yq` on your `PATH` — a missing
`git` is reported as `[WARNING]`, missing `jq`/`yq` as `[INFO]`.

### Profiles subcommands

| Subcommand              | Description                                      |
|-------------------------|--------------------------------------------------|
| `profiles list`         | List all available profiles                      |
| `profiles show <name>`  | Show profile details (plugins, rules, env, etc.) |
| `profiles enable <name>`| Apply a profile to this project                  |
| `profiles disable <name>`| Remove a profile from this project              |
| `profiles reinstall <name>`| Re-apply a profile to its declared state      |
| `profiles status`       | Show drift state of all profiles                 |
| `profiles edit <name>`  | Edit a profile's `profile.yaml` in `$EDITOR`     |
| `profiles new <name>`   | Scaffold a new profile directory                 |

### Plugins subcommands

| Subcommand              | Description                                      |
|-------------------------|--------------------------------------------------|
| `plugins list`          | List all known plugins with status (`--tag` to filter) |
| `plugins enable <name>` | Enable a plugin in this project                  |
| `plugins disable <name>`| Disable a plugin                                 |
| `plugins diff`          | Show diff between project and global plugins     |
| `plugins tags`          | List available tag filters for `plugins list`    |
| `plugins refresh`       | Force re-fetch GitHub marketplace sources (`--marketplace`/`-m` to target one) |

### Skills subcommands

| Subcommand              | Description                                          |
|-------------------------|------------------------------------------------------|
| `skills list`           | List all discovered skills with their status         |
| `skills enable <id>`    | Enable a skill at auto (remove its override)         |
| `skills disable <id>`   | Disable a skill (off override)                       |
| `skills hide <id>`      | Set a skill to user-invocable-only                   |
| `skills show <id>`      | Show the current override status of a skill          |
| `skills reset`          | Remove all skill overrides from project settings     |

### MCP subcommands

| Subcommand              | Description                                      |
|-------------------------|--------------------------------------------------|
| `mcp list`              | List configured MCP servers                      |
| `mcp enable <name>`     | Add or update an MCP server in `settings.json`   |
| `mcp disable <name>`    | Remove an MCP server from `settings.json`        |
| `mcp show <name>`       | Show MCP server configuration details            |

`mcp enable` adds a server or updates one that already exists. Describe the server
with flags:

```bash
# command server (--type defaults to "command")
ccx mcp enable filesystem --command mcp-server-fs --args --root --args .

# HTTP server
ccx mcp enable remote-api --type http --url https://mcp.example.com/sse
```

`--args` is repeatable — each occurrence adds one argument. The `--command`,
`--url`, `--args`, and `--type` flags map to the `command`/`url`/`args`/`type`
fields of the server entry.

Power-user path: pass the full config as a JSON positional instead of flags.

```bash
ccx mcp enable filesystem '{"command": "mcp-server-fs", "args": ["--root", "."]}'
ccx mcp enable remote-api '{"type": "http", "url": "https://mcp.example.com/sse"}'
```

### claude-md subcommands

Manage references to global CLAUDE.md fragments inside `.claude/CLAUDE.md`.

| Subcommand                  | Description                                          |
|-----------------------------|------------------------------------------------------|
| `claude-md list`            | List configured fragments in the ccx marker block    |
| `claude-md add <name>`      | Add a global fragment reference to `.claude/CLAUDE.md`|
| `claude-md remove <name>`   | Remove a global fragment reference                   |
| `claude-md init`            | Initialize `.claude/CLAUDE.md` with an empty marker block |

A fragment named `<name>` must already exist at `~/.config/ccx/claudemd/<name>.md`
(create one with `ccx config claudemd new <name>`). `claude-md add` writes an
absolute `@<path>` reference into the `<!-- ccx:begin -->`…`<!-- ccx:end -->` block.

### config subcommands

| Subcommand                       | Description                                     |
|----------------------------------|-------------------------------------------------|
| `config claudemd new <name>`     | Create a new global claudemd fragment           |

## Configuration files

| File | Purpose |
|------|---------|
| `.claude/settings.json` | Plugins, permissions, MCP servers, skill overrides for this project |
| `.ccx/context.json` | Which profiles are enabled, their installation snapshots, and drift state |
| `.claude/rules/*.md` | Per-rule markdown files installed by profiles |
| `.claude/CLAUDE.md` | Project instructions; the `<!-- ccx:begin -->`…`<!-- ccx:end -->` block is managed by ccx |
| `~/.config/ccx/profiles/<name>/` | User-authored profiles — one directory per profile |
| `~/.config/ccx/claudemd/<name>.md` | Global CLAUDE.md fragments referenced by `claude-md add` |

### `.claude/settings.json`

The project settings file ccx reads and writes. Only the keys ccx touches are
shown; unknown keys are preserved.

```json
{
  "enabledPlugins": {
    "java@my-marketplace": true
  },
  "permissions": {
    "allow": ["Bash(mvn *)"],
    "deny": ["Bash(rm -rf *)"]
  },
  "mcpServers": {
    "filesystem": { "command": "mcp-server-fs", "args": ["--root", "."] }
  },
  "skillOverrides": {
    "java:java": "off"
  }
}
```

#### What a profile's `permissions`, `env`, and `ignore` produce

A profile declares these blocks in its `profile.yaml`:

```yaml
permissions:
  deny: ["Bash(rm -rf *)"]
env:
  JAVA_HOME: /usr/lib/jvm/java-21
ignore:
  - "target/"
  - "*.class"
```

On `ccx profiles enable`, all three merge into `.claude/settings.json`. `ignore`
patterns are translated into `Read(...)` deny rules and joined with the profile's
own `permissions.deny` entries — Claude Code has no separate ignore-file mechanism:

```json
// .claude/settings.json
{
  "permissions": {
    "deny": ["Bash(rm -rf *)", "Read(target/**)", "Read(**/*.class)"]
  },
  "env": { "JAVA_HOME": "/usr/lib/jvm/java-21" }
}
```

The translation is purely syntactic:

| `ignore:` entry   | Deny rule written    |
|-------------------|----------------------|
| `target/`         | `Read(target/**)`    |
| `*.class`         | `Read(**/*.class)`   |
| `.env`            | `Read(.env)`         |
| `docs/*.md`       | `Read(docs/*.md)`    |
| `Read(custom/**)` | `Read(custom/**)`    |

An entry already written as `Read(...)`, `Write(...)`, or `Bash(...)` passes through
verbatim, so you can bypass the auto-wrapping when you want exact control.

Two things to know about the rules this produces:

- **They are unanchored on purpose.** `Read(target/**)` has no leading `/`, so Claude Code
  resolves it relative to the current directory and, for a single-segment directory pattern
  in a deny rule, matches a `target` directory at *any* depth beneath it. `Read(/target/**)`
  would pin it to the project root only. Unanchored is the broader deny, which is what you
  want from an ignore list. Write `Read(/target/**)` yourself if you need the narrow form.
- **They cover reads and edits, not writes.** A `Read(...)` deny rule blocks the Read and Edit
  tools on the matched path, and does not apply to a file touched by an arbitrary subprocess
  (`Bash(cat …)`). Add an explicit `Write(...)` or `Bash(...)` deny entry if you need those too.

### `.ccx/context.json`

ccx tracks which profiles are enabled and an installation snapshot per profile so
it can detect drift. Written automatically — you do not edit this by hand.

```json
{
  "version": "1",
  "enabledProfiles": {
    "java": {
      "enabledAt": "2026-06-24T10:15:30Z",
      "snapshot": {
        "plugins": ["java@my-marketplace"],
        "rules": ["no-empty-catch"],
        "permissionsAllow": ["Bash(mvn *)"],
        "permissionsDeny": [],
        "commands": [],
        "fragments": [],
        "env": {},
        "ignorePatterns": ["target/"]
      }
    }
  }
}
```

## Example: scaffold a profile and apply it

ccx ships **no profiles in the binary** — author your own (see [Creating your own profile](#creating-your-own-profile) below).
Once a profile exists under `~/.config/ccx/profiles/`, apply it to a project:

```shell
# scaffold a profile, then edit ~/.config/ccx/profiles/java/profile.yaml
ccx profiles new java

# apply it to a project (auto-enables any profiles it extends)
ccx profiles enable java --project-dir ~/my-project

# check what was applied
ccx status --project-dir ~/my-project

# launch the interactive dashboard
ccx tui --project-dir ~/my-project
```

## Creating your own profile

**ccx ships no profiles in the binary** — author your own under
`~/.config/ccx/profiles/` — one directory per profile. Scaffold one with:

```bash
ccx profiles new my-profile
# edit the generated ~/.config/ccx/profiles/my-profile/profile.yaml
```

### On-disk layout

```
~/.config/ccx/
├── profiles/
│   └── my-profile/
│       ├── profile.yaml      # the profile definition (see below)
│       ├── rules/            # *.md rule files installed into .claude/rules/
│       │   └── no-empty-catch.md
│       ├── claudemd/         # *.md fragments injected into the CLAUDE.md block
│       │   └── java.md
│       └── commands/         # *.md command files
│           └── ship.md
└── claudemd/                 # global fragments, shared across all profiles
    └── company-style.md
```

### `profile.yaml`

A profile is a named bundle of plugins, rules, permissions, CLAUDE.md fragments,
environment variables, and ignore patterns. Every field is optional.

```yaml
metadata:
  name: my-profile
  description: "Java + Quarkus working set"

# inherit from other profiles; merged depth-first
extends:
  - base

plugins:
  enable:
    - java@my-marketplace

permissions:
  allow:
    - "Bash(mvn *)"
  deny:
    - "Bash(rm -rf *)"

claudeMd:
  fragments:
    - java                  # resolves to claudemd/java.md

env:
  ENABLE_LSP_TOOL: "1"

ignore:
  - "target/"
```

### A fragment file

Rule and claudemd fragments are plain markdown. Rule files are auto-discovered:
every `*.md` in a profile's `rules/` directory is applied — there is no key for
them in `profile.yaml`. Example
`~/.config/ccx/profiles/my-profile/claudemd/java.md`:

```markdown
# Java conventions

- Records over POJOs, sealed classes, pattern matching
- SLF4J for logging — never System.out
- Constructor injection, never field injection
```

### Global fragments and `claude-md add`

Fragments that aren't tied to a single profile live in
`~/.config/ccx/claudemd/`. Create one and reference it from a project:

```bash
# create a global fragment
ccx config claudemd new company-style

# reference it from the current project's .claude/CLAUDE.md
ccx claude-md add company-style
```

`claude-md add` writes an absolute `@<path>` line into the ccx-managed block of
`.claude/CLAUDE.md`, so Claude Code picks up the fragment for that project.

## Profile lifecycle

Profiles are named bundles of plugins, fragments, rules, and permissions. When you enable a profile, ccx:

1. Applies plugins to `.claude/settings.json`
2. Writes rule files to `.claude/rules/`
3. Adds fragment references to the `<!-- ccx:begin -->`…`<!-- ccx:end -->` block in `CLAUDE.md`
4. Records the installation snapshot in `.ccx/context.json`

The snapshot enables drift detection: if you manually remove a plugin or rule file, `profiles status` will show `ALTERED`. Use `profiles reinstall` to bring the profile back to its declared state.

## TUI

```bash
ccx tui [--project-dir .]
```

Interactive 5-tab dashboard (Overview, Profiles, Plugins, MCPs, CLAUDE.md).
Requires a real TTY.

| Key          | Action                                              |
|--------------|-----------------------------------------------------|
| Tab / S-Tab  | Next / previous tab                                 |
| 1–5          | Jump to tab by number                               |
| ↑ / ↓        | Navigate list (scroll on Overview)                  |
| `/`          | Focus filter bar                                    |
| Esc          | Clear filter / blur filter bar                      |
| Enter        | Context-dependent: toggle selected profile (Profiles), remove server (MCP), toggle fragment (CLAUDE.md) |
| `e` / `d` / `r` | Enable / disable / reinstall profile (Profiles tab) |
| `r`          | Refresh marketplace (Plugins tab)                   |
| `q`          | Quit                                                |
| `?`          | Toggle help overlay                                 |

## Build

### JVM

```bash
mvn verify
```

### Native binary (GraalVM)

```bash
# Install GraalVM 25 with native-image
mvn package -Pnative
./target/ccx --help
```

### Release

Releases are created by pushing a `v*` tag. The CI builds a Linux x86_64 native binary and publishes a GitHub Release via JReleaser.

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Distribution & installation

How a build becomes an installed binary, end to end.

**1. Build** — `mvn clean package -Pnative` produces `target/ccx`, a
self-contained Linux x86_64 native executable (no JVM required at runtime).

**2. Release** — the `release` Maven profile runs JReleaser, which publishes the
native binary to [GitHub Releases](https://github.com/aneveux/ccx/releases) on the
`aneveux/ccx` repository with a conventional-commits changelog. The release is cut
by pushing a `v*` tag (see [Release](#release) above); CI runs the native build and
the JReleaser publish step.

**3. Install** — `install.sh` (the `curl … | bash` one-liner above) resolves the
latest release tag, downloads the `ccx` binary and its `checksums_sha256.txt`
sidecar, **verifies the SHA-256 checksum (fail-closed: aborts on a missing or
mismatched checksum)**, and installs the binary to `~/.local/bin/ccx`. Override the
target with `CCX_INSTALL_DIR`; bypass verification (not recommended) with
`CCX_SKIP_CHECKSUM=1`.

**Documented limits (current):**
- **Linux x86_64 only.** The release builds and publishes a single
  `linux-x86_64` native binary; `install.sh` fetches only that binary.
- **No macOS build yet.** macOS arm64 native builds and the Homebrew tap are
  stubbed but disabled in `pom.xml` until a macOS native build job is added to
  `release.yml`.
