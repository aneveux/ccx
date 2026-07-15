package me.neveux.ccx.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.service.CcxConfigService;
import me.neveux.ccx.service.CcxContextService;
import me.neveux.ccx.service.ClaudeMdService;
import me.neveux.ccx.service.MarketplaceCloneService;
import me.neveux.ccx.service.PluginMapService;
import me.neveux.ccx.service.ProfileApplyService;
import me.neveux.ccx.service.ProfileService;
import me.neveux.ccx.service.SettingsService;
import me.neveux.ccx.service.SkillsService;
import picocli.CommandLine;

import java.lang.reflect.Field;

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

    public TestCommandFactory(
            SettingsService settingsService,
            ProfileService profileService,
            PluginMapService pluginMapService,
            ClaudeMdService claudeMdService) {
        this.settingsService = settingsService;
        this.profileService = profileService;
        this.pluginMapService = pluginMapService;
        this.claudeMdService = claudeMdService;
        this.skillsService = new SkillsService(settingsService);
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
        this.skillsService = new SkillsService(settingsService);
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
        injectIfPresent(instance, "ccxConfigService", ccxConfigService);
        injectIfPresent(instance, "marketplaceCloneService", marketplaceCloneService);
        injectIfPresent(instance, "mapper", new ObjectMapper());
        return (K) instance;
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
