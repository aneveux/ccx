package me.neveux.ccx.service;

/**
 * Thrown when a profile cannot be loaded — a {@code profile.yaml} that exists but cannot be
 * parsed (YAML syntax error, unrecognised field), or a name that cannot address a profile
 * directory at all.
 *
 * <p>Usually wraps the underlying {@link java.io.IOException} from the YAML mapper; a name that
 * fails validation has no cause to wrap.
 */
public class ProfileLoadException extends RuntimeException {
    public ProfileLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProfileLoadException(String message) {
        super(message);
    }
}
