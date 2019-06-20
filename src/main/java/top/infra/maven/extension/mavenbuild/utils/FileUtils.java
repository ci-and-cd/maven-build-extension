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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import top.infra.exception.RuntimeIOException;

public class FileUtils {

    private static void copyFile(final String from, final String to) {
        try {
            Files.copy(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            throw new RuntimeIOException(ex);
        }
    }

    /**
     * Check existence of a program in the path.
     * see: https://stackoverflow.com/questions/934191/how-to-check-existence-of-a-program-in-the-path/23539220
     *
     * @param exec executable name
     * @return exec exists
     */
    public static boolean existsInPath(final String exec) {
        // return exec(String.format("type -p %s", exec)).getKey() == 0;
        return Stream.of(System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
            .map(Paths::get)
            .anyMatch(path -> path.resolve(exec).toFile().exists());
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
            return Optional.empty();
        }
    }

    public static boolean writeFile(final Path path, final byte[] bytes, final StandardOpenOption... options) {
        try {
            Files.write(path, bytes, options);
            return true;
        } catch (final IOException ex) {
            return false;
        }
    }
}
