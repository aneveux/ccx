package me.neveux.ccx.service;

import me.neveux.ccx.model.Settings;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Discovers skill files in marketplace sources and manages per-project skill overrides
 * in {@code settings.json}.
 *
 * <p>Skills live under {@code <marketplace>/<pluginName>/skills/<skillName>/SKILL.md}.
 * Only local directory sources are scanned by this service — GitHub sources that have
 * not yet been cloned are silently skipped (the background clone happens in
 * {@link PluginMapService} / {@link MarketplaceCloneService}).
 *
 * <p>Override modes are validated against the set {@code {"user-invocable-only", "off"}}.
 * The mode {@code "auto"} is the implicit default and is never written to settings.
 */
@ApplicationScoped
@RegisterForReflection
public class SkillsService {

    private static final Logger log = LoggerFactory.getLogger(SkillsService.class);

    private static final Set<String> VALID_MODES = Set.of("user-invocable-only", "off");

    public record SkillEntry(
        String skillId,
        String pluginName,
        String skillName,
        Path skillMdPath
    ) {}

    private final SettingsService settingsService;

    public SkillsService() {
        this.settingsService = new SettingsService();
    }

    @Inject
    public SkillsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Scans all local directory marketplace sources in {@code settings} and returns every
     * skill found (each directory under {@code <marketplace>/<plugin>/skills/} that
     * contains a {@code SKILL.md} file).
     *
     * @param settings project settings containing the marketplace sources to scan
     * @return the discovered skills; never null, may be empty
     */
    public List<SkillEntry> discoverSkills(Settings settings) {
        if (settings.extraKnownMarketplaces() == null || settings.extraKnownMarketplaces().isEmpty()) {
            return Collections.emptyList();
        }

        var results = new ArrayList<SkillEntry>();

        for (var source : settings.extraKnownMarketplaces()) {
            if ("github".equals(source.sourceType()) || source.path() == null) {
                log.debug("skipping non-directory marketplace source key={} sourceType={}", source.key(), source.sourceType());
                continue;
            }

            var marketplacePath = Path.of(source.path());

            if (!Files.isDirectory(marketplacePath)) {
                log.debug("skipping marketplace source key={} path={} — not a directory", source.key(), source.path());
                continue;
            }

            if (!PathSecurity.resolveAndCheck(marketplacePath)) {
                log.warn("marketplace directory resolves outside user home — skipping path={} key={}", source.path(), source.key());
                continue;
            }

            try {
                marketplacePath = marketplacePath.toRealPath();
            } catch (IOException e) {
                log.warn("marketplace directory canonicalization failed — skipping path={} key={}", source.path(), source.key(), e);
                continue;
            }

            final var resolvedMarketplacePath = marketplacePath;
            try (var pluginStream = Files.list(resolvedMarketplacePath)) {
                pluginStream
                    .filter(Files::isDirectory)
                    .forEach(pluginDir -> {
                        var pluginName = pluginDir.getFileName().toString();
                        var skillsDir = pluginDir.resolve("skills");

                        if (!Files.isDirectory(skillsDir)) {
                            log.debug("skipping plugin pluginName={} — no skills directory", pluginName);
                            return;
                        }

                        try (var skillStream = Files.list(skillsDir)) {
                            skillStream
                                .filter(Files::isDirectory)
                                .forEach(skillDir -> {
                                    var skillName = skillDir.getFileName().toString();
                                    var skillMdPath = skillDir.resolve("SKILL.md");

                                    if (!Files.exists(skillMdPath)) {
                                        log.debug("skipping skill pluginName={} skillName={} — SKILL.md not found", pluginName, skillName);
                                        return;
                                    }

                                    var skillId = pluginName + ":" + skillName;
                                    var entry = new SkillEntry(skillId, pluginName, skillName, skillMdPath);
                                    log.debug("discovered skill skillId={} path={}", skillId, skillMdPath);
                                    results.add(entry);
                                });
                        } catch (IOException e) {
                            log.warn("failed to list skills dir pluginName={} skillsDir={}", pluginName, skillsDir, e);
                        }
                    });
            } catch (IOException e) {
                log.warn("failed to list marketplace source key={} path={}", source.key(), source.path(), e);
            }
        }

        return results;
    }

    /**
     * Sets a skill override in the project's {@code settings.json}. The override controls
     * how Claude Code treats the skill — {@code "user-invocable-only"} hides it from
     * auto-selection while keeping it manually invocable; {@code "off"} disables it entirely.
     *
     * @param skillId     the skill identifier ({@code pluginName:skillName})
     * @param mode        the override mode; must be one of {@code "user-invocable-only"} or {@code "off"}
     * @param projectRoot root directory of the project
     * @throws IllegalArgumentException if {@code mode} is not a recognised value
     */
    public void setOverride(String skillId, String mode, Path projectRoot) {
        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                "Invalid override mode '" + mode + "' — must be one of: " + VALID_MODES);
        }

        var settings = settingsService.readForUpdate(projectRoot);
        var overrides = settings.skillOverrides() == null
            ? new HashMap<String, String>()
            : new HashMap<>(settings.skillOverrides());
        overrides.put(skillId, mode);
        settingsService.write(settings.withSkillOverrides(overrides), projectRoot);
        log.debug("override set skillId={} mode={} projectRoot={}", skillId, mode, projectRoot);
    }

    /**
     * Removes the override for {@code skillId} from the project's {@code settings.json},
     * returning the skill to its default {@code "auto"} behaviour. Has no effect if no
     * override was set.
     *
     * @param skillId     the skill identifier ({@code pluginName:skillName})
     * @param projectRoot root directory of the project
     */
    public void removeOverride(String skillId, Path projectRoot) {
        var settings = settingsService.readForUpdate(projectRoot);
        if (settings.skillOverrides() == null) {
            return;
        }
        var overrides = new HashMap<>(settings.skillOverrides());
        overrides.remove(skillId);
        settingsService.write(
            settings.withSkillOverrides(overrides.isEmpty() ? null : overrides), projectRoot);
        log.debug("override removed skillId={} projectRoot={}", skillId, projectRoot);
    }

    /**
     * Removes all skill overrides from the project's {@code settings.json} and returns the
     * list of skill IDs that were removed.
     *
     * @param projectRoot root directory of the project
     * @return the skill IDs whose overrides were cleared; empty if none were set
     */
    public List<String> resetAllOverrides(Path projectRoot) {
        var settings = settingsService.readForUpdate(projectRoot);
        if (settings.skillOverrides() == null || settings.skillOverrides().isEmpty()) {
            return Collections.emptyList();
        }
        var removed = new ArrayList<>(settings.skillOverrides().keySet());
        settingsService.write(settings.withSkillOverrides(null), projectRoot);
        log.debug("all overrides reset count={} projectRoot={}", removed.size(), projectRoot);
        return removed;
    }

    /**
     * Returns the effective override mode for {@code skillId}, checking the project
     * settings first and falling back to the global settings. Returns {@code "auto"} if
     * neither defines an override.
     *
     * @param skillId     the skill identifier ({@code pluginName:skillName})
     * @param projectRoot root of the project (project-level {@code settings.json})
     * @param globalRoot  root of the global claude config (global-level {@code settings.json})
     * @return the active override mode, or {@code "auto"} when none is set
     */
    public String getStatus(String skillId, Path projectRoot, Path globalRoot) {
        var projectSettings = settingsService.read(projectRoot);
        if (projectSettings.skillOverrides() != null && projectSettings.skillOverrides().containsKey(skillId)) {
            return projectSettings.skillOverrides().get(skillId);
        }

        var globalSettings = settingsService.read(globalRoot);
        if (globalSettings.skillOverrides() != null && globalSettings.skillOverrides().containsKey(skillId)) {
            return globalSettings.skillOverrides().get(skillId);
        }

        return "auto";
    }
}
