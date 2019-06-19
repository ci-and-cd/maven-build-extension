package top.infra.maven.extension.mavenbuild.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class SupportFunction {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final Pattern PATTERN_SEMANTIC_VERSION_SNAPSHOT = Pattern.compile("^([0-9]+\\.){0,2}[0-9]+-SNAPSHOT$");

    private SupportFunction() {
    }

    public static List<String> asList(final String[] array1, final String... array2) {
        return Arrays.asList(concat(array1, array2));
    }

    public static String[] concat(final String[] array1, final String... array2) {
        return Stream.of(array1, array2).flatMap(Stream::of).toArray(String[]::new);
        // return Stream.concat(Arrays.stream(array1), Arrays.stream(array2)).toArray(String[]::new);
    }

    public static boolean exists(final Path path) {
        return path != null && path.toFile().exists();
    }

    public static boolean isEmpty(final String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isSemanticSnapshotVersion(final String version) {
        return version != null && PATTERN_SEMANTIC_VERSION_SNAPSHOT.matcher(version).matches();
    }

    public static List<String> lines(final String cmdOutput) {
        return Arrays.stream(("" + cmdOutput).split("\\r?\\n"))
            .map(line -> line.replaceAll("\\s+", " "))
            .filter(SupportFunction::isNotEmpty)
            .collect(Collectors.toList());
    }

    public static boolean isNotEmpty(final String str) {
        return str != null && !str.isEmpty();
    }

    public static <F, S> Entry<F, S> newTuple(final F first, final S second) {
        return new AbstractMap.SimpleImmutableEntry<>(first, second);
    }

    public static <F, S> Entry<Optional<F>, Optional<S>> newTupleOptional(final F first, final S second) {
        return new AbstractMap.SimpleImmutableEntry<>(Optional.ofNullable(first), Optional.ofNullable(second));
    }

    public static boolean notEmpty(final String str) {
        return str != null && !str.isEmpty();
    }

    public static String stackTrace(final Exception ex) {
        final StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static String uniqueKey() {
        try {
            final MessageDigest salt = MessageDigest.getInstance("SHA-256");
            salt.update(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(salt.digest());
        } catch (final NoSuchAlgorithmException ex) {
            throw new UnsupportedOperationException(ex);
        }
    }

    private static String bytesToHex(final byte[] bytes) {
        final char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            final int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
