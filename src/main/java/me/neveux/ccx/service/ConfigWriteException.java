package me.neveux.ccx.service;

/**
 * Thrown when a write to a ccx-managed configuration file fails — for example, when
 * {@code settings.json} or {@code context.json} cannot be atomically moved into place.
 *
 * <p>Wraps the underlying {@link java.io.IOException} so callers do not need to catch
 * checked exceptions for what is a fatal configuration-layer error.
 */
public class ConfigWriteException extends RuntimeException {
    public ConfigWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
