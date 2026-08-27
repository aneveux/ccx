package me.neveux.ccx.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Utility methods for safe file I/O operations.
 *
 * <p>The primary operation is {@link #atomicWrite}, which ensures that a write either
 * completes fully or leaves the pre-existing file untouched — a partial write is never
 * visible to readers. All ccx services that touch the filesystem use this pattern.
 */
public final class FileUtils {

    private FileUtils() {}

    /**
     * Functional interface for a write operation that may throw {@link IOException}.
     *
     * @param <T> the type of the value passed to the consumer
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }

    /**
     * Convenience overload of {@link #atomicWrite(Path, ThrowingConsumer)} that writes a
     * string directly.
     *
     * @param target  the file to write to
     * @param content the content to write
     * @throws IOException if writing or moving the temp file fails
     */
    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, tmp -> Files.writeString(tmp, content));
    }

    /**
     * Writes {@code target} atomically: the {@code writer} populates a freshly created scratch
     * file in the same directory, which is then moved into place with
     * {@link StandardCopyOption#ATOMIC_MOVE}. If the writer or the move fails, the scratch file is
     * removed so a failed write never leaves an orphan behind, and the pre-existing target stays
     * untouched.
     *
     * <p>The scratch file is created by {@link Files#createTempFile(Path, String, String,
     * java.nio.file.attribute.FileAttribute...)}, which fails rather than following an existing
     * entry at the same path. A predictable {@code <target>.tmp} sibling would let anything that
     * can plant a symlink there — a checked-out repository, another process — redirect the write
     * to an arbitrary file, and would also make two concurrent writes to the same target collide.
     */
    public static void atomicWrite(Path target, ThrowingConsumer<Path> writer) throws IOException {
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var directory = parent != null ? parent : Path.of(".");
        var tmp = Files.createTempFile(directory, target.getFileName() + ".", ".tmp");
        try {
            writer.accept(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
