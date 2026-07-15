package me.neveux.ccx.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathSecurity — path boundary checks")
class PathSecurityTest {

    @Test
    @DisplayName("isAllowed permits paths under user.home")
    void isAllowed_permitsHomePath() {
        var homePath = Path.of(System.getProperty("user.home")).resolve("projects/foo");
        assertThat(PathSecurity.isAllowed(homePath)).isTrue();
    }

    @Test
    @DisplayName("isAllowed permits paths under java.io.tmpdir")
    void isAllowed_permitsTmpPath() {
        var tmpPath = Path.of(System.getProperty("java.io.tmpdir")).resolve("work/session");
        assertThat(PathSecurity.isAllowed(tmpPath)).isTrue();
    }

    @Test
    @DisplayName("isAllowed rejects /etc/passwd")
    void isAllowed_rejectsEtcPasswd() {
        assertThat(PathSecurity.isAllowed(Path.of("/etc/passwd"))).isFalse();
    }

    @Test
    @DisplayName("isAllowed rejects path normalized to /etc")
    void isAllowed_rejectsNormalizedEscape() {
        var home = System.getProperty("user.home");
        var escape = Path.of(home + "/../../etc/hosts");
        // After normalize() this becomes /etc/hosts — outside all allowed roots
        assertThat(PathSecurity.isAllowed(escape)).isFalse();
    }

    @Test
    @DisplayName("isAllowed permits paths under user.dir (project working directory)")
    void isAllowed_permitsProjectDir() {
        var projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        assertThat(PathSecurity.isAllowed(projectDir)).isTrue();
    }

    @Test
    @DisplayName("resolveAndCheck allows an existing path inside java.io.tmpdir")
    void resolveAndCheck_allowsExistingPathInsideHome(@TempDir Path tempDir) throws Exception {
        var realFile = tempDir.resolve("allowed.txt");
        Files.writeString(realFile, "content");

        assertThat(PathSecurity.resolveAndCheck(realFile)).isTrue();
    }

    @Test
    @DisplayName("resolveAndCheck returns false for a path that does not exist")
    void resolveAndCheck_returnsFalseForNonExistentPath() {
        var nonExistent = Path.of("/nonexistent/path/that/does/not/exist");

        assertThat(PathSecurity.resolveAndCheck(nonExistent)).isFalse();
    }

    @Test
    @DisplayName("isAllowed returns false for null input")
    void isAllowed_returnsFalseForNull() {
        assertThat(PathSecurity.isAllowed(null)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("resolveAndCheck returns false for a symlink pointing outside allowed roots")
    void resolveAndCheck_returnsFalseForSymlinkEscape(@TempDir Path tempDir) throws Exception {
        var link = tempDir.resolve("escape-link");
        java.nio.file.Files.createSymbolicLink(link, Path.of("/etc"));
        // The symlink resolves to /etc which is outside all allowed roots
        assertThat(PathSecurity.resolveAndCheck(link))
            .as("resolveAndCheck must return false for a symlink escaping to /etc")
            .isFalse();
    }

    @Test
    @DisplayName("isAllowed rejects a non-existent path that normalizes OUTSIDE all roots")
    void isAllowed_when_nonExistentNormalizesOutside_then_rejected() {
        // toRealPath() throws (path absent) → fallback to toAbsolutePath().normalize().
        // The .. segments climb out of home and land on /etc/passwd, outside every root.
        var nonExistent = Path.of(System.getProperty("user.home"),
            "nonexistent-xyz", "..", "..", "..", "..", "etc", "passwd");

        assertThat(java.nio.file.Files.exists(nonExistent))
            .as("guard: the constructed path must not exist so the toRealPath fallback is exercised")
            .isFalse();
        assertThat(PathSecurity.isAllowed(nonExistent))
            .as("a non-existent path that normalizes to /etc/passwd must be rejected via the normalize fallback")
            .isFalse();
    }

    @Test
    @DisplayName("isAllowed accepts a non-existent path that normalizes INSIDE a root (new-project case)")
    void isAllowed_when_nonExistentNormalizesInside_then_accepted() {
        // The brand-new project case: the dir does not exist yet, but normalizes under home.
        var nonExistent = Path.of(System.getProperty("user.home"), "brand-new-project-xyz", ".claude");

        assertThat(java.nio.file.Files.exists(nonExistent))
            .as("guard: the constructed path must not exist so the toRealPath fallback is exercised")
            .isFalse();
        assertThat(PathSecurity.isAllowed(nonExistent))
            .as("a non-existent path normalizing under user.home must be accepted (new project dir)")
            .isTrue();
    }

    @Test
    @DisplayName("resolveAndCheck rejects the SAME non-existent inside-root path that isAllowed accepts")
    void resolveAndCheck_when_nonExistentInsideRoot_then_rejected() {
        // Documents the deliberate isAllowed-vs-resolveAndCheck contract difference:
        // isAllowed has a normalize fallback for absent paths; resolveAndCheck does not.
        var nonExistent = Path.of(System.getProperty("user.home"), "brand-new-project-xyz", ".claude");

        assertThat(PathSecurity.isAllowed(nonExistent))
            .as("isAllowed accepts the absent-but-inside-root path")
            .isTrue();
        assertThat(PathSecurity.resolveAndCheck(nonExistent))
            .as("resolveAndCheck rejects it because toRealPath throws and there is no fallback")
            .isFalse();
    }
}
