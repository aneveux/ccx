# ccx — Architecture

## Overview

`ccx` is a CLI tool for managing fine-grained Claude Code configuration per project. It handles plugin enablement, `CLAUDE.md` fragment composition, MCP server management, skill overrides, rule files, and profile lifecycle — eliminating the need to manually edit `.claude/settings.json`, `CLAUDE.md`, or related files. A full-screen TUI dashboard provides an interactive view of all configuration dimensions. Profiles define reusable configuration bundles that can be enabled, disabled, and drift-detected.

## Layers

### CLI (`me.neveux.ccx.cli`)
Picocli `@Command` classes: one per top-level command. Each top-level command is a CDI `@Dependent` bean (plan 036 Wave 1 flipped them from `@ApplicationScoped` — the ARC client proxy nulled picocli's `@Spec` field injection in production). Subcommands are static nested classes that use field injection (explicit CDI exception). Commands are the only entry point for user input; they delegate all business logic to services.

### Service (`me.neveux.ccx.service`)
CDI `@ApplicationScoped` beans containing all business logic. Services are the only classes that read and write files. Each service follows the atomic-write invariant: all file writes go through a `.tmp` sibling + `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`.

Key services:
- `SettingsService` — reads/writes `.claude/settings.json`
- `ProfileService` — loads and resolves profile YAML from the on-disk ccx config dir; supports multi-level inheritance
- `ProfileApplyService` — applies a resolved profile (plugins, fragments, rules, permissions) to a project; also removes profile resources
- `CcxContextService` — manages `.ccx/context.json` lock file tracking which profiles are enabled and their installation snapshot; provides drift detection
- `ClaudeMdService` — reads/writes the `<!-- ccx:begin -->`…`<!-- ccx:end -->` marker block in `CLAUDE.md`
- `PluginMapService` — discovers and maps plugin short names to qualified keys from marketplace directories
- `SkillsService` — discovers skills from configured marketplace paths
- `MarketplaceCloneService` — clones/pulls marketplace plugin repositories for offline discovery
- `ClaudePluginRegistryService` — read-only view of Claude Code's `~/.claude/plugins/installed_plugins.json`: which enabled plugins are actually installed for a project (see ADR-015)
- `ClaudePluginInstallService` — runs `claude plugin install <plugin>@<marketplace> --scope project` in the project directory; never throws, reports a status instead

### Model (`me.neveux.ccx.model`)
Jackson-annotated records/POJOs for serialization. No business logic. Key types: `Settings`, `Profile`, `CcxContext`, `CcxContext.ProfileSnapshot`, `Permissions`, `McpServer`.
`Settings` uses an inner `Builder` class for deserialization (`@JsonDeserialize(builder=Settings.Builder.class)` + `@JsonPOJOBuilder(withPrefix="")`) to support `@JsonAnySetter` passthrough of unknown fields — records cannot carry `@JsonAnySetter` directly. Both `Settings` and `Settings.Builder` are annotated `@RegisterForReflection` (inner classes require a separate annotation for GraalVM native).
Marketplace cluster: `MarketplaceManifest`, `MarketplacePlugin`, `MarketplaceSource`
(with custom `MarketplaceSourceSerializer`/`Deserializer`), `PluginMapCache`.
Claude Code plugin registry (read-only): `ClaudePluginRegistry`, `ClaudePluginInstall`.
Profile sub-records: `ProfileMetadata`, `ProfilePlugins`, `ProfileClaudeMd`.
Context sub-record: `CcxContext.ProfileRecord`.

### TUI (`me.neveux.ccx.tui`)
Tamboui-based interactive terminal dashboard launched by `TuiCommand`. Architecture:
- `TuiDataService` (CDI) — builds `TuiSnapshot`, the full immutable data graph for all tab renders
- `TuiSnapshot` — value object containing all data needed to render every tab; built once per action
- `CcxApp` — top-level Tamboui application; owns `TuiState` and `TuiActions`; dispatches key events
- `TuiState` — mutable state: active tab, selected rows, filter focus, snapshot reference
- `TuiActions` — non-CDI plain Java object; the only TUI class that mutates project state; delegates to services
- `PluginInstallController` — dispatches `claude plugin install` to a single background daemon thread so the render thread never blocks on a subprocess; publishes progress to the footer and flags a snapshot rebuild when done
- `FilterState` — per-tab filter state wrapping Tamboui `TextInputState`
- Tab classes (`tabs/`) — pure renderers implementing `FilterableTab<T>`; no service injection; no state mutation

### GraalVM (`me.neveux.ccx.graal`)
`NativeConfig` registers classes that require reflection at build time via `@RegisterForReflection`. Also declares `initialize-at-run-time` entries for JLine/JNA native backend classes that cannot be initialized at image build time.

### Util (`me.neveux.ccx.util`)
Stateless static helpers: `FileUtils.atomicWrite` (write-to-.tmp then `Files.move ATOMIC_MOVE`),
`ListUtils.orderedUnion` (null-safe ordered dedup of two lists), and
`IgnorePatterns.toReadDenyRule` (profile `ignore:` pattern → `permissions.deny` rule, shared by
`ProfileApplyService` and `CcxContextService` so apply and drift detection cannot disagree), and
`ProcessRunner.run` (hardened subprocess execution: argv list never a shell string, merged stderr,
virtual-thread output drain to avoid a pipe-buffer deadlock, bounded timeout + `destroyForcibly`;
shared by `MarketplaceCloneService` and `ClaudePluginInstallService`).

### Config (`me.neveux.ccx.config`)
CDI producer class `ObjectMapperConfig`: produces `@Named("jsonMapper")` (Jackson JSON) and
`@Named("yamlMapper")` (Jackson YAML) `ObjectMapper` beans, both configured with
`ACCEPT_CASE_INSENSITIVE_PROPERTIES`, `JavaTimeModule`, and no-timestamps serialization.

## Module Map

```
cli/
  ProfilesCommand    → ProfileApplyService, CcxContextService, ProfileService, CcxConfigService, SettingsService,
                        ClaudePluginRegistryService, ClaudePluginInstallService
  PluginsCommand     → SettingsService, PluginMapService, MarketplaceCloneService,
                        ClaudePluginRegistryService, ClaudePluginInstallService
  SkillsCommand      → SettingsService, SkillsService
  McpCommand         → SettingsService
  ClaudeMdCommand    → ClaudeMdService, CcxConfigService
  DoctorCommand      → SettingsService, PluginMapService, ClaudeMdService, CcxConfigService, ProfileService,
                        CcxContextService, ClaudePluginRegistryService
  StatusCommand      → SettingsService, ClaudeMdService
  CleanCommand       → SettingsService, ClaudeMdService
  ConfigCommand      → CcxConfigService
  TuiCommand         → TuiDataService, ProfileApplyService, CcxContextService, ProfileService,
                        SettingsService, ClaudeMdService, MarketplaceCloneService, PluginMapService
  (`init` and `compose` top-level commands were removed in plan 036 Wave 2; `resolveDependencyClosure` migrated to ProfileService. Profile `ignore:` patterns are applied by ProfileApplyService as `permissions.deny` rules — see ADR-014.)

service/
  ProfileApplyService   → SettingsService, ClaudeMdService, PluginMapService, CcxConfigService
  CcxContextService     → ClaudeMdService (for readMarkerBlock, fragment drift check)
  ProfileService        → CcxConfigService (loads YAML from config dir)
  SettingsService       (no service deps — Jackson + filesystem)
  ClaudeMdService       (no service deps — filesystem only)
  SkillsService         → SettingsService
  PluginMapService      → MarketplaceCloneService (Jackson + filesystem)
  ClaudePluginRegistryService  (no service deps — Jackson + filesystem, read-only)
  ClaudePluginInstallService   → ProcessRunner (spawns the `claude` CLI)

tui/
  TuiDataService     → SettingsService, ClaudeMdService, CcxContextService,
                        ProfileService, PluginMapService, CcxConfigService
  TuiActions         → SettingsService, ClaudeMdService, TuiDataService,
                        ProfileApplyService, CcxContextService, ProfileService
  CcxApp             (plain Java; constructed by TuiCommand with injected services)
  PluginInstallController → SettingsService, ClaudePluginRegistryService, ClaudePluginInstallService
                     (plain Java; owned by CcxApp; runs installs off the render thread)
  tabs/FilterableTab (abstract; implemented by 4 list-tab classes — no service deps)
                     (OverviewTab is a standalone final class; it does not extend FilterableTab)
```

Dependency direction: `cli → service → model`. The `tui` package depends on `service` and `model` but CLI commands do not import TUI classes (except `TuiCommand` which instantiates `CcxApp`).

## Data Flow

### CLI command: `ccx profiles enable java --project-dir .`

1. Picocli parses args → routes to `ProfilesCommand.EnableCommand.call()`
2. `ProfileService.load("java")` → reads `~/.config/ccx/profiles/java/profile.yaml` from disk (validates the leaf exists before anything is written)
3. `ProfileService.resolveDependencyClosure("java")` → the `extends` closure in dependency-first order, leaf last. Each member is then `load()`ed and applied individually — a member already recorded in `.ccx/context.json` is skipped, so re-enabling never re-stamps an existing record
4. `ProfileApplyService.apply(member, dir, false, context)` per member:
   - Reads existing `settings.json` via `SettingsService.read()`
   - Adds plugin keys to `enabledPlugins`
   - Merges permissions
   - Merges `env` entries
   - Translates `ignore:` patterns into `permissions.deny` rules via `IgnorePatterns.toReadDenyRule` (see ADR-014)
   - Writes updated `settings.json` atomically — one write, so any new settings step must be ordered before it
   - Writes rule files to `.claude/rules/` atomically
   - Writes fragment lines to `.claude/CLAUDE.md` marker block atomically
   - Writes command files to `.claude/commands/` atomically
   - Returns `ProfileSnapshot` (what was written)
5. `CcxContextService.recordEnabled(member, snapshot, dir)`:
   - Reads `.ccx/context.json`
   - Adds the profile record with timestamp + snapshot
   - Writes `.ccx/context.json` atomically
6. list/confirmation commands print to stdout via the picocli `PrintWriter` + `out.flush()` (the `InlineRenderer` interactive path was removed in plan 036 Wave 3)

### TUI refresh after action (e.g. pressing `e` on Profiles tab)

1. `CcxApp.handleEvent()` → dispatches to `handleProfileAction("enable")`
2. `TuiActions.enableProfile(name, projectDir)` → calls services (same as above)
3. Returns new `TuiSnapshot` from `TuiDataService.snapshot(projectDir)`
4. `TuiState.setSnapshot(newSnap)` — replaces immutable snapshot
5. Tamboui calls `CcxApp.renderFrame()` on next tick → all tabs re-render from new snapshot (pure)

## Boundaries

- **CLI layer delegates business logic to services** — edge-case commands (`CleanCommand`, `ProfilesCommand.NewCommand`, `ConfigCommand`) do direct atomic writes/deletes where no service boundary is warranted; all other I/O goes through services
- **Tab classes are pure renderers** — no service injection, no file I/O, no state mutation
- **`TuiActions` is the only TUI class that mutates project state** — all other TUI classes are read-only
- **All file writes are atomic** — write to `.tmp` sibling, then `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`
- **`settings.json` is the source of truth** for plugins, permissions, MCP servers, and skill overrides
- **`.ccx/context.json` tracks profile membership** — NOT `.claude/` (that belongs to Claude Code)
- **Profiles live on disk under `~/.config/ccx/profiles/<name>/profile.yaml`** — there are no bundled classpath profiles (`src/main/resources/` holds only `application.properties` and `META-INF/`); users author their own
- **Path boundary checks** — all user-supplied paths (e.g. `--project-dir`, `--output`, marketplace paths) are validated to stay within `user.home`, `user.dir`, or `java.io.tmpdir`

## PathSecurity

`me.neveux.ccx.service.PathSecurity` is a final utility class with two static methods:

| Method | Semantics |
|--------|-----------|
| `isAllowed(Path)` | Lenient — allows non-existent paths (for future writes). On `IOException` from `toRealPath()`, falls back to lexical `toAbsolutePath().normalize()`. Null → false. |
| `resolveAndCheck(Path)` | Strict — non-existent paths return false (can't verify, reject). On `IOException` → false. Used for read/exec operations where the path must already exist. |

**Allowed boundaries** (checked against resolved path):
- `System.getProperty("user.home")` — the user's home directory tree
- `System.getProperty("user.dir")` — the current working directory tree
- `System.getProperty("java.io.tmpdir")` — the system temp directory tree

**Symlink handling**: both methods call `Path.toRealPath()` first, which resolves all symlinks to their final target. This prevents a symlink like `/tmp/evil → /etc` from bypassing the boundary check — the real path `/etc` does not start with any allowed boundary.

Command helpers in `CommandUtil` wrap `PathSecurity.isAllowed()`:
- `CommandUtil.checkPathSecurity(dir, spec)` — prints a standard error message and returns false on rejection; commands return exit code 1 immediately.
