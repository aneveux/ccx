package me.neveux.ccx.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The enable / disable / reinstall lifecycle of a profile in a project, owned in one place so the
 * CLI and the TUI cannot answer "what does enabling do" differently.
 *
 * <p>Each method performs the state change and returns what happened; how it is reported is the
 * caller's business (a printed line, a footer message, an exit code). Nothing here writes to a
 * terminal, and installing the plugins a newly enabled profile declares is deliberately left out:
 * the CLI installs them synchronously while the TUI installs them on a background executor.
 */
@ApplicationScoped
public class ProfileLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ProfileLifecycleService.class);

    private final ProfileService profileService;
    private final ProfileApplyService profileApplyService;
    private final CcxContextService ccxContextService;

    public ProfileLifecycleService() {
        this(new ProfileService(), new ProfileApplyService(), new CcxContextService());
    }

    @Inject
    public ProfileLifecycleService(ProfileService profileService, ProfileApplyService profileApplyService,
                                   CcxContextService ccxContextService) {
        this.profileService = profileService;
        this.profileApplyService = profileApplyService;
        this.ccxContextService = ccxContextService;
    }

    /**
     * What one {@link #enable} call did, member by member of the profile's extends closure.
     *
     * @param applied              members freshly applied and recorded, dependencies first
     * @param alreadyEnabled       members skipped because the context already recorded them
     * @param missingDependencies  members named by an {@code extends} chain with no profile on disk
     */
    public record EnableResult(List<String> applied, List<String> alreadyEnabled,
                               List<String> missingDependencies) {

        /** Whether the profile the user asked for was already enabled, making this call a no-op for it. */
        public boolean wasAlreadyEnabled(String name) {
            return alreadyEnabled.contains(name);
        }

        /** The already-enabled members other than {@code name}, which are dependencies of it. */
        public List<String> alreadyEnabledDependencies(String name) {
            return alreadyEnabled.stream().filter(member -> !member.equals(name)).toList();
        }
    }

    /**
     * Applies the profile's whole extends closure, dependencies first and the leaf last, so a
     * dependent is never recorded enabled without its dependencies.
     *
     * <p>A member the context already records as enabled is skipped rather than re-applied:
     * re-applying cannot repair an edited rule file (rules are written with {@code overwrite=false}),
     * so {@link #reinstall} is the honest repair path. A dependency that has no profile on disk is
     * reported in the result instead of aborting the others.
     *
     * @param name       the profile the user asked for; validated before anything is written
     * @param projectDir the project to enable it in
     * @return which members were applied, skipped, or missing
     * @throws ProfileNotFoundException if {@code name} itself has no profile on disk
     * @throws IOException              if the context file cannot be updated
     */
    public EnableResult enable(String name, Path projectDir) throws IOException {
        profileService.load(name);

        var applied = new ArrayList<String>();
        var alreadyEnabled = new ArrayList<String>();
        var missingDependencies = new ArrayList<String>();
        for (var member : profileService.resolveDependencyClosure(name)) {
            var context = ccxContextService.read(projectDir);
            if (context.enabledProfiles().containsKey(member)) {
                alreadyEnabled.add(member);
                continue;
            }
            try {
                var profile = profileService.load(member);
                var snapshot = profileApplyService.apply(profile, projectDir, false, context);
                ccxContextService.recordEnabled(member, snapshot, projectDir);
                applied.add(member);
            } catch (ProfileNotFoundException missing) {
                log.warn("dependency missing on disk dependency={} of={}", member, name);
                missingDependencies.add(member);
            }
        }
        log.debug("profile enabled name={} applied={} alreadyEnabled={} projectDir={}",
            name, applied, alreadyEnabled, projectDir);
        return new EnableResult(List.copyOf(applied), List.copyOf(alreadyEnabled),
            List.copyOf(missingDependencies));
    }

    /**
     * Reverts everything the recorded snapshot of {@code name} claims — skipping resources a
     * sibling profile still claims — and drops it from the context file.
     *
     * @param name       the profile to disable
     * @param projectDir the project to disable it in
     * @return false when the profile was not recorded as enabled, in which case nothing was touched
     * @throws IOException if the context file cannot be updated
     */
    public boolean disable(String name, Path projectDir) throws IOException {
        var context = ccxContextService.read(projectDir);
        var record = context.enabledProfiles().get(name);
        if (record == null) {
            log.debug("profile not enabled, nothing to disable name={} projectDir={}", name, projectDir);
            return false;
        }
        profileApplyService.remove(record.snapshot(), projectDir, context, name);
        ccxContextService.recordDisabled(name, projectDir);
        log.debug("profile disabled name={} projectDir={}", name, projectDir);
        return true;
    }

    /**
     * Removes the profile if it is recorded, then re-applies it with {@code overwrite=true} so
     * edited rule and command files are restored — the repair path {@link #enable} deliberately
     * is not. The profile is applied fully resolved, so an inherited resource comes back too.
     *
     * @param name       the profile to reinstall
     * @param projectDir the project to reinstall it in
     * @throws ProfileNotFoundException if the profile, or a profile it extends, has no YAML on disk
     * @throws IOException              if the context file cannot be updated
     */
    public void reinstall(String name, Path projectDir) throws IOException {
        disable(name, projectDir);
        var resolved = profileService.loadAndResolve(name);
        var snapshot = profileApplyService.apply(resolved, projectDir, true, ccxContextService.read(projectDir));
        ccxContextService.recordEnabled(name, snapshot, projectDir);
        log.debug("profile reinstalled name={} projectDir={}", name, projectDir);
    }
}
