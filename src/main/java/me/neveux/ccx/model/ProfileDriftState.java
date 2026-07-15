package me.neveux.ccx.model;

/**
 * Describes the reconciliation state between a profile's expected configuration
 * and what is actually present in {@code .claude/settings.json}.
 * Used by {@code ProfileService.resolve()} to report per-profile drift.
 */
public enum ProfileDriftState {

    /** The profile is enabled and its settings exactly match the live configuration. */
    ENABLED,

    /** The profile is not listed as enabled in the current context. */
    DISABLED,

    /**
     * The profile is enabled but one or more of its contributed settings have been
     * modified in {@code .claude/settings.json} since it was applied.
     */
    ALTERED,

    /**
     * The profile's state could not be determined, typically because the context
     * file or settings file is missing or malformed.
     */
    BROKEN
}
