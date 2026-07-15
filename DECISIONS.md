# ccx — Decisions

Architecture Decision Records (ADRs). Each entry captures a significant
technical choice, its context, and its consequences.

## ADR-001: Java + Quarkus + Picocli stack

**Date:** 2026-06-10
**Status:** accepted

**Context:** The original ccx is written in another language. A Java rewrite
allows leveraging the existing Java/Quarkus expertise in this codebase and
enables native binary distribution via GraalVM.

**Decision:** Use Java 17+, Quarkus for the application framework, and Picocli
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

## ADR-012: `budget`, `spawn`, and `clean` commands deferred indefinitely

**Date:** 2026-06-17
**Status:** accepted

**Context:** The original ccx has `budget` (token estimation), `spawn` (ephemeral sessions), and `clean` (remove ccx config) commands. The TUI backlog also had entries for a Compose tab and TUI coverage of these commands.

**Decision:** `budget` and `spawn` are deferred indefinitely — do not implement unless explicitly requested. `clean` has no TUI tab and that is intentional — it is a destructive one-off operation not suited to an interactive panel. The CLAUDE.md tab in the TUI covers the compose/stack use case. These items are not tracked as open backlog items.

**Consequences:** Reduces scope. If any of these are needed later, they should be explicitly requested and planned.

---

## ADR-011: `InlineApp` for styled CLI output with TTY guard

**Date:** 2026-06-12
**Status:** superseded (plan 036 Wave 3 — InlineRenderer removed; see ADR-002 revision)

**Context:** Commands like `profiles list`, `plugins list`, and `profiles enable` benefit from styled terminal output (icons, color, badges) but must degrade gracefully in CI, pipes, and test environments.

**Decision:** `InlineRenderer` uses Tamboui `InlineApp`/`InlineToolkitRunner` for styled output when a TTY is available (`System.console() != null && CI != "1"`). All render methods accept a `Runnable fallback` that writes plain text via the Picocli `PrintWriter`. A null fallback does nothing (callers that do not need a fallback can omit it via the 3-arg/5-arg overloads).

**Consequences:** Tests capture output via the Picocli `PrintWriter` — the fallback path — so styled output never pollutes test assertions. The TTY guard prevents JLine from emitting escape sequences to redirected output.

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
