package me.neveux.ccx.service;

/**
 * Thrown when profile inheritance contains a cycle — for example, profile A extends B
 * and B extends A. Detected during {@link ProfileService#loadAndResolve} traversal.
 */
public class ProfileCycleException extends RuntimeException {
    public ProfileCycleException(String message) {
        super(message);
    }
}
