# ccx — Project Conventions

## Overview

`ccx` is a CLI + full-screen TUI for managing fine-grained Claude Code configuration
per project. It handles plugin enablement, `CLAUDE.md` fragment composition, MCP servers,
skill overrides, rule files, and reusable profiles — so `.claude/settings.json`, `CLAUDE.md`,
and related files never need manual editing. Built on Quarkus + Picocli, packaged as a
GraalVM native binary.

Architecture: see `ARCHITECTURE.md`. Vision: see `VISION.md`.

## Build & Test

- JVM build: `mvn -o clean package`
- Run tests: `mvn -o test`
- Native build: `mvn -o package -Dnative` (requires GraalVM 25 / Mandrel on PATH)
- Run CLI: `java -jar target/quarkus-app/quarkus-run.jar` or the native `-runner` binary
- Use absolute `mvn` path and `-o` (offline) flag to bypass the rtk hook and skip
  tamboui metadata fetches (see Environment section below).

## Layout

- `cli/`     — Picocli `@Command` classes; entry points only, delegate to services
- `service/` — CDI `@ApplicationScoped` beans; the only layer that touches the filesystem
- `model/`   — Jackson records for `settings.json`, `profile.yaml`, `context.json`
- `config/`  — CDI producers: `ObjectMapperConfig` (`@Named("jsonMapper")`, `@Named("yamlMapper")`)
- `tui/`     — Tamboui dashboard (`CcxApp`, `TuiDataService`, tabs, `TuiActions`)
- `util/`    — `FileUtils` (atomic writes), `ListUtils` (ordered union)
- `graal/`   — `NativeConfig`: reflection + runtime-init entries for GraalVM native image

## Project Patterns (from trellis)

### Services
- Every service needs both `@Inject` constructor (CDI) and no-arg constructor (test instantiation). Quarkus 3.x ARC prefers `@Inject` when both exist.
- All file writes must be atomic: write to a `.tmp` sibling, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`. Direct writes corrupt files on interrupted writes. `SettingsService` and `PluginMapService` both follow this pattern.
- `ProfileService` creates its own YAML mapper in the no-arg constructor (for test instantiation). In the CDI path, the `@Named("yamlMapper")` bean from `ObjectMapperConfig` is injected via the `@Inject` constructor — both paths are wired.

### Commands (Picocli)
- `CommandLine.setOut()` takes `PrintWriter`, not `PrintStream`. There is no `setIn()`.
- Never use `@ApplicationScoped` on a picocli `@Command` with `@Spec`/`@Option`/`@Parameters` field injection — use `@Dependent`. The ARC client proxy intercepts picocli's `@Spec` field write and leaves it null → NPE in production (confirmed by decompiling quarkus-picocli-deployment). Static nested `@Command` subcommands are auto-`@Dependent @Unremovable` by `PicocliProcessor.addScopeToCommands`, so they're never proxied — field injection is acceptable there as an explicit exception to the constructor-injection rule.
- `CcxApp`, `TuiActions`, and the root `CcxCommand` are NOT CDI beans (built by hand, not via the picocli `IFactory`). They must construct their own services (`new SettingsService()`) and be exception-safe (a banner/render must never throw).
- Commands that read from `~/.claude` must accept a `--global-dir` option so tests can redirect to `@TempDir`. Never hardcode home-relative paths in command logic.
- `DoctorCommand --global-dir` uses HOME-root semantics (`Path.of(user.home)`, resolves `.claude/` underneath), distinct from `CommandUtil.resolveGlobalDir()` which returns `~/.claude` directly. These cannot be unified. Guard `checkPathSecurity` on `--global-dir` only when it is explicitly non-null.
- Subcommands implement `Callable<Integer>` and return their exit code. The root `CcxCommand` is the one exception: it implements `Runnable` + `CommandLine.IExitCodeGenerator` and stores its code in a field for `getExitCode()`. Picocli treats a `Callable`'s return value as the exit code, but the root command also renders the bare-`ccx` banner, and a banner path that returns a value invites returning one by accident; `IExitCodeGenerator` keeps signalling a rejected `--project-dir` possible without making the happy path return anything.
- `TestCommandFactory` injects services by field name via reflection. Fields must be named exactly: `settingsService`, `profileService`, `pluginMapService`, `claudeMdService`, `skillsService`, `profileApplyService`, `ccxContextService`, `profileLifecycleService`, `ccxConfigService`, `marketplaceCloneService`, `claudePluginRegistryService`, `claudePluginInstallService`, `mapper`. New commands must match.

### TUI
- The render thread must never call `PluginMapService.buildMap()`/`resolve()` — those trigger a git clone via `ensureLocal()` and freeze the UI. Use `buildMapNoFetch()` on the render path; the background daemon thread owns the actual clone/pull.
- Package-private TUI classes (`TuiDataService`, `MarketplaceFetchController`, `PreviewMarkdownBuilder`) require their tests to live in the `me.neveux.ccx.tui` package.

### Testing
- Never assert only on exit code — TDD stubs throw `UnsupportedOperationException` which also exits non-zero, causing false passes. Always assert output content too.
- AssertJ is not in the Quarkus BOM — declare version explicitly (`3.27.7` in `pom.xml`).
- Mockito is NOT on the classpath (only `quarkus-junit5` + `assertj-core`). For failure injection, hand-roll a throwing subclass (e.g. `ObjectWriter` overriding `writeValue` to throw). For driving the real production CDI path under test, use `QuarkusMock.installMockForType(realObject, Type.class)` — it takes a real object and ships in `quarkus-junit5`.
- Under `@QuarkusTest`/`@QuarkusMainTest`, `@TempDir` field injection runs AFTER `@BeforeEach` (fields are null when setup fires). Create temp dirs manually with `Files.createTempDirectory` in `@BeforeEach` and clean up with a reverse-order walk in `@AfterEach`.
- A backlog/STATE "open" status is a HINT, never ground truth. Always re-verify a finding against actual `file:line` on disk before trusting it — prior "open" ≠ currently-broken.

### Language & serialization
- Jackson deserializes records natively via record-component reflection — no `-parameters` flag, no `@JsonCreator`/`@JsonProperty` needed. But `@RegisterForReflection` on model records is load-bearing for the GraalVM native build (failure is at runtime, not compile — the JVM suite won't catch it).

### Environment
- The `rtk` Claude Code hook rewrites `git`/`grep`/`find`/`mvn` into `rtk <cmd>`, which fails on a broken bwrap loopback even with `dangerouslyDisableSandbox`. Invoke the absolute binary path (`/usr/bin/git`, `/usr/bin/grep`, `/usr/bin/find`, full `mvn` path) to bypass the hook. Add `-o` (offline) to `mvn` to skip the tamboui SNAPSHOT metadata fetch.
- A PreToolUse security hook rejects any Write payload containing a literal ESC byte (0x1b). For ANSI escapes in source, build them at runtime via `Character.toString(27)`, never `\033` or a raw byte.
