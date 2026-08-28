package me.neveux.ccx.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Utility methods for safe file I/O operations.
 *
 * <p>The primary operation is {@link #atomicWrite}, which ensures that a write either
 * completes fully or leaves the pre-existing file untouched — a partial write is never
 * visible to readers. All ccx services that touch the filesystem use this pattern.
 *
 * <p>Two write modes exist, differing only in the mode a *newly created* file gets:
 * {@link #atomicWrite} leaves it to the process umask, {@link #atomicWriteOwnerOnly} forces
 * owner-only for a file that can carry credentials. Neither ever changes the mode of a file
 * that already exists.
 */
public final class FileUtils {

    /** Mode 600 — what a file that can hold MCP credentials is created with. */
    private static final Set<PosixFilePermission> OWNER_ONLY =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** The mode a brand-new target is created with; irrelevant when the target already exists. */
    private enum NewFileMode { UMASK_DEFAULT, OWNER_ONLY }

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
     * @throws IOException if writing or moving the scratch file fails
     */
    public static void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, scratch -> Files.writeString(scratch, content));
    }

    /**
     * Writes {@code target} atomically: the {@code writer} populates a freshly created scratch
     * file in the same directory, which is then moved into place with
     * {@link StandardCopyOption#ATOMIC_MOVE}. If the writer or the move fails, the scratch file is
     * removed so a failed write never leaves an orphan behind, and the pre-existing target stays
     * untouched.
     *
     * <p>A file created by this method carries the mode a plain {@code open(O_CREAT)} would have
     * produced — 0666 masked by the process umask — because that is exactly what the scratch file
     * is created with. Use {@link #atomicWriteOwnerOnly} for a target whose contents are secret.
     *
     * @param target the file to write to
     * @param writer populates the scratch file it is handed; must not assume the final path
     * @throws IOException if writing or moving the scratch file fails
     */
    public static void atomicWrite(Path target, ThrowingConsumer<Path> writer) throws IOException {
        write(target, writer, NewFileMode.UMASK_DEFAULT);
    }

    /**
     * Same contract as {@link #atomicWrite(Path, ThrowingConsumer)}, except that a target being
     * created for the first time is created mode 600 rather than at the umask default.
     *
     * <p>For files ccx writes that can hold credentials: {@code .claude/settings.json} round-trips
     * MCP {@code env} and {@code headers} blocks, and {@code .ccx/context.json} stores profile
     * {@code env} values. Under a common {@code umask 002} the default would be 664 — group-writable
     * and world-readable.
     *
     * <p>Deliberately does <em>not</em> tighten a target that already exists: narrowing a file the
     * user or an administrator widened on purpose would break a shared-group or container setup
     * silently. {@code ccx doctor} reports such a file instead.
     *
     * @param target the file to write to
     * @param writer populates the scratch file it is handed; must not assume the final path
     * @throws IOException if writing or moving the scratch file fails
     */
    public static void atomicWriteOwnerOnly(Path target, ThrowingConsumer<Path> writer) throws IOException {
        write(target, writer, NewFileMode.OWNER_ONLY);
    }

    private static void write(Path target, ThrowingConsumer<Path> writer, NewFileMode newFileMode)
            throws IOException {
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var directory = parent != null ? parent : Path.of(".");
        var scratch = createScratchFile(directory, target, newFileMode);
        try {
            writer.accept(scratch);
            Files.move(scratch, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Creates the scratch file the write is staged in, already carrying the mode the finished file
     * is meant to have — the mode survives the {@link StandardCopyOption#ATOMIC_MOVE} that puts it
     * in place, and passing it at creation leaves no window in which the file is briefly wider.
     *
     * <p>The name is unpredictable and creation is exclusive ({@link Files#createFile} fails rather
     * than following an entry already at that path). A predictable {@code <target>.tmp} sibling
     * would let anything that can plant a symlink there — a checked-out repository, another
     * process — redirect the write to an arbitrary file, and would make two concurrent writes to
     * the same target collide.
     *
     * <p>Passing no mode is what produces the umask default: the kernel applies the umask to 0666
     * itself, so nothing here needs to know what the umask is.
     *
     * <p>A mode passed at creation is filtered through the umask too ({@code open(2)} applies
     * {@code mode & ~umask}), so reproducing an existing file's mode exactly takes a {@code chmod}
     * after the fact — under {@code umask 022} the creation attribute alone silently drops the
     * group-write bit of an existing {@code 664} file. Creating narrow and widening afterwards
     * keeps the ordering safe: the scratch file is never wider than the mode the target already has.
     */
    private static Path createScratchFile(Path directory, Path target, NewFileMode newFileMode)
            throws IOException {
        var scratch = directory.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createFile(scratch);
        }
        var existingMode = existingMode(target);
        if (existingMode.isPresent()) {
            var mode = existingMode.get();
            var created = Files.createFile(scratch, PosixFilePermissions.asFileAttribute(mode));
            Files.setPosixFilePermissions(created, mode);
            return created;
        }
        if (newFileMode == NewFileMode.OWNER_ONLY) {
            return Files.createFile(scratch, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        return Files.createFile(scratch);
    }

    /** Empty when {@code target} does not exist yet, which is what distinguishes create from rewrite. */
    private static Optional<Set<PosixFilePermission>> existingMode(Path target) throws IOException {
        try {
            return Optional.of(Files.getPosixFilePermissions(target));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }
}
