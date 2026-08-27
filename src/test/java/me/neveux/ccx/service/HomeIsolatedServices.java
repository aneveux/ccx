package me.neveux.ccx.service;

import me.neveux.ccx.config.ObjectMapperConfig;

import java.nio.file.Path;

/**
 * Factories for services that would otherwise read the developer's real {@code ~/.claude}.
 *
 * <p>{@link PluginMapService}'s no-arg constructor builds a {@link ClaudeMarketplaceRegistryService}
 * pointed at {@code ~/.claude/plugins/known_marketplaces.json}, and every marketplace recorded there
 * is appended to the sources a plugin lookup scans. A test using it therefore resolves plugin short
 * names differently on a machine that happens to have a marketplace containing a same-named plugin —
 * a pass/fail flip driven by the developer's box rather than by the code under test.
 *
 * <p>Use these factories with a path inside the test's {@code @TempDir} that is never created.
 */
public final class HomeIsolatedServices {

    private HomeIsolatedServices() {}

    /** A registry service pointed at {@code missingRegistryFile}, so no real marketplace is ever seen. */
    public static ClaudeMarketplaceRegistryService registryServiceWithout(Path missingRegistryFile) {
        return new ClaudeMarketplaceRegistryService() {
            @Override
            protected Path registryFile() {
                return missingRegistryFile;
            }
        };
    }

    /** A plugin map service whose only marketplace sources are the ones a test declares itself. */
    public static PluginMapService pluginMapService(Path missingRegistryFile) {
        return new PluginMapService(ObjectMapperConfig.defaultJson(), new MarketplaceCloneService(),
            registryServiceWithout(missingRegistryFile));
    }
}
