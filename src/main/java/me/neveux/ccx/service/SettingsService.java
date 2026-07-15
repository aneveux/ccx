package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.util.FileUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@code .claude/settings.json} in a project directory.
 *
 * <p>Parses the JSON file into a {@link me.neveux.ccx.model.Settings} record on read.
 * If the file does not exist, an empty (default) {@code Settings} is returned rather than
 * throwing. Writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}, so a
 * crash mid-write leaves the previous settings intact.
 */
@ApplicationScoped
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final ObjectMapper mapper;

    public SettingsService() {
        this.mapper = ObjectMapperConfig.defaultJson();
    }

    @Inject
    public SettingsService(@Named("jsonMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Reads {@code <projectRoot>/.claude/settings.json}, returning an empty
     * {@code Settings} if the file does not exist or cannot be parsed.
     *
     * @param projectRoot root directory of the project
     * @return the current settings; never null
     */
    public Settings read(Path projectRoot) {
        var settingsFile = projectRoot.resolve(".claude/settings.json");
        if (!Files.exists(settingsFile)) {
            log.debug("settings file absent path={}", settingsFile);
            return new Settings();
        }
        try {
            var settings = mapper.readValue(settingsFile.toFile(), Settings.class);
            log.debug("settings loaded path={}", settingsFile);
            return settings;
        } catch (IOException e) {
            log.warn("failed to read settings path={} — returning empty settings", settingsFile, e);
            return new Settings();
        }
    }

    /**
     * Like {@link #read}, but throws on parse errors so callers can distinguish
     * a missing file (returns empty Settings) from a malformed file (throws).
     *
     * @throws IOException if the file exists but cannot be parsed
     */
    public Settings readOrThrow(Path projectRoot) throws IOException {
        var settingsFile = projectRoot.resolve(".claude/settings.json");
        if (!Files.exists(settingsFile)) {
            log.debug("settings file absent path={}", settingsFile);
            return new Settings();
        }
        var settings = mapper.readValue(settingsFile.toFile(), Settings.class);
        log.debug("settings loaded path={}", settingsFile);
        return settings;
    }

    /**
     * Writes {@code settings} to {@code <projectRoot>/.claude/settings.json} atomically,
     * creating the {@code .claude/} directory if it does not exist.
     *
     * @param settings    the settings to persist
     * @param projectRoot root directory of the project
     * @throws ConfigWriteException if the file cannot be written
     */
    public void write(Settings settings, Path projectRoot) {
        var claudeDir = projectRoot.resolve(".claude");
        var settingsFile = claudeDir.resolve("settings.json");
        try {
            FileUtils.atomicWrite(settingsFile, tmp -> mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), settings));
            log.debug("settings written path={}", settingsFile);
        } catch (IOException e) {
            throw new ConfigWriteException("Failed to write settings to " + settingsFile, e);
        }
    }
}
