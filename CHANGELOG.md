# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/aneveux/ccx/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/aneveux/ccx/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aneveux/ccx/releases/tag/v0.1.0
