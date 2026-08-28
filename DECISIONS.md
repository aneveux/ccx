# ccx — Decisions

Architecture Decision Records (ADRs). Each entry captures a significant
technical choice, its context, and its consequences.

## ADR-001: Java + Quarkus + Picocli stack

**Date:** 2026-06-10
**Status:** accepted

**Context:** The original ccx is written in another language. A Java rewrite
allows leveraging the existing Java/Quarkus expertise in this codebase and
enables native binary distribution via GraalVM.

**Decision:** Use Java 25, Quarkus for the application framework, and Picocli
for CLI argument parsing.

**Consequences:** Native binary via GraalVM is possible but requires
reflection-awareness. Dev loop is fast with Quarkus dev mode.

---

## ADR-002: Tamboui for rich terminal UI

**Date:** 2026-06-10
**Status:** accepted

**Context:** Plain text CLI output is functional but not delightful. The project
principle is "delightful terminal UX" — interactive pickers, color, and rich
formatting should be first-class.

**Decision:** Experiment with Tamboui for terminal UI components. Adopt where
it meaningfully improves the experience; fall back to plain Picocli output
where Tamboui adds friction without value.

**Consequences:** Full integration shipped in plan 012. GraalVM native build requires `initialize-at-run-time` entries for JLine backend classes (`Kernel32`, `INPUT_RECORD`, `CHAR_INFO`) and JNA. `NativeConfig.java` registers these. TTY detection guard required for non-TTY environments (CI, piped output) — `InlineRenderer` falls back to plain Picocli output when `System.console() == null` or `CI=1`. **Revision (2026-06-30):** The `InlineRenderer`/`InlineApp` path was removed in plan 036 Wave 3 — it launched an interactive sub-app that produced blank output on a real TTY. Plain stdout via the picocli `PrintWriter` is now the only path.

---

## ADR-003: Profile ownership via `.ccx/context.json` lock file

**Date:** 2026-06-12
**Status:** accepted

**Context:** When multiple profiles are enabled, ccx needs to track which resources (plugins, stacks, rules) were installed by which profile, in order to remove them precisely on disable without affecting other profiles.

**Decision:** Maintain a `.ccx/context.json` file per project containing a `ProfileRecord` per enabled profile. Each record stores a `ProfileSnapshot` (lists of plugins, stacks, rules, permissions written at enable time) and a timestamp. The file lives under `.ccx/` (not `.claude/`) to clearly separate ccx metadata from Claude Code native config.

**Consequences:** Drift detection (ENABLED/ALTERED/DISABLED) compares the snapshot against live state. Snapshots can become stale if files are manually edited — detected and surfaced as ALTERED state.

---

## ADR-004: `FilterState` with `TextInputState` composition for TUI filter bars

**Date:** 2026-06-12
**Status:** accepted

**Context:** All list tabs (Profiles, Plugins, Skills, MCPs, CLAUDE.md) need a filter bar that routes typed characters to a text input and filters the list in real time.

**Decision:** Introduce `FilterState` as a thin wrapper around Tamboui `TextInputState`. It exposes `filter(items, labelFn)` for filtering, `clampedIndex(filtered)` for safe selection, and `appendChar`/`backspace`/`clear` for input. Tab 0 (Overview) and Tab 6 (Doctor) have `null` filter state — `TuiState.hasFilter()` guards access.

**Consequences:** Filter state is per-tab and persists while the tab is visible. Switching tabs does not clear the filter. Escape clears filter text and returns to normal navigation.

---

## ADR-005: `ProfileApplyService` centralisation

**Date:** 2026-06-12
**Status:** accepted

**Context:** Both `InitCommand` (bulk init) and `ProfilesCommand` (per-profile enable) need to apply profile resources. Early implementation duplicated the permissions-merge logic across both.

**Decision:** `ProfileApplyService` is the single owner of the apply/remove lifecycle. `InitCommand` delegates to it for the real apply path. The `overwrite` flag distinguishes init-with-force (always write) from re-enable (skip existing files).

**Consequences:** `InitCommand.mergeProfileIntoSettings()` was retained only for the dry-run preview path (which doesn't write files) to avoid pulling service dependencies into a read-only code path.

**Revision (2026-06-30):** `InitCommand` (and `ComposeCommand`/`ComposeService`) were removed in plan 036 Wave 2. `ProfileApplyService` is now the sole owner of the apply/remove lifecycle; the dry-run preview path described above no longer exists. `resolveDependencyClosure` moved to `ProfileService` and `.claudeignore` bootstrap to `ProfileApplyService`.

**Revision (2026-08-11):** the `.claudeignore` bootstrap named above no longer exists — `.claudeignore` was never a real Claude Code feature. `ProfileApplyService` now translates profile `ignore:` patterns into `permissions.deny` rules inside `settings.json`. See ADR-014.

---

## ADR-006: `TuiSnapshot` as full immutable data graph

**Date:** 2026-06-12
**Status:** accepted

**Context:** The TUI needs to render its tabs (5 in the shipped layout — see ADR-007) from live project state. Tabs could query services directly or receive a pre-built snapshot.

**Decision:** `TuiDataService.snapshot()` builds the full `TuiSnapshot` on every action. Tab renderers are pure functions of the snapshot — no service injection, no live reads. After any mutation (`TuiActions`), a new snapshot is built and replaces the old one in `TuiState`.

**Consequences:** Each action triggers a full re-read of `settings.json`, `CLAUDE.md`, context.json, and skill directories. For typical project sizes (< 100 plugins, < 20 skills) this is fast enough. Eliminates stale-cache bugs — the snapshot is always current.

---

## ADR-007: 5-tab TUI layout with fixed ordering

**Date:** 2026-06-12 (revised 2026-06-17, 2026-06-25)
**Status:** accepted

**Context:** The TUI needs a consistent, predictable tab order that users can navigate by number key. The original 7-tab layout included a Skills/Overrides tab, but skills are always packaged inside plugins from a marketplace — there is no meaningful skill management surface without a plugin context.

**Decision:** Fixed 5-tab order: `0=Overview`, `1=Profiles`, `2=Plugins`, `3=MCPs`, `4=CLAUDE.md`. Number keys 1-5 jump directly to tabs 0-4. Tab/Shift-Tab cycle.

**Consequences:** Tab indices are hardcoded in `CcxApp.handleEvent()` and `handleEnter()`. Adding a new tab requires updating `TAB_COUNT`, `filterStates[]`, all switch statements in CcxApp, and `syncListState()`.

**Revision (2026-06-25):** The Skills tab was removed earlier (revision 2026-06-17), and the Doctor tab was never wired into live navigation. The code settled on `TuiState.TAB_COUNT = 5` with tabs `Overview / Profiles / Plugins / MCPs / CLAUDE.md` (keys 1-5); there is no Doctor or Skills tab. This ADR is corrected from "6-tab" to "5-tab" to match the shipped code.

---

## ADR-008: Master-detail layout for list tabs

**Date:** 2026-06-12
**Status:** accepted

**Context:** List tabs (Profiles, Plugins, Skills, MCPs, CLAUDE.md) each need a searchable list on the left and a detail/preview pane on the right.

**Decision:** Standard layout: 3-row filter bar on top, body split 40% list / 60% preview. Implemented in `FilterableTab<T>` base class which 4 list tabs extend (Profiles, Plugins, MCPs, CLAUDE.md). OverviewTab is a standalone final class that does not extend `FilterableTab`. Tab-specific item formatting and preview content are provided by abstract methods.

**Consequences:** Consistent visual layout across all list tabs. `FilterableTab<T>` holds the `ListState` — accessible via `listState()` for `CcxApp.syncListState()`.

---

## ADR-009: Context file at `.ccx/context.json`, not `.claude/`

**Date:** 2026-06-12
**Status:** accepted

**Context:** ccx needs a per-project metadata file to track enabled profiles and their snapshots.

**Decision:** Store at `.ccx/context.json`. The `.claude/` directory is owned by Claude Code; storing ccx metadata there would create ambiguity and potential conflicts with future Claude Code features.

**Consequences:** Projects using ccx gain a `.ccx/` directory. Should be added to `.gitignore` if context.json should not be committed (team members would each have their own enabled profiles).

---

## ADR-010: Markdown strings for TUI preview panes

**Date:** 2026-06-12
**Status:** accepted

**Context:** Preview panes need to show rich formatted content (headers, bold, lists) for profiles, plugins, stacks.

**Decision:** `TuiDataService` builds pre-composed Markdown strings (e.g. `buildProfilePreviewMarkdown()`) and stores them in snapshot entries. Tab renderers pass these strings to `PreviewRenderer.render()` which displays them as-is.

**Consequences:** Markdown is generated at snapshot-build time, not render time. Changes to preview formatting require a snapshot rebuild. Tamboui renders Markdown in the preview pane; unsupported syntax falls back to raw text.

---

## ADR-011: `InlineApp` for styled CLI output with TTY guard

**Date:** 2026-06-12
**Status:** superseded (plan 036 Wave 3 — InlineRenderer removed; see ADR-002 revision)

**Context:** Commands like `profiles list`, `plugins list`, and `profiles enable` benefit from styled terminal output (icons, color, badges) but must degrade gracefully in CI, pipes, and test environments.

**Decision:** `InlineRenderer` uses Tamboui `InlineApp`/`InlineToolkitRunner` for styled output when a TTY is available (`System.console() != null && CI != "1"`). All render methods accept a `Runnable fallback` that writes plain text via the Picocli `PrintWriter`. A null fallback does nothing (callers that do not need a fallback can omit it via the 3-arg/5-arg overloads).

**Consequences:** Tests capture output via the Picocli `PrintWriter` — the fallback path — so styled output never pollutes test assertions. The TTY guard prevents JLine from emitting escape sequences to redirected output.

---

## ADR-012: `budget`, `spawn`, and `clean` commands deferred indefinitely

**Date:** 2026-06-17
**Status:** accepted

**Context:** The original ccx has `budget` (token estimation), `spawn` (ephemeral sessions), and `clean` (remove ccx config) commands. The TUI backlog also had entries for a Compose tab and TUI coverage of these commands.

**Decision:** `budget` and `spawn` are deferred indefinitely — do not implement unless explicitly requested. `clean` has no TUI tab and that is intentional — it is a destructive one-off operation not suited to an interactive panel. The CLAUDE.md tab in the TUI covers the compose/stack use case. These items are not tracked as open backlog items.

**Consequences:** Reduces scope. If any of these are needed later, they should be explicitly requested and planned.

---

## ADR-013: `Settings.Builder` for unknown-key passthrough

**Date:** 2026-07-13
**Status:** accepted

**Context:** `settings.json` is owned by Claude Code, not ccx. Claude Code freely adds new top-level keys (`model`, `statusLine`, `outputStyle`, etc.). Without passthrough, every ccx read-modify-write cycle would silently drop these keys — data loss on the user's own Claude Code config.

A Jackson `@JsonAnySetter` method on the record itself is not possible: records are final and their components are fixed; Jackson cannot inject unknown keys into a record canonical constructor without a custom deserializer.

**Decision:** `Settings` uses a static inner `Builder` class annotated with `@JsonDeserialize(builder=Settings.Builder.class)` and `@JsonPOJOBuilder(withPrefix="")`. The Builder carries `@JsonAnySetter` (collects unknown keys into a `Map<String, Object> unknownFields`) and `@JsonAnyGetter` on the record's `unknownFields()` accessor spreads them back inline on serialization.

**Critical gotcha:** `@JsonPOJOBuilder(withPrefix="")` is load-bearing. Without it, Jackson defaults to looking for `withX()` setter methods — the compile succeeds but every field deserializes as null at runtime. This is a silent failure mode with no stack trace.

**Consequences:**
- Both `Settings` and `Settings.Builder` must be annotated `@RegisterForReflection` — GraalVM does not automatically register inner classes even when the outer class is registered.
- Any new known top-level field added to `Settings` must also get a corresponding setter in `Builder`.
- The `unknownFields` component uses `@JsonInclude(NON_EMPTY)` so it is omitted from serialized output when empty (no `"unknownFields":{}` noise in the JSON).

---

## ADR-014: `ignore:` patterns become `permissions.deny` rules, not a `.claudeignore` file

**Date:** 2026-08-11
**Status:** accepted

**Context:** ccx wrote profile `ignore:` patterns to a `.claudeignore` file at the project root, and bootstrapped that file with a default pattern set on first profile enable. `.claudeignore` is not a real Claude Code feature — it was hallucinated by an LLM and propagated into ccx across plans 024 and 036. Claude Code has never read the file, so every pattern ccx wrote there was silently ignored. Users who added `ignore: ["*.env"]` to a profile got no protection at all while the tool reported success.

The mechanism Claude Code actually honours is `permissions.deny` in `.claude/settings.json`, whose entries are tool-use patterns like `Read(target/**)`.

**Decision:** `Profile.ignore` is applied as `Read(...)` deny rules merged into `settings.json`. `ProfileApplyService.toReadDenyRule` performs a purely syntactic translation: a trailing-slash pattern becomes `Read(<pattern>**)`, a bare glob with no path separator becomes `Read(**/<pattern>)` so it matches at any depth, anything else is wrapped as `Read(<pattern>)`, and an entry already written as `Read(...)`/`Write(...)`/`Bash(...)` passes through verbatim. Blank and null entries are skipped.

`ccx clean --force` still deletes a `.claudeignore` if present — the file is no longer managed, but older ccx versions created one and leaving it behind would be confusing litter.

**Consequences:**
- The snapshot records only those original patterns whose translated rule was *absent* from `permissions.deny` before the merge. Without this, disabling a profile would delete a deny rule the user wrote by hand.
- `ignore:` no longer has a dedicated on-disk artefact, so the `.claudeignore` reporting in `ccx doctor`, `ccx status`, and the TUI overview was removed along with `TuiSnapshot.ClaudeIgnoreInfo`.
- **Tension with VISION principle 4 ("explicit over magic: no silent defaults"):** auto-wrapping `target/` into `Read(target/**)` is a translation the user does not see in their profile YAML. Accepted, mitigated two ways — the mapping table is documented in `README.md` and `CONTRIBUTING.md`, and raw `Read(...)`/`Write(...)`/`Bash(...)` entries pass through untouched so a user who wants exact control has it.
- Existing projects carrying a stale `.claudeignore` are not migrated automatically. Re-enabling a profile writes the correct deny rules; the dead file is removed by `ccx clean`.

**Revision (2026-08-11, same day):** three corrections after verifying the translation against the published permission semantics (https://code.claude.com/docs/en/permissions).

The translation now lives in `me.neveux.ccx.util.IgnorePatterns.toReadDenyRule`, not in a private `ProfileApplyService` method. It has two callers that must agree: the apply path writes the rule, and `CcxContextService.detectDrift` re-derives it from the recorded pattern to decide whether the rule is still present. Duplicating the logic would let drift detection disagree with what apply wrote, so a single static helper is the source of truth. Recording the original pattern rather than the derived rule also means a snapshot written by an older ccx resolves through the current translation.

Ownership is decided by **provenance, not novelty**: `applyIgnoreAsDenyRules` claims a rule when it is absent from `permissions.deny` *or* already contributed by a recorded profile (`ccxOwnedDenyRules(context)`). The earlier "absent before the merge" rule alone left two profiles sharing a pattern with only one owner, so disabling that one stranded the rule for the other. A rule the user wrote by hand, with no recorded profile behind it, is still never claimed.

**The generated rules are deliberately left unanchored, and this is not a bug.** Per the docs, `Read(target/**)` resolves relative to the current directory, and a deny rule whose pattern starts with a single-segment directory name matches that directory at any depth beneath it. `Read(/target/**)` would resolve relative to the settings file's own directory and match the project root only. The unanchored form is therefore a *superset* of the anchored one, which is the correct default for an ignore list; switching to `/`-anchored rules would silently narrow coverage and strand every rule already recorded in the unanchored form. Users who want the narrow form write `Read(/target/**)` in `permissions.deny` directly, where it passes through verbatim.

Scope limit worth stating explicitly: a `Read(...)` deny rule covers the Read and Edit tools (Edit since Claude Code v2.1.208) and does not constrain a file touched by an arbitrary subprocess. `ignore:` is not a sandbox; it is a guardrail against the model reading or editing noise.

---

## ADR-015: Plugin installation is delegated to the Claude Code CLI

**Date:** 2026-08-27
**Status:** accepted

**Context:** Claude Code keeps *enablement* and *installation* as two independent pieces of state. Enablement is `enabledPlugins` in `.claude/settings.json` — the only thing ccx ever wrote. Installation is a record in `~/.claude/plugins/installed_plugins.json` (carrying `scope`, `projectPath`, `installPath`, `version`, `gitCommitSha`) plus a materialized tree under `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/` with `.in_use` refcount and `.orphaned_at` GC markers. A plugin enabled with no matching installation record does not load, and Claude Code reports it as `isn't installed here` (error type `plugin-not-installed`) or `not cached at …` (`plugin-cache-miss`).

Every plugin ccx enabled was therefore inert unless the user happened to have installed it by hand for that exact project. Claude Code's own remediation string is literally `claude plugin install <plugin>@<marketplace> --scope project`.

**Decision:** ccx writes the enablement and delegates the installation to the Claude Code CLI, invoked as `claude plugin install <plugin>@<marketplace> --scope project` with the working directory set to the project root (the cwd is what lets Claude Code see the project's `extraKnownMarketplaces`). This runs on every enable path — `plugins enable`, `profiles enable`, `profiles reinstall`, and the TUI's plugin and profile toggles — and `ccx plugins sync` repairs projects already in the broken state.

**Rejected alternative — writing `installed_plugins.json` and the cache tree directly.** It is reachable: the file shape is simple JSON. But version resolution from the marketplace catalog, cache materialization, `.in_use` refcounting and the `.orphaned_at` GC are Claude Code internals with no compatibility contract, and they change between releases. The `cloudbees-context` failure that prompted this work *was* an orphan-marked cache directory — exactly the state a hand-rolled writer would produce and then fail to notice. Delegation trades a subprocess dependency for never owning that logic.

**Consequences:**
- `scope` is `project`, never `user`. A user-scope install also adds the key to `~/.claude/settings.json`, and `TuiActions.togglePlugin` deliberately refuses to manage any plugin present in global settings — user scope would make every ccx-installed plugin untoggleable.
- On `plugins enable`, the install runs **before** ccx writes `settings.json`, so ccx has the last word on formatting and its `unknownFields` round-trip. `claude plugin install` rewrites that file itself, but the only key it touches is `enabledPlugins` and it preserves unknown fields (measured against Claude Code 2.x), so the paths where it writes last — `profiles enable`/`reinstall`, `plugins sync`, the TUI — lose nothing beyond ccx's key ordering.
- ccx now has a runtime dependency on the `claude` binary for this one operation. Discovery is `CCX_CLAUDE_BIN` → `which claude` → `~/.local/bin/claude` → `~/.claude/local/claude`; the user's interactive `claude` may be a shell function, which a subprocess cannot see. A missing binary or a failed install is a **warning, not a failure**: the enablement is still written, the exact manual command is printed, and the exit code stays 0. The gap is then visible in `ccx doctor` and fixable with `ccx plugins sync`. `plugins sync` itself exits 1 on failure, since being an explicit repair command is its whole purpose.
- **`--yes` is deliberately not passed.** `claude plugin install -y` accepts a marketplace-declared install command without a confirmation prompt, which would let ccx run arbitrary code from a marketplace unattended — against `.claude/rules/security-safety.md`. Ordinary git/archive plugins install fine without it; a plugin that genuinely needs a declared command lands on the FAILED path and the user runs the printed command themselves.
- The qualified name is validated against `[A-Za-z0-9._-]+@[A-Za-z0-9._-]+` *before* anything is spawned, and the argv is a `List<String>` through `util/ProcessRunner` — never a shell string.
- TUI installs are dispatched to a single background daemon thread (`tui/PluginInstallController`). The render thread must never block on a subprocess that may clone a marketplace over the network, and the installs are serialized because concurrent `claude plugin install` invocations would race on `installed_plugins.json`.
- `ccx doctor` can now exit 1 on plugin installation state. The pre-existing `(not found in marketplace)` WARNING stays non-failing; a plugin Claude Code refuses to load is an ERROR.
- `ClaudePluginRegistryService` reads `installed_plugins.json` but never writes it. An unreadable or malformed file is advisory: it logs a warning and reports nothing missing, so a bad file cannot produce a wall of false positives or trigger a wave of installs.
