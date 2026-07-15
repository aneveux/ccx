/**
 * Tamboui terminal UI components for the interactive ccx dashboard.
 *
 * <p>Key classes: {@code CcxApp} (top-level application), {@code TuiState} (mutable UI state),
 * {@code TuiSnapshot} (immutable data graph rebuilt after each action), {@code TuiActions}
 * (the only class that mutates project state), and tab renderers under {@code tabs/} which
 * are pure functions of the snapshot with no service dependencies.
 */
package me.neveux.ccx.tui;
