package me.neveux.ccx.service;

/**
 * Thrown when a profile name is referenced (directly or via {@code extends}) but no
 * corresponding {@code profile.yaml} is found under the ccx profiles directory.
 */
public class ProfileNotFoundException extends RuntimeException {
    public ProfileNotFoundException(String profileName) {
        super("Profile not found: " + profileName);
    }
}
