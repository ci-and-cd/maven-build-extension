package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.SupportFunction.find;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.lines;
import static top.infra.maven.extension.mavenbuild.SupportFunction.notEmpty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Docker {

    private static final Pattern PATTERN_BASE_IMAGE = Pattern.compile("^FROM[ ]+.+$");

    private static final Pattern PATTERN_FILE_WITH_EXT = Pattern.compile(".+/.+\\..+");

    private final Logger logger;
    private final Map<String, String> environment;
    private final String homeDir;
    private final String registry;
    private final String registryPass;
    private final String registryUrl;
    private final String registryUser;

    public Docker(
        final Logger logger,
        final String dockerHost,
        final String homeDir,
        final String registry,
        final String registryPass,
        final String registryUrl,
        final String registryUser
    ) {
        this.logger = logger;
        this.environment = environment(dockerHost, registry);
        this.homeDir = homeDir;

        this.registry = registry;
        this.registryPass = registryPass;
        this.registryUrl = registryUrl;
        this.registryUser = registryUser;
    }

    public void cleanOldImages() {
        final Entry<Integer, String> dockerImages = this.docker("images");

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Found dockerImages %s %s", dockerImages.getKey(), dockerImages.getValue()));
        }

        if (dockerImages.getKey() == 0) {
            final List<String> imageIds = imagesToClean(lines(dockerImages.getValue()));

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Found imageIds %s", imageIds));
            }

            imageIds.forEach(imageId -> {
                logger.info(String.format("Delete old image %s", imageId));
                final Entry<Integer, String> dockerRmi = this.docker("rmi", imageId);
                if (dockerRmi.getKey() != 0) {
                    logger.warn(String.format("Error on remove image %s", imageId));
                }
            });
        }
    }

    public void initDockerConfig() {
        this.docker("version");

        // TODO config docker log rotation
        final File dockerConfigDir = new File(this.homeDir + ".docker");
        if (!dockerConfigDir.exists()) {
            dockerConfigDir.mkdirs();
        }
    }

    public void pullBaseImage() {
        logger.info(">>>>>>>>>> ---------- pull_base_image ---------- >>>>>>>>>>");
        final List<String> dockerfiles = dockerfiles();

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Found dockerfiles %s", dockerfiles));
        }

        final List<String> baseImages = baseImages(dockerfiles);

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Found baseImages %s", baseImages));
        }

        baseImages.forEach(image -> this.docker("pull", image));
        logger.info("<<<<<<<<<< ---------- pull_base_image ---------- <<<<<<<<<<");
    }

    private Entry<Integer, String> docker(final String... options) {
        return SupportFunction.exec(this.environment, null, dockerCommand(options));
    }

    public void dockerLogin() {
        if (isNotEmpty(this.registry)) {
            this.dockerLogin(this.registry);
        } else {
            this.dockerLogin(this.registryUrl);
        }
    }

    private void dockerLogin(final String toRegistry) {
        if (isNotEmpty(this.registryPass) && isNotEmpty(this.registryUser)) {
            if (isNotEmpty(toRegistry)) {
                if (toRegistry.startsWith("https://")) {
                    logger.info(String.format("docker logging into secure registry %s", toRegistry));
                } else {
                    logger.info(String.format("docker logging into insecure registry %s", toRegistry));
                }

                final List<String> command = dockerCommand("login", "--password-stdin", "-u=" + this.registryUser, toRegistry);
                final Entry<Integer, String> resultDockerLogin = SupportFunction.exec(this.environment, this.registryPass, command);
                logger.info(String.format("docker login [%s] result [%s]", toRegistry, resultDockerLogin.getKey()));
            }
        } else {
            logger.info(String.format("skip docker login [%s]", toRegistry));
        }
    }

    static List<String> baseImages(final List<String> dockerfiles) {
        return dockerfiles
            .stream()
            .map(Docker::baseImageOf)
            .filter(SupportFunction::isNotEmpty)
            .map(line -> line.replaceAll("\\s+", " "))
            .filter(SupportFunction::isNotEmpty)
            .map(line -> line.split("\\s+"))
            .filter(value -> value.length > 1)
            .map(value -> value[1])
            .distinct()
            .collect(Collectors.toList());
    }

    static String baseImageOf(final String dockerfile) {
        try (final Stream<String> stream = Files.lines(Paths.get(dockerfile))) {
            return stream.filter(line -> PATTERN_BASE_IMAGE.matcher(line).matches()).findFirst().orElse(null);
        } catch (final IOException ex) {
            return null;
        }
    }

    static List<String> dockerCommand(final String... options) {
        return SupportFunction.asList(new String[]{"docker"}, options);
    }

    static List<String> dockerfiles() {
        return find(".", "*Docker*")
            .stream()
            .filter(line -> !line.contains("/target/classes/"))
            .filter(line -> !PATTERN_FILE_WITH_EXT.matcher(line).matches())
            .collect(Collectors.toList());
    }

    public static Optional<String> dockerHost(final Properties systemProperties) {
        return Optional.ofNullable(systemProperties.getProperty("env.DOCKER_HOST"));
    }

    static Map<String, String> environment(final String dockerHost, final String registry) {
        final Map<String, String> result = new LinkedHashMap<>();

        if (notEmpty(dockerHost)) {
            result.put("DOCKER_HOST", dockerHost);
        }

        if (notEmpty(registry) && !registry.startsWith("https://")) {
            result.put("DOCKER_OPTS", String.format("–insecure-registry %s", registry));
        }

        return result;
    }

    static List<String> imagesToClean(final List<String> dockerImages) {
        return dockerImages
            .stream()
            .filter(line -> line.contains("<none>"))
            .map(line -> line.split("\\s+"))
            .filter(value -> value.length > 2)
            .map(value -> value[2])
            .filter(value -> !"IMAGE".equals(value))
            .collect(Collectors.toList());
    }
}
