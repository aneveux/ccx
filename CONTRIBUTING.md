# Contributing to ccx

## Prerequisites

- Java 25+
- Maven 3.8+
- GraalVM 25+ with `native-image` (native builds only)

## Development setup

```bash
git clone https://github.com/aneveux/ccx
cd ccx
mvn verify
```

## Running tests

```bash
mvn test
```

Tests use `@QuarkusTest` with `@TempDir` redirection — no home directory writes.

## Building

**JVM:**
```bash
mvn verify
java -jar target/quarkus-app/quarkus-run.jar --help
```

**Native binary:**
```bash
mvn package -Pnative
./target/ccx --help
```

## Commit conventions

- Imperative mood, present tense: `Add profile drift detection`, not `Added`
- Subject line < 72 characters
- One logical change per commit
- Body when the why is not obvious from the subject

## Pull requests

- One logical change per PR
- Tests required for new behaviour and bug fixes
- Do not bundle refactors with feature changes — separate PRs

## Key directories

| Directory | Purpose |
|-----------|---------|
| `cli/` | Picocli `@Command` classes — one per top-level command |
| `service/` | CDI `@ApplicationScoped` business logic and file I/O |
| `model/` | Jackson-annotated POJOs (`Settings`, `Profile`, `CcxContext`, …) |
| `tui/` | Tamboui terminal dashboard (`CcxApp`, tab renderers, `TuiActions`) |
| `config/` | CDI configuration producers (`ObjectMapperConfig`) |
| `graal/` | GraalVM reflection registrations for native builds |

## Project conventions

**Services**
- Every service needs both an `@Inject` constructor (CDI) and a no-arg constructor (test instantiation).
- All file writes must be atomic: write to a `.tmp` sibling, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`.

**Commands**
- Commands read from `~/.claude` via a `--global-dir` option — never hardcode home-relative paths.
- Static nested `@Command` subcommand classes use field injection (explicit CDI exception).

**Testing**
- Never assert only on exit code — always assert output content too.
- Use `@TempDir` to redirect file I/O; never write to the real home directory.
- `AssertJ` is used for assertions (declared explicitly — not in the Quarkus BOM).

## How to add a new command

1. Create a class in `cli/` annotated `@Command(name = "...", description = "...")`.
2. Annotate the class `@Dependent @Unremovable` and implement `Callable<Integer>`.
3. Inject services via the `@Inject` public constructor; add a no-arg constructor for tests.
4. Add a `@CommandLine.Spec CommandLine.Model.CommandSpec spec` field for output and error streams.
5. Use `CommandUtil.resolveProjectDir(projectDir)` for `--project-dir` resolution.
6. Use `CommandUtil.checkPathSecurity(dir, spec)` to guard user-supplied paths; return `1` on rejection.
7. Register the command in `CcxCommand`'s `@Command(subcommands = {...})` annotation.
8. Add the command class to `TestCommandFactory` — field name must match the service field names exactly.
9. Create a test class in `src/test/java/.../cli/` extending `QuarkusTest` (or plain JUnit if no CDI). Assert both output content and exit code.

## Authoring a profile

**ccx ships with no bundled profiles** — `src/main/resources/profiles/` is empty
and stays that way. Profiles are user data, authored on disk under
`~/.config/ccx/profiles/<name>/`. Scaffold one with `ccx profiles new <name>` and
edit the generated `profile.yaml`. See the
[Creating your own profile](README.md#creating-your-own-profile) section of the
README for the full on-disk layout and schema.

**Minimal `profile.yaml`:**
```yaml
metadata:
  name: myprofile
  description: "What this profile does"

plugins:
  enable:
    - some-plugin

claudeMd:
  fragments:
    - my-fragment

rules:
  files:
    - my-rule
```

Key schema fields:
- `extends: [parent-profile]` — inherit from one or more profiles; merged depth-first
- `plugins.enable` — list of plugin short names resolved via `PluginMapService`
- `claudeMd.fragments` — fragment names resolving to `claudemd/<name>.md` in the profile dir
- `rules.files` — rule file base names resolving to `rules/<name>.md` in the profile dir
- `permissions.allow/deny` — merged into `.claude/settings.json` permissions arrays
- `env` — environment variables; `ignore` — `.claudeignore` patterns
