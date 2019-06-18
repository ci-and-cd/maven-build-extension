package top.infra.maven.extension.mavenbuild;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.TimeUnit;
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

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    static final Pattern PATTERN_GIT_REPO_SLUG = Pattern.compile(".*[:/]([^/]+(/[^/.]+))(\\.git)?");

    private static final Pattern PATTERN_SEMANTIC_VERSION_SNAPSHOT = Pattern.compile("^([0-9]+\\.){0,2}[0-9]+-SNAPSHOT$");

    private static final Pattern PATTERN_URL = Pattern.compile("^(.+://|git@)([^/\\:]+(:\\d+)?).*$");

    private SupportFunction() {
    }

    public static String bytesToHex(final byte[] bytes) {
        final char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            final int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
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

    public static Optional<String> domainOrHostFromUrl(final String url) {
        final Optional<String> result;
        final Matcher matcher = PATTERN_URL.matcher(url);
        if (matcher.matches()) {
            result = Optional.ofNullable(matcher.group(2));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    public static Entry<Optional<Integer>, Optional<Exception>> download(
        final Logger logger,
        final String fromUrl,
        final String saveToFile,
        final Map<String, String> headers,
        final int maxTry
    ) {
        // download a file only when it exists
        // see: https://stackoverflow.com/questions/921262/how-to-download-and-save-a-file-from-internet-using-java

        Exception lastException = null;
        Integer lastStatus = null;

        int count = 0;
        while (count < maxTry) {
            count++;

            String newUrl = null;
            InputStream inputStream = null;
            try {
                final URL source = new URL(fromUrl);
                final HttpURLConnection urlConnection = (HttpURLConnection) source.openConnection();
                urlConnection.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(10L));
                urlConnection.setReadTimeout((int) TimeUnit.SECONDS.toMillis(20L));

                if (headers != null && headers.size() > 0) {
                    headers.forEach(urlConnection::setRequestProperty);
                }

                inputStream = urlConnection.getInputStream();
                final int status = urlConnection.getResponseCode();

                lastException = null;
                lastStatus = status;

                final boolean redirect = lastStatus == HttpURLConnection.HTTP_MOVED_TEMP
                    || lastStatus == HttpURLConnection.HTTP_MOVED_PERM
                    || lastStatus == HttpURLConnection.HTTP_SEE_OTHER;

                if (redirect) {
                    newUrl = urlConnection.getHeaderField("Location");
                    logger.info(String.format("Download redirect ('%s' to '%s'). %s", fromUrl, newUrl, status));
                } else {
                    newUrl = null;
                    logger.info(String.format("Download result ('%s' to '%s'). %s", fromUrl, saveToFile, status));
                }
            } catch (final java.io.FileNotFoundException ex) {
                logger.warn(String.format("Download error ('%s' to '%s'). %s", fromUrl, saveToFile, "Not found"));

                lastException = null;
                lastStatus = 404;
            } catch (final java.net.SocketTimeoutException ex) {
                logger.warn(String.format("Download timeout ('%s' to '%s'). %s", fromUrl, saveToFile, ex.getMessage()));

                lastException = ex;
                lastStatus = null;
            } catch (final Exception ex) {
                logger.warn(String.format("Download error ('%s' to '%s'). %s", fromUrl, saveToFile, ex.getMessage()), ex);

                lastException = ex;
                lastStatus = null;
            }

            if (inputStream != null) {
                if (newUrl != null) {
                    return download(logger, newUrl, saveToFile, headers, maxTry);
                } else if (is2xxStatus(lastStatus)) {
                    final File saveToDir = Paths.get(saveToFile).toFile().getParentFile();
                    try {
                        if (!saveToDir.exists()) {
                            Files.createDirectories(Paths.get(saveToDir.toString()));
                        }
                    } catch (final IOException ex) {
                        return newTupleOptional(lastStatus, ex);
                    }

                    try (final ReadableByteChannel rbc = Channels.newChannel(inputStream)) {
                        try (final FileOutputStream fos = new FileOutputStream(saveToFile)) {
                            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                        }
                        return newTupleOptional(lastStatus, null);
                    } catch (final IOException ex) {
                        return newTupleOptional(lastStatus, ex);
                    }
                } else if (lastStatus != null && !is5xxStatus(lastStatus)) {
                    return newTupleOptional(lastStatus, null);
                }
            } else if (lastStatus != null && lastStatus == 404) {
                return newTupleOptional(lastStatus, null);
            }
        }

        return newTupleOptional(lastStatus, lastException);
    }

    public static Entry<Integer, String> exec(final String command) {
        try {
            final Process proc = Runtime.getRuntime().exec(command);
            return execResult(proc);
        } catch (final IOException ex) {
            return newTuple(-1, "");
        }
    }

    public static Entry<Integer, String> exec(
        final Map<String, String> environment,
        final String stdIn,
        final List<String> command
    ) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (environment != null) {
            processBuilder.environment().putAll(environment);
        }
        try {
            final Process proc = processBuilder.start();
            if (stdIn != null) {
                try (final PrintWriter writer = new PrintWriter(proc.getOutputStream())) {
                    writer.println(stdIn);
                    writer.flush();
                }
            }
            return execResult(proc);
        } catch (final IOException ex) {
            return newTuple(-1, "");
        }
    }

    private static Entry<Integer, String> execResult(final Process proc) {
        try {
            final String result = new BufferedReader(new InputStreamReader(proc.getInputStream()))
                .lines()
                .collect(Collectors.joining("\n"));
            return newTuple(proc.waitFor(), result);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return newTuple(-1, "");
        }
    }

    /**
     * Check existence of a program in the path.
     * see: https://stackoverflow.com/questions/934191/how-to-check-existence-of-a-program-in-the-path/23539220
     *
     * @param exec executable name
     * @return exec exists
     */
    static boolean existsInPath(final String exec) {
        // return exec(String.format("type -p %s", exec)).getKey() == 0;
        return Stream.of(System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
            .map(Paths::get)
            .anyMatch(path -> path.resolve(exec).toFile().exists());
    }

    static List<String> find(final String path, final String name) {
        return org.unix4j.Unix4j.find(path, name).toStringList();
    }

    /**
     * Gitlab's sub group is not supported intentionally.
     *
     * @param url git remote origin url
     * @return repo slug
     */
    static Optional<String> gitRepoSlugFromUrl(final String url) {
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

    public static boolean is2xxStatus(final Integer status) {
        return status != null && status >= 200 && status < 300;
    }

    public static boolean is404Status(final Integer status) {
        return status != null && status == 404;
    }

    public static boolean is5xxStatus(final Integer status) {
        return status != null && status >= 500 && status < 600;
    }

    public static boolean isEmpty(final String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isNotEmpty(final String str) {
        return str != null && !str.isEmpty();
    }

    static boolean isSemanticSnapshotVersion(final String version) {
        return version != null && PATTERN_SEMANTIC_VERSION_SNAPSHOT.matcher(version).matches();
    }

    static List<String> lines(final String cmdOutput) {
        return Arrays.stream(("" + cmdOutput).split("\\r?\\n"))
            .map(line -> line.replaceAll("\\s+", " "))
            .filter(SupportFunction::isNotEmpty)
            .collect(Collectors.toList());
    }

    /**
     * Provide script execution context variables.
     */
    static Map<String, Object> mapFromProperties(final Properties properties) {
        final Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
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

    static Optional<Integer> parseJavaVersion(final String javaVersion) {
        final Optional<Integer> result;

        if (javaVersion.matches("1.\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[1]));
        } else if (javaVersion.matches("\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[0]));
        } else {
            result = Optional.empty();
        }

        return result;
    }

    public static String pathname(final File file) {
        try {
            return file.getCanonicalPath();
        } catch (final IOException ex) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Extract null-safe profile identity.
     */
    static String profileId(final Profile profile) {
        return profile == null || profile.getId() == null ? "" : profile.getId();
    }

    /**
     * Extract optional project name from context.
     */
    static String projectName(ProfileActivationContext context) {
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
     * Provide script execution context variables.
     */
    static Map<String, Object> projectContext(
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
     * Extract profile property value.
     */
    private static String propertyValue(final Profile profile) {
        return profile.getActivation().getProperty().getValue();
    }

    /**
     * Produce default problem descriptor.
     */
    static ModelProblemCollectorRequest problemRequest() {
        return new ModelProblemCollectorRequest(ModelProblem.Severity.ERROR, ModelProblem.Version.BASE);
    }

    public static Optional<String> readFile(final Path path, final Charset encoding) {
        try {
            return Optional.of(new String(Files.readAllBytes(path), encoding));
        } catch (final IOException ex) {
            return Optional.empty();
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

    public static String stackTrace(final Exception ex) {
        final StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static Optional<Integer> systemJavaVersion() {
        final String systemJavaVersion = System.getProperty("java.version");
        return parseJavaVersion(systemJavaVersion);
    }

    static String systemUserHome() {
        final String pathname = System.getProperty("user.home", ".");
        return pathname(new File(pathname));
    }

    static String systemUserDir() {
        final String pathname = System.getProperty("user.dir", ".");
        return pathname(new File(pathname));
    }

    static String systemJavaIoTmp() {
        final String pathname = System.getProperty("java.io.tmpdir", "/tmp");
        return pathname(new File(pathname));
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

    public static Optional<String> urlWithoutPath(final String url) {
        final Optional<String> result;
        final Matcher matcher = PATTERN_URL.matcher(url);
        if (matcher.matches()) {
            result = Optional.of(matcher.group(1) + matcher.group(2));
        } else {
            result = Optional.empty();
        }
        return result;
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
