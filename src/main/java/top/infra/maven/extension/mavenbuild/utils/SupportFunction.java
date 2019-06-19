package top.infra.maven.extension.mavenbuild.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.profile.ProfileActivationContext;

public abstract class SupportFunction {

    public static final Pattern PATTERN_GIT_REPO_SLUG = Pattern.compile(".*[:/]([^/]+(/[^/.]+))(\\.git)?");
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

    private static void copyFile(final String from, final String to) {
        try {
            Files.copy(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Gitlab's sub group is not supported intentionally.
     *
     * @param url git remote origin url
     * @return repo slug
     */
    public static Optional<String> gitRepoSlugFromUrl(final String url) {
        final Optional<String> result;

        final Matcher matcher = PATTERN_GIT_REPO_SLUG.matcher(url);
        if (matcher.matches()) {
            result = Optional.ofNullable(matcher.group(1));
        } else {
            result = Optional.empty();
        }

        return result;
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

    public static Properties merge(final Properties properties, final Properties intoProperties) {
        properties.stringPropertyNames().forEach(name -> intoProperties.setProperty(name, properties.getProperty(name)));
        return intoProperties;
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

    public static String os() {
        final String osName = System.getProperty("os.name", "generic").toLowerCase();
        final String result;
        if (osName.contains("mac")) {
            result = "darwin";
        } else if (osName.contains("win")) {
            result = "windows";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            result = "unix";
        } else {
            result = "generic";
        }
        return result;
    }

    /**
     * Provide script execution context variables.
     */
    public static Map<String, Object> projectContext(
        final Model project,
        final ProfileActivationContext context
    ) {
        // Note: keep order.
        final Map<String, Object> bindings = new LinkedHashMap<>();
        // Inject project props: override defaults
        bindings.putAll(context.getProjectProperties());

        bindings.putAll(mapFromProperties(project.getProperties()));
        // Inject system props, override previous.
        bindings.putAll(context.getSystemProperties());
        // Inject user props, override previous.
        bindings.putAll(context.getUserProperties());
        // Expose default variable context.
        bindings.put("value", bindings);
        // Expose resolved pom.xml model.
        bindings.put("project", project);
        return bindings;
    }

    /**
     * Provide script execution context variables.
     */
    static Map<String, Object> mapFromProperties(final Properties properties) {
        final Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }

    /**
     * Extract profile property value.
     */
    private static String propertyValue(final Profile profile) {
        return profile.getActivation().getProperty().getValue();
    }

    public static Optional<String> readFile(final Path path, final Charset encoding) {
        try {
            return Optional.of(new String(Files.readAllBytes(path), encoding));
        } catch (final IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * Report titled activator problem.
     */
    static void reportProblem(
        final String title,
        final Exception error,
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final String message = String.format("%s: project='%s' profile='%s'", title, projectName(context), profileId(profile));
        registerProblem(message, error, profile.getLocation(""), problems);
    }

    /**
     * Extract null-safe profile identity.
     */
    public static String profileId(final Profile profile) {
        return profile == null || profile.getId() == null ? "" : profile.getId();
    }

    /**
     * Extract optional project name from context.
     */
    public static String projectName(ProfileActivationContext context) {
        final String missing = "<missing>";
        final File basedir = context.getProjectDirectory();
        if (basedir == null) {
            return missing;
        }

        final File pomFile = new File(basedir, "pom.xml");
        if (pomFile.exists()) {
            final Model model = readMavenModel(pomFile);
            final String artifactId = model.getArtifactId();
            if (artifactId != null) {
                return artifactId;
            } else {
                return missing;
            }
        } else {
            return basedir.getName();
        }
    }

    /**
     * Fail-safe pom.xml model reader.
     */
    static Model readMavenModel(File pomFile) {
        try (final FileInputStream fileInput = new FileInputStream(pomFile)) {
            final InputStreamReader fileReader = new InputStreamReader(fileInput, StandardCharsets.UTF_8);

            final MavenXpp3Reader pomReader = new MavenXpp3Reader();
            return pomReader.read(fileReader);
        } catch (final Exception ex) {
            return new Model();
        }
    }

    /**
     * Inject new problem in reporter.
     */
    static void registerProblem(
        final String message,
        final Exception error,
        final InputLocation location,
        final ModelProblemCollector problems
    ) {
        final ModelProblemCollectorRequest request = problemRequest()
            .setMessage(message)
            .setException(error)
            .setLocation(location);
        problems.add(request);
    }

    /**
     * Produce default problem descriptor.
     */
    static ModelProblemCollectorRequest problemRequest() {
        return new ModelProblemCollectorRequest(ModelProblem.Severity.ERROR, ModelProblem.Version.BASE);
    }

    public static String stackTrace(final Exception ex) {
        final StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static Properties toProperties(final Map<String, String> map) {
        final Properties result;
        if (map != null) {
            result = new Properties();
            result.putAll(map);
        } else {
            result = null;
        }
        return result;
    }

    public static String toString(final Properties properties, final Pattern pattern) {
        final StringBuilder sb = new StringBuilder(pattern != null ? pattern.pattern() : "").append(System.lineSeparator());

        final String[] names = properties.stringPropertyNames()
            .stream()
            .filter(name -> pattern == null || pattern.matcher(name).matches())
            .sorted()
            .toArray(String[]::new);

        IntStream
            .range(0, names.length)
            .forEach(idx -> {
                if (idx > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(String.format("%03d ", idx));
                final String name = names[idx];
                sb.append(name).append("=").append(properties.getProperty(name));
            });

        return maskSecrets(sb.toString());
    }

    public static String maskSecrets(final String text) {
        return "" + text
            .replaceAll("KEYNAME=.+", "KEYNAME=<secret>")
            .replaceAll("keyname=.+", "keyname=<secret>")
            .replaceAll("ORGANIZATION=.+", "ORGANIZATION=<secret>")
            .replaceAll("organization=.+", "organization=<secret>")
            .replaceAll("PASS=.+", "PASS=<secret>")
            .replaceAll("pass=.+", "pass=<secret>")
            .replaceAll("PASSWORD=.+", "PASSWORD=<secret>")
            .replaceAll("password=.+", "password=<secret>")
            .replaceAll("PASSPHRASE=.+", "PASSPHRASE=<secret>")
            .replaceAll("passphrase=.+", "passphrase=<secret>")
            .replaceAll("TOKEN=.+", "TOKEN=<secret>")
            .replaceAll("token=.+", "token=<secret>")
            .replaceAll("USER=.+", "USER=<secret>")
            .replaceAll("user=.+", "user=<secret>")
            .replaceAll("USERNAME=.+", "USERNAME=<secret>")
            .replaceAll("username=.+", "username=<secret>");
    }

    public static String toString(final Map<String, String> properties, final Pattern pattern) {
        final StringBuilder sb = new StringBuilder(pattern != null ? pattern.pattern() : "").append(System.lineSeparator());

        final String[] names = properties.keySet()
            .stream()
            .filter(name -> pattern == null || pattern.matcher(name).matches())
            .sorted()
            .toArray(String[]::new);

        IntStream
            .range(0, names.length)
            .forEach(idx -> {
                if (idx > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(String.format("%03d ", idx));
                final String name = names[idx];
                sb.append(name).append("=").append(properties.get(name));
            });

        return maskSecrets(sb.toString());
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
