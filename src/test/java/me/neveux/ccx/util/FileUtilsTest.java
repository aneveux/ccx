package me.neveux.ccx.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileUtils")
class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("should write file with correct content")
    void atomicWrite_writesFileWithCorrectContent() throws Exception {
        var target = tempDir.resolve("output.txt");

        FileUtils.atomicWrite(target, "hello world");

        assertThat(Files.readString(target)).isEqualTo("hello world");
    }

    @Test
    @DisplayName("should create parent directories when absent")
    void atomicWrite_createsParentDirectoriesWhenAbsent() throws Exception {
        var target = tempDir.resolve("subdir/file.txt");

        FileUtils.atomicWrite(target, "content");

        assertThat(target).exists();
    }

    @Test
    @DisplayName("should leave no .tmp sibling after successful write")
    void atomicWrite_noTmpFileRemainsAfterSuccess() throws Exception {
        var target = tempDir.resolve("output.txt");

        FileUtils.atomicWrite(target, "hello world");

        try (var stream = Files.list(tempDir)) {
            var tmpFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .toList();
            assertThat(tmpFiles)
                .as("no .tmp file must remain after a successful atomicWrite")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("should replace existing file content on second write")
    void atomicWrite_replacesExistingFile() throws Exception {
        var target = tempDir.resolve("output.txt");

        FileUtils.atomicWrite(target, "old");
        FileUtils.atomicWrite(target, "new");

        assertThat(Files.readString(target)).isEqualTo("new");
    }

    @Test
    @DisplayName("should leave an existing file's permissions untouched")
    void atomicWrite_preservesExistingFilePermissions() throws Exception {
        assumePosix();
        var target = tempDir.resolve("settings.json");
        Files.writeString(target, "old");
        var groupAndOtherReadable = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(target, groupAndOtherReadable);

        FileUtils.atomicWrite(target, "new");

        assertThat(Files.getPosixFilePermissions(target))
            .as("a rewrite must not change the mode a user or administrator chose")
            .isEqualTo(groupAndOtherReadable);
    }

    @Test
    @DisplayName("should leave a deliberately private file private on rewrite")
    void atomicWrite_preservesOwnerOnlyPermissions() throws Exception {
        assumePosix();
        var target = tempDir.resolve("secret.json");
        Files.writeString(target, "old");
        var ownerOnly = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(target, ownerOnly);

        FileUtils.atomicWrite(target, "new");

        assertThat(Files.getPosixFilePermissions(target))
            .as("a rewrite must never widen a tightened file")
            .isEqualTo(ownerOnly);
    }

    @Test
    @DisplayName("should create a new file with the mode a plain create would produce")
    void atomicWrite_newFileGetsUmaskDerivedPermissions() throws Exception {
        assumePosix();
        var reference = Files.createFile(tempDir.resolve("reference.txt"));
        var referencePermissions = Files.getPosixFilePermissions(reference);
        var target = tempDir.resolve("fresh.json");

        FileUtils.atomicWrite(target, "content");

        assertThat(Files.getPosixFilePermissions(target))
            .as("a fresh file must carry the process umask, not createTempFile's owner-only mode")
            .isEqualTo(referencePermissions);
        Assumptions.assumeTrue(referencePermissions.contains(PosixFilePermission.OTHERS_READ),
            "umask hides group/other read on this machine");
        assertThat(Files.getPosixFilePermissions(target))
            .contains(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    }

    @Test
    @DisplayName("should leave no permission-probe file behind")
    void atomicWrite_noProbeFileRemains() throws Exception {
        assumePosix();

        FileUtils.atomicWrite(tempDir.resolve("output.txt"), "content");

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(path -> path.getFileName().toString()))
                .as("the umask probe must be removed")
                .noneMatch(name -> name.startsWith(".ccx-mode-probe"));
        }
    }

    @Test
    @DisplayName("should create a new secret-bearing file owner-only regardless of the umask")
    void atomicWriteOwnerOnly_newFileIsOwnerOnly() throws Exception {
        assumePosix();
        var target = tempDir.resolve("settings.json");

        FileUtils.atomicWriteOwnerOnly(target, tmp -> Files.writeString(tmp, "{\"env\":{\"TOKEN\":\"s3cret\"}}"));

        assertThat(Files.getPosixFilePermissions(target))
            .as("a file that can carry MCP credentials must not be created group- or world-readable")
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("should leave an existing wider mode alone rather than silently tightening it")
    void atomicWriteOwnerOnly_preservesExistingWiderMode() throws Exception {
        assumePosix();
        var target = tempDir.resolve("settings.json");
        Files.writeString(target, "old");
        var groupWritable = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(target, groupWritable);

        FileUtils.atomicWriteOwnerOnly(target, tmp -> Files.writeString(tmp, "new"));

        assertThat(Files.getPosixFilePermissions(target))
            .as("ccx reports a too-wide existing file via doctor; it never chmods one behind the user's back")
            .isEqualTo(groupWritable);
        assertThat(Files.readString(target)).isEqualTo("new");
    }

    @Test
    @DisplayName("should leave no scratch file behind after an owner-only write")
    void atomicWriteOwnerOnly_leavesNoScratchFile() throws Exception {
        assumePosix();

        FileUtils.atomicWriteOwnerOnly(tempDir.resolve("settings.json"), tmp -> Files.writeString(tmp, "{}"));

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(path -> path.getFileName().toString()))
                .as("only the finished file may remain")
                .containsExactly("settings.json");
        }
    }

    private void assumePosix() {
        Assumptions.assumeTrue(
            Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) != null,
            "filesystem has no POSIX permission support");
    }
}
