package me.neveux.ccx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.neveux.ccx.config.ObjectMapperConfig;
import me.neveux.ccx.model.CcxContext;
import me.neveux.ccx.model.ProfileDriftState;
import me.neveux.ccx.model.Settings;
import me.neveux.ccx.util.FileUtils;
import me.neveux.ccx.util.IgnorePatterns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the {@code .ccx/context.json} lock file in a project directory.
 *
 * <p>The context file records which profiles are currently enabled, each with a snapshot
 * of the resources they contributed (plugins, rules, fragments, etc.) and the timestamp
 * when they were enabled. This lets ccx perform accurate drift detection and safe
 * profile removal without re-reading the marketplace.
 *
 * <p>All writes are atomic via {@link me.neveux.ccx.util.FileUtils#atomicWrite}.
 */
@ApplicationScoped
public class CcxContextService {

    private static final Logger log = LoggerFactory.getLogger(CcxContextService.class);

    private final ObjectMapper mapper;
    private final ClaudeMdService claudeMdService;

    public CcxContextService() {
        this.mapper = ObjectMapperConfig.defaultJson();
        this.claudeMdService = new ClaudeMdService();
    }

    @Inject
    public CcxContextService(@Named("jsonMapper") ObjectMapper mapper, ClaudeMdService claudeMdService) {
        this.mapper = mapper;
        this.claudeMdService = claudeMdService;
    }

    /**
     * Reads the context file from {@code <projectRoot>/.ccx/context.json}. Returns an
     * empty context (no enabled profiles) if the file does not exist or cannot be parsed.
     *
     * <p>The result is normalised: neither {@code version} nor {@code enabledProfiles} is ever
     * null, so callers can dereference the map directly however the file on disk was shaped.
     *
     * @param projectRoot root directory of the project
     * @return the current context; never null, with a non-null {@code enabledProfiles}
     */
    public CcxContext read(Path projectRoot) {
        var contextFile = projectRoot.resolve(".ccx/context.json");
        if (!Files.exists(contextFile)) {
            log.debug("context file absent path={}", contextFile);
            return CcxContext.empty();
        }
        try {
            var ctx = normalize(mapper.readValue(contextFile.toFile(), CcxContext.class));
            log.debug("context loaded path={}", contextFile);
            return ctx;
        } catch (IOException e) {
            log.warn("failed to read context path={} — returning empty context", contextFile, e);
            return CcxContext.empty();
        }
    }

    /**
     * Like {@link #read} but propagating a parse failure, for the mutating paths: rewriting a
     * context file ccx could not parse would drop every profile recorded in it.
     *
     * @throws IOException if the file exists but cannot be parsed
     */
    private CcxContext readOrThrow(Path projectRoot) throws IOException {
        var contextFile = projectRoot.resolve(".ccx/context.json");
        if (!Files.exists(contextFile)) {
            return CcxContext.empty();
        }
        return normalize(mapper.readValue(contextFile.toFile(), CcxContext.class));
    }

    /** Substitutes defaults for a missing {@code version} or {@code enabledProfiles}. */
    private static CcxContext normalize(CcxContext ctx) {
        if (ctx.version() != null && ctx.enabledProfiles() != null) {
            return ctx;
        }
        return new CcxContext(
            ctx.version() != null ? ctx.version() : CcxContext.FORMAT_VERSION,
            ctx.enabledProfiles() != null ? ctx.enabledProfiles() : Map.of());
    }

    /**
     * Writes {@code ctx} to {@code <projectRoot>/.ccx/context.json} atomically.
     *
     * @param ctx         the context to persist
     * @param projectRoot root directory of the project
     * @throws ConfigWriteException if the file cannot be written
     */
    public void write(CcxContext ctx, Path projectRoot) {
        var ccxDir = projectRoot.resolve(".ccx");
        var contextFile = ccxDir.resolve("context.json");
        try {
            FileUtils.atomicWrite(contextFile, tmp -> mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), ctx));
            log.debug("context written path={}", contextFile);
        } catch (IOException e) {
            throw new ConfigWriteException("Failed to write context to " + contextFile, e);
        }
    }

    /**
     * Adds or updates the enabled-profile entry for {@code profileName}, recording the
     * supplied snapshot and the current timestamp.
     *
     * @param profileName the profile being enabled
     * @param snapshot    the resources contributed by the profile
     * @param projectRoot root directory of the project
     * @throws IOException if the context file cannot be read or written
     */
    public void recordEnabled(String profileName, CcxContext.ProfileSnapshot snapshot, Path projectRoot) throws IOException {
        var ctx = readOrThrow(projectRoot);
        var profiles = new HashMap<>(ctx.enabledProfiles());
        profiles.put(profileName, new CcxContext.ProfileRecord(Instant.now(), snapshot));
        write(new CcxContext(ctx.version(), profiles), projectRoot);
        log.debug("profile recorded as enabled name={}", profileName);
    }

    /**
     * Removes the enabled-profile entry for {@code profileName} from the context file.
     * Has no effect if the profile was not recorded as enabled.
     *
     * @param profileName the profile being disabled
     * @param projectRoot root directory of the project
     * @throws IOException if the context file cannot be read or written
     */
    public void recordDisabled(String profileName, Path projectRoot) throws IOException {
        var ctx = readOrThrow(projectRoot);
        var profiles = new HashMap<>(ctx.enabledProfiles());
        profiles.remove(profileName);
        write(new CcxContext(ctx.version(), profiles), projectRoot);
        log.debug("profile recorded as disabled name={}", profileName);
    }

    /**
     * Detects drift for a single profile by comparing every component of the recorded snapshot
     * against the live {@code settings.json}, the files under {@code .claude/}, and the current
     * {@code <!-- ccx:begin -->} block in {@code .claude/CLAUDE.md}.
     *
     * <p>All eight snapshot components are checked: plugins, rule files, command files,
     * CLAUDE.md fragments, {@code permissions.allow}, {@code permissions.deny}, the deny rules
     * that {@code ignore:} patterns translate to, and environment entries. Any recorded
     * component that is gone — or, for env, re-pointed at a different value — is drift.
     *
     * @param profileName  the profile to check
     * @param recorded     the snapshot recorded when the profile was last enabled; may be null
     * @param liveSettings the current {@code settings.json} content
     * @param claudeDir    the project's {@code .claude/} directory
     * @return {@link me.neveux.ccx.model.ProfileDriftState#DISABLED} if {@code recorded} is null,
     *         {@link me.neveux.ccx.model.ProfileDriftState#ALTERED} if any recorded component is
     *         missing or changed, {@link me.neveux.ccx.model.ProfileDriftState#ENABLED} otherwise
     */
    public ProfileDriftState detectDrift(String profileName, CcxContext.ProfileSnapshot recorded, Settings liveSettings, Path claudeDir) {
        // claudeDir is the .claude/ directory; CLAUDE.md is now inside .claude/
        var claudeMd = claudeDir.resolve("CLAUDE.md");
        var markerLines = claudeMdService.readMarkerBlock(claudeMd);
        return detectDrift(profileName, recorded, liveSettings, claudeDir, markerLines);
    }

    /**
     * Variant of {@link #detectDrift(String, CcxContext.ProfileSnapshot, Settings, Path)}
     * that accepts pre-read marker-block lines, avoiding a redundant file read when the
     * caller has already loaded them. Drift semantics are identical — all eight snapshot
     * components are checked; only the source of the fragment lines differs.
     *
     * @param profileName  the profile to check
     * @param recorded     the snapshot recorded when the profile was last enabled; may be null
     * @param liveSettings the current {@code settings.json} content
     * @param claudeDir    the project's {@code .claude/} directory
     * @param markerLines  lines currently inside the {@code <!-- ccx:begin -->} block
     * @return {@link me.neveux.ccx.model.ProfileDriftState#DISABLED},
     *         {@link me.neveux.ccx.model.ProfileDriftState#ALTERED}, or
     *         {@link me.neveux.ccx.model.ProfileDriftState#ENABLED}
     */
    public ProfileDriftState detectDrift(String profileName, CcxContext.ProfileSnapshot recorded, Settings liveSettings, Path claudeDir, List<String> markerLines) {
        if (recorded == null) {
            return ProfileDriftState.DISABLED;
        }

        // Check plugins
        if (recorded.plugins() != null) for (var plugin : recorded.plugins()) {
            if (liveSettings.enabledPlugins() == null || !Boolean.TRUE.equals(liveSettings.enabledPlugins().get(plugin))) {
                log.debug("drift detected: plugin absent profileName={} plugin={}", profileName, plugin);
                return ProfileDriftState.ALTERED;
            }
        }

        // Check rules
        if (recorded.rules() != null) for (var rule : recorded.rules()) {
            if (!Files.exists(claudeDir.resolve("rules/" + rule + ".md"))) {
                log.debug("drift detected: rule file absent profileName={} rule={}", profileName, rule);
                return ProfileDriftState.ALTERED;
            }
        }

        // Check commands — symmetric with rules: each recorded command file must still exist
        if (recorded.commands() != null) for (var command : recorded.commands()) {
            if (!Files.exists(claudeDir.resolve("commands/" + command + ".md"))) {
                log.debug("drift detected: command file absent profileName={} command={}", profileName, command);
                return ProfileDriftState.ALTERED;
            }
        }

        // Check fragments — each recorded fragment must have @absPath in the marker block
        if (recorded.fragments() != null && !recorded.fragments().isEmpty()) {
            for (var absPath : recorded.fragments()) {
                var expected = "@" + absPath;
                if (!markerLines.contains(expected)) {
                    log.debug("drift detected: fragment absent from ccx block profileName={} fragment={}", profileName, absPath);
                    return ProfileDriftState.ALTERED;
                }
            }
        }

        var liveAllow = livePermissions(liveSettings, true);
        if (recorded.permissionsAllow() != null) for (var allow : recorded.permissionsAllow()) {
            if (!liveAllow.contains(allow)) {
                log.debug("drift detected: allow rule absent profileName={} rule={}", profileName, allow);
                return ProfileDriftState.ALTERED;
            }
        }

        var liveDeny = livePermissions(liveSettings, false);
        if (recorded.permissionsDeny() != null) for (var deny : recorded.permissionsDeny()) {
            if (!liveDeny.contains(deny)) {
                log.debug("drift detected: deny rule absent profileName={} rule={}", profileName, deny);
                return ProfileDriftState.ALTERED;
            }
        }

        // Check ignore patterns — the deny rule each recorded pattern translates to must still
        // be present. Translating (rather than storing the rule) keeps one source of truth with
        // the apply path, so a snapshot written by an older ccx still resolves to the same rule.
        if (recorded.ignorePatterns() != null) for (var pattern : recorded.ignorePatterns()) {
            if (pattern == null || pattern.isBlank()) continue;
            var rule = IgnorePatterns.toReadDenyRule(pattern);
            if (!liveDeny.contains(rule)) {
                log.debug("drift detected: ignore deny rule absent profileName={} pattern={} rule={}",
                    profileName, pattern, rule);
                return ProfileDriftState.ALTERED;
            }
        }

        // Check env — a recorded key that was removed OR re-pointed at another value is drift
        if (recorded.env() != null) for (var entry : recorded.env().entrySet()) {
            var liveValue = liveSettings.env() == null ? null : liveSettings.env().get(entry.getKey());
            if (!Objects.equals(entry.getValue(), liveValue)) {
                log.debug("drift detected: env entry changed profileName={} key={}", profileName, entry.getKey());
                return ProfileDriftState.ALTERED;
            }
        }

        return ProfileDriftState.ENABLED;
    }

    /** Live {@code permissions.allow} or {@code permissions.deny} entries, never null. */
    private static List<String> livePermissions(Settings liveSettings, boolean allow) {
        if (liveSettings == null || liveSettings.permissions() == null) {
            return List.of();
        }
        var entries = allow ? liveSettings.permissions().allow() : liveSettings.permissions().deny();
        return entries == null ? List.of() : entries;
    }

}
