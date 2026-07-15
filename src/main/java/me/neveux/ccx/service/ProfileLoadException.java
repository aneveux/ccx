package me.neveux.ccx.service;

/**
 * Thrown when a {@code profile.yaml} file exists but cannot be parsed — for example,
 * due to a YAML syntax error or an unrecognised field that prevents deserialization.
 *
 * <p>Wraps the underlying {@link java.io.IOException} from the YAML mapper.
 */
public class ProfileLoadException extends RuntimeException {
    public ProfileLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
