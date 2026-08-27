# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Breaking.** `ccx clean` no longer modifies anything without `--force`. A bare
  `ccx clean` always prints what would be removed and exits. It previously destroyed
  ccx-managed configuration whenever a TTY was present — with no confirmation, though
  `--help` advertised one — and only performed a dry run when no TTY was available.
- **Breaking.** A profile's `claudeMd.fragments` list is now honoured. A profile that
  declares the list contributes exactly those files, in declaration order, and an empty
  list contributes nothing; only a profile with no `fragments` key still falls back to
  globbing `claudemd/*.md`. The declared list was previously deserialized and ignored, so
  every profile contributed its whole `claudemd/` directory regardless. A declared name
  resolves against the profile's own directory before the shared one, with or without the
  `.md` suffix, and must land on a regular file inside the ccx config directory.
- `ccx profiles disable` now removes the `permissions.allow` and `permissions.deny`
  entries the profile contributed, sibling-aware: a rule another enabled profile still
  contributes is kept. Disabling previously stranded every permission rule it had added.
- The five read-only `plugins` and `skills` subcommands that printed a path-security
  rejection and then exited 0 now exit 1, matching their write-command siblings and the
  documented contract.
- `ccx doctor` exits 1 on an `[ERROR]` only; the `(not found in marketplace)`
  `[WARNING]` is deliberately non-failing. The Javadoc and README previously claimed
  either would fail the command.
- Both CI workflows pin GraalVM 25.0.2 exactly rather than asking for whatever 25.x is
  current, so a release binary is reproducible.

### Fixed

- ccx no longer replaces a `settings.json` it could not parse. Every read-modify-write
  path now goes through a strict read that refuses on a parse failure. The lenient read
  returned empty settings on a malformed file, so the next write replaced the user's
  entire configuration; it is now confined to read-only paths, where a broken file must
  still be inspectable.
- `permissions` and `mcpServers` keys ccx does not model survive a write. `defaultMode`,
  `ask`, `additionalDirectories` and per-server `env` / `headers` were previously dropped
  at every nesting level.
- The `CLAUDE.md` marker block is normalised to exactly one well-formed instance on every
  write. A file with two blocks, an inverted marker pair, or a truncated begin marker
  previously ended up with nested blocks and a duplicated `@fragment` line per write.
- Profile cycle detection keys off the registry key instead of the user-editable
  `metadata.name`. A profile with no `metadata` block used to overflow the stack, and a
  copied profile directory whose metadata name was never edited faked a cycle. Diamond
  inheritance resolves correctly.
- The marketplace clone cache verifies that an existing clone's origin matches the
  declared repository, so a clone left by another project cannot be read as the requested
  marketplace. `""` and `"."` are rejected as marketplace keys.
- A truncated plugin-map cache — one whose `entries` field is absent — is rebuilt instead
  of throwing.
- Holding the TUI refresh key no longer starts two concurrent fetches in the same clone:
  the in-flight guard is claimed on the calling thread. A fetch that failed no longer
  reports itself as a fresh cache of 0 plugins.
- Skill discovery resolves marketplaces through Claude Code's own registry, so a github
  marketplace Claude Code has already cloned is scanned rather than skipped.
- `ccx clean` refuses before removing anything when `settings.json` is corrupt — a
  half-cleaned project is worse than a refused clean.
- The TUI Overview tab distinguishes an unreadable `.claude/settings.json` from an empty
  one; it previously rendered zeroes, reading as "nothing is configured here".

## [0.4.0] - 2026-08-27

### Added

- `ccx plugins sync` installs every plugin enabled in the project that Claude Code has
  no installation record for — the repair path for projects configured before ccx
  installed plugins, or whose cached plugin tree was garbage-collected. `--dry-run`
  previews without installing.

### Fixed

- Enabling a plugin now installs it. Claude Code tracks enablement
  (`enabledPlugins` in `settings.json`) and installation
  (`~/.claude/plugins/installed_plugins.json` plus the cache tree under
  `~/.claude/plugins/cache/`) separately; ccx only ever wrote the first, so a plugin
  enabled through ccx failed to load with `isn't installed here` or `not cached`.
  Every enable path — `plugins enable`, `profiles enable`, `profiles reinstall`, and the
  TUI's plugin and profile toggles — now runs
  `claude plugin install <plugin>@<marketplace> --scope project` in the project directory
  first. TUI installs run in the background so the UI never blocks. When the `claude`
  binary is missing (override its location with `CCX_CLAUDE_BIN`) or the install fails,
  the enablement is still written and the exact command to run by hand is printed.
- `ccx doctor` reports `[ERROR] plugin enabled but not installed for this project` for
  each such plugin and exits 1. Note the behaviour change: doctor can now fail on plugin
  installation state.

## [0.3.2] - 2026-08-26

### Fixed

- Marketplaces now resolve from Claude Code's own registry
  (`~/.claude/plugins/known_marketplaces.json`) before ccx tries to clone them. A
  marketplace on a private GitHub repository previously never resolved — ccx cloned
  over https with `GIT_TERMINAL_PROMPT=0` and could not authenticate — so every
  plugin from it reported `not found in marketplace`. A marketplace registered only
  with Claude Code and absent from every `settings.json` is now discovered too.
- `ccx doctor` verifies enabled plugins against user-scope `extraKnownMarketplaces`
  as well as project-scope ones. A plugin from a marketplace declared only in
  `~/.claude/settings.json` previously reported as unverifiable.
- Plugin short-name resolution is first-wins, so a project-scope marketplace is no
  longer shadowed by a global one declaring the same plugin name — the previous
  last-wins behaviour contradicted `mergeSources()`.

## [0.3.1] - 2026-08-12

### Fixed

- Drift detection now checks all eight recorded snapshot components. It previously
  looked at plugins, rule files, and CLAUDE.md fragments only, so a deleted command
  file, a removed `permissions.allow`/`deny` entry, a deny rule derived from an
  `ignore:` pattern, or an `env` value re-pointed elsewhere all read as ENABLED.
- Enabling a profile from the TUI now skips dependency-closure members that are
  already enabled, matching `ccx profiles enable`. It previously re-applied and
  re-stamped their `.ccx/context.json` records.
- `install.sh` verifies only the `ccx` line of the checksum file, so the install no
  longer breaks the first time a release publishes a second artifact.

### Changed

- The `ignore:` → `permissions.deny` translation moved to `me.neveux.ccx.util.IgnorePatterns`
  so the apply path and drift detection share one source of truth. See the ADR-014
  revision in `DECISIONS.md` for why the generated rules are left unanchored.

## [0.3.0] - 2026-07-16

### Changed

- Profile rule files are now auto-discovered: every `*.md` in a profile's `rules/`
  directory is applied, matching how `claudemd/` and `commands/` already work. The
  `rules.files` key in `profile.yaml` is no longer read.

## [0.2.0] - 2026-07-16

### Changed

- Native binary is now named `ccx` instead of `ccx-runner`.
- Renamed Java package from `io.github.aneveux.ccx` to `me.neveux.ccx`.
- `status --full` now shows global plugins, skill overrides, and MCP servers.
- Profile dependency closure: enabling a profile auto-enables its `extends` dependencies; missing dependencies show as BROKEN drift state.
- TUI Overview tab rewritten as responsive stacked-block layout with per-item bullet lists.
- TUI help overlay uses styled Paragraph instead of markdown table for better legibility.
- `ccx skills` verbs normalized to `enable`/`disable` spine matching other noun groups.
- `ccx mcp enable` accepts `--command`, `--args`, `--url`, `--type` flags in addition to raw JSON.

### Fixed

- `q` typed into an active TUI filter no longer quits the application.
- TUI double-buffer character-width desync for emoji glyphs — switched to Hearts palette (💚💛💔💙🤍🚧) which are measured correctly by tamboui.
- Overview scroll clamp: scrolling past the last line no longer overwrites the tab header.
- `ccx profiles enable` auto-enables dependency profiles transitively.

### Security

- `TuiCommand --project-dir` now runs `checkPathSecurity` before opening the TUI.
- `SkillsCommand` write subcommands (`enable`, `disable`, `hide`, `reset`) return exit code 1 on path-security rejection.
- `ProfilesCommand.EditCommand` splits `$EDITOR` on whitespace before passing to `ProcessBuilder`.
- `MarketplaceCloneService.ensureLocal()` rejects NUL bytes in directory source keys.

### Removed

- `ccx init` and `ccx compose` commands removed; profile management via `ccx profiles enable` covers the use case.
- `InlineRenderer` interactive sub-app path removed; all list output goes to plain stdout.
- Starter profiles removed from the repository; use `ccx profiles new` to author your own.

## [0.1.0] - 2026-06-25

Initial release.

### Added

- `ccx clean` to remove ccx-managed configuration from a project.
- `ccx doctor` to validate project configuration.
- `ccx status` to summarize the current configuration.
- `ccx plugins` (`list`, `enable`, `disable`, `diff`, `tags`, `refresh`) to manage Claude Code plugins.
- `ccx profiles` (`list`, `show`, `enable`, `disable`, `reinstall`, `status`, `edit`, `new`) to manage user-authored profiles under `~/.config/ccx/profiles/`.
- `ccx skills` (`list`, `enable`, `disable`, `hide`, `show`, `reset`) to manage skill overrides.
- `ccx mcp` (`list`, `enable`, `disable`, `show`) to manage MCP servers.
- `ccx claude-md` (`list`, `add`, `remove`, `init`) to manage CLAUDE.md fragment references.
- `ccx config claudemd new` to create global CLAUDE.md fragments.
- `ccx tui` — interactive 5-tab terminal dashboard (Overview, Profiles, Plugins, MCPs, CLAUDE.md).
- Profile drift detection via installation snapshots in `.ccx/context.json`.
- Atomic, path-security-checked file writes for all managed configuration.
- `install.sh` for one-line installation into `~/.local/bin`.

[Unreleased]: https://github.com/aneveux/ccx/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/aneveux/ccx/compare/v0.3.2...v0.4.0
[0.3.2]: https://github.com/aneveux/ccx/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/aneveux/ccx/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/aneveux/ccx/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/aneveux/ccx/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aneveux/ccx/releases/tag/v0.1.0
