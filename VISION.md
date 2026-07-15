# ccx — Vision

## Purpose
CLI for managing fine-grained Claude Code configuration per project.

## Target User
Software engineers using Claude Code who need per-project
configuration management (rules, hooks, MCP servers, settings)
without manually juggling CLAUDE.md and settings files.

## Principles

1. Terminal-native: every workflow works without leaving the terminal
2. Per-project first: configuration is scoped to the project root
3. Composable: layered config (user → project → local) with clear precedence
4. Explicit over magic: no silent defaults, changes are always visible
5. Delightful terminal UX: rich output, interactive pickers, color — not just plain text

## Non-Goals

1. No GUI — terminal-native only
2. Not a general dotfiles manager — scoped to Claude Code config
3. No cloud sync or remote config storage

## Constraints
- Java 25+ (Quarkus + Picocli)
- Rich terminal UI via Tamboui — experiment-first, adopt where it improves UX
- Must be distributable as a native binary (GraalVM)
