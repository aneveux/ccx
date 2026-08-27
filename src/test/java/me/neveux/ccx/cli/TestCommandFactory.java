package me.neveux.ccx.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMarketplaceRegistryService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.ClaudePluginInstallService;
import me.neveux.ccx.service.ClaudePluginRegistryService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileLifecycleService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test-only picocli IFactory that injects service instances directly into command
 * fields, bypassing CDI. This lets command tests run as plain JUnit without Quarkus.
 */
public class TestCommandFactory implements CommandLine.IFactory {

    private final SettingsService settingsService;
    private final ProfileService profileService;
    private final PluginMapService pluginMapService;
    private final ClaudeMdService claudeMdService;
    private final SkillsService skillsService;
    private final ProfileApplyService profileApplyService;
    private final CcxContextService ccxContextService;
    private final CcxConfigService ccxConfigService;
    private final MarketplaceCloneService marketplaceCloneService;
    /**
     * Reports every enabled plugin as already installed, so commands under test never reach the
     * install path. Tests that exercise installation replace it via
     * {@link #withClaudePluginRegistryService}.
     */
    private ClaudePluginRegistryService claudePluginRegistryService = new ClaudePluginRegistryService() {
        @Override
        public boolean isInstalledFor(String qualifiedName, Path projectRoot) {
            return true;
        }

        @Override
        public List<String> missingInstalls(Map<String, Boolean> enabledPlugins, Path projectRoot) {
            return List.of();
        }
    };

    /** Fails without spawning a process, so a stray install attempt can never shell out in tests. */
    private ClaudePluginInstallService claudePluginInstallService = new ClaudePluginInstallService() {
        @Override
        public InstallResult install(String qualifiedName, Path projectRoot) {
            return new InstallResult(Status.CLI_NOT_FOUND, "no Claude Code CLI in tests");
        }
    };

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = new SkillsService(settingsService, new ClaudeMarketplaceRegistryService());
        this.profileApplyService = null;
        this.ccxContextService = null;
        this.ccxConfigService = null;
        this.marketplaceCloneService = null;
    }

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService,
            MarketplaceCloneService marketplaceCloneService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = new SkillsService(settingsService, new ClaudeMarketplaceRegistryService());
        this.profileApplyService = null;
        this.ccxContextService = null;
        this.ccxConfigService = null;
        this.marketplaceCloneService = marketplaceCloneService;
    }

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService,
            SkillsService skillsService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = skillsService;
        this.profileApplyService = null;
        this.ccxContextService = null;
        this.ccxConfigService = null;
        this.marketplaceCloneService = null;
    }

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService,
            SkillsService skillsService,
            ProfileApplyService profileApplyService,
            CcxContextService ccxContextService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = skillsService;
        this.profileApplyService = profileApplyService;
        this.ccxContextService = ccxContextService;
        this.ccxConfigService = null;
        this.marketplaceCloneService = null;
    }

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService,
            SkillsService skillsService,
            ProfileApplyService profileApplyService,
            CcxContextService ccxContextService,
            CcxConfigService ccxConfigService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = skillsService;
        this.profileApplyService = profileApplyService;
        this.ccxContextService = ccxContextService;
        this.ccxConfigService = ccxConfigService;
        this.marketplaceCloneService = null;
    }

    /**
     * Sets the plugin registry service injected into commands that check installation state.
     * Fluent rather than a constructor overload so existing test call sites stay untouched.
     */
    public TestCommandFactory withClaudePluginRegistryService(ClaudePluginRegistryService service) {
        this.claudePluginRegistryService = service;
        return this;
    }

    /** Sets the plugin install service injected into commands that install plugins. */
    public TestCommandFactory withClaudePluginInstallService(ClaudePluginInstallService service) {
        this.claudePluginInstallService = service;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K> K create(Class<K> cls) throws Exception {
        var instance = CommandLine.defaultFactory().create(cls);
        injectIfPresent(instance, "settingsService", settingsService);
        injectIfPresent(instance, "profileService", profileService);
        injectIfPresent(instance, "pluginMapService", pluginMapService);
        injectIfPresent(instance, "claudeMdService", claudeMdService);
        injectIfPresent(instance, "skillsService", skillsService);
        injectIfPresent(instance, "profileApplyService", profileApplyService);
        injectIfPresent(instance, "ccxContextService", ccxContextService);
        injectIfPresent(instance, "profileLifecycleService", profileLifecycleService());
        injectIfPresent(instance, "ccxConfigService", ccxConfigService);
        injectIfPresent(instance, "marketplaceCloneService", marketplaceCloneService);
        injectIfPresent(instance, "claudePluginRegistryService", claudePluginRegistryService);
        injectIfPresent(instance, "claudePluginInstallService", claudePluginInstallService);
        injectIfPresent(instance, "mapper", new ObjectMapper());
        return (K) instance;
    }

    /**
     * The lifecycle service built from the three services it composes, or null when a test did not
     * supply them — the same "absent means leave the field alone" contract the other services have.
     */
    private ProfileLifecycleService profileLifecycleService() {
        if (profileService == null || profileApplyService == null || ccxContextService == null) return null;
        return new ProfileLifecycleService(profileService, profileApplyService, ccxContextService);
    }

    private void injectIfPresent(Object target, String fieldName, Object value) {
        if (value == null) return;
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(target, value);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to inject " + fieldName + " into " + target.getClass(), e);
        }
    }

    private Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
