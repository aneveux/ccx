package me.neveux.ccx.graal;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM native image configuration for ccx.
 *
 * <p>Registers classes that are referenced only at runtime — primarily Tamboui's JLine3
 * backend provider — for class-only reflection. Without this registration, the native
 * binary throws {@link ClassNotFoundException} at startup when looking up the backend.
 */
@RegisterForReflection(targets = {dev.tamboui.backend.jline3.JLineBackendProvider.class}, methods = false, fields = false)
public class NativeConfig {}
