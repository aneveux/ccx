package me.neveux.ccx.service;

/**
 * Thrown when a ccx-managed configuration file exists but cannot be parsed, and the
 * operation in progress would otherwise rewrite it.
 *
 * <p>Rewriting a file ccx failed to understand silently discards whatever it could not
 * parse, so every read-modify-write path aborts with this exception instead of falling
 * back to empty defaults. Purely read-only paths (status, doctor, the TUI dashboard)
 * keep using the lenient reader and degrade gracefully.
 */
public class ConfigReadException extends RuntimeException {
    public ConfigReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
