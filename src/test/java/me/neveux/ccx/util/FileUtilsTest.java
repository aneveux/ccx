package me.neveux.ccx.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileUtils.atomicWrite")
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
}
