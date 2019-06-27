package top.infra.maven.extension.mavenbuild.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import top.infra.maven.exception.RuntimeIOException;

public class FileUtils {

    private static void copyFile(final String from, final String to) {
        try {
            Files.copy(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            throw new RuntimeIOException(ex);
        }
    }

    public static String createDirectories(final String pathname) {
        final Path path = Paths.get(pathname);
        try {
            Files.createDirectories(path);
        } catch (final IOException ex) {
            throw new RuntimeIOException(String.format("Error create directory '%s'. %s", pathname, ex.getMessage()), ex);
        }
        return pathname;
    }

    public static List<String> find(final String path, final String name) {
        return org.unix4j.Unix4j.find(path, name).toStringList();
    }

    public static String pathname(final File file) {
        try {
            return file.getCanonicalPath();
        } catch (final IOException ex) {
            return file.getAbsolutePath();
        }
    }

    public static Optional<String> readFile(final Path path, final Charset encoding) {
        try {
            return Optional.of(new String(Files.readAllBytes(path), encoding));
        } catch (final IOException ex) {
            throw new RuntimeIOException(ex);
        }
    }

    public static boolean writeFile(final Path path, final byte[] bytes, final StandardOpenOption... options) {
        try {
            Files.write(path, bytes, options);
            return true;
        } catch (final IOException ex) {
            throw new RuntimeIOException(ex);
        }
    }
}
