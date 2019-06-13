package top.infra.maven.extension.mavenbuild;

import static java.util.Collections.singletonMap;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_PASS;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_USER;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exec;
import static top.infra.maven.extension.mavenbuild.SupportFunction.notEmpty;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.unix4j.Unix4j;

public class Docker {

    private static final String DOCKER_EXECUTABLE = "docker";
    private static final String ENV_VAR_DOCKER_HOST = "DOCKER_HOST";

    private final Logger logger;
    private final CiOptionAccessor ciOpts;
    private final String homeDir;

    public Docker(
        final Logger logger,
        final CiOptionAccessor ciOpts,
        final String homeDir
    ) {
        this.logger = logger;
        this.ciOpts = ciOpts;
        this.homeDir = homeDir;
    }

    private void initDockerConfig() {
        // TODO config docker log rotation
        final File dockerConfigDir = new File(this.homeDir + ".docker");
        if (!dockerConfigDir.exists()) {
            dockerConfigDir.mkdirs();
        }

        final Optional<String> host = this.ciOpts.dockerHost();
        final Optional<String> registry = this.ciOpts.getOption(DOCKER_REGISTRY);
        final Optional<String> registryPass = this.ciOpts.getOption(DOCKER_REGISTRY_PASS);
        final Optional<String> registryUser = this.ciOpts.getOption(DOCKER_REGISTRY_USER);
        if (registry.isPresent() && registryPass.isPresent() && registryUser.isPresent()) {
            dockerLogin(logger, host.orElse(null), registry.get(), registryUser.get(), registryPass.get());
            logger.info("docker login done");
        } else {
            logger.info("skip docker login");
        }
    }

    public void cleanOldImages() {
        if (this.ciOpts.docker()) {
            final Map<String, String> env = this.ciOpts.dockerHost()
                .map(host -> singletonMap(ENV_VAR_DOCKER_HOST, host)).orElse(null);
            exec(env, null, DOCKER_EXECUTABLE, "version");

            this.initDockerConfig();

            final Map.Entry<Integer, String> dockerImages = exec(env, null, DOCKER_EXECUTABLE, "images");

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Found dockerImages %s %s", dockerImages.getKey(), dockerImages.getValue()));
            }

            if (dockerImages.getKey() == 0) {
                final List<String> imageIds = imagesToClean(lines(dockerImages.getValue()));

                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Found imageIds %s", imageIds));
                }

                imageIds.forEach(imageId -> {
                    logger.info(String.format("Delete old image %s", imageId));
                    final Map.Entry<Integer, String> dockerRmi = exec(env, null, DOCKER_EXECUTABLE, "rmi", imageId);
                    if (dockerRmi.getKey() != 0) {
                        logger.warn(String.format("Error on remove image %s", imageId));
                    }
                });
            }
        }
    }

    private static final Pattern PATTERN_FILE_WITH_EXT = Pattern.compile(".+/.+\\..+");

    public void pullBaseImage() {
        logger.info(">>>>>>>>>> ---------- pull_base_image ---------- >>>>>>>>>>");
        if (this.ciOpts.docker()) {
            final List<String> dockerfiles = dockerfiles();

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Found dockerfiles %s", dockerfiles));
            }

            final List<String> baseImages = baseImages(dockerfiles);

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Found baseImages %s", baseImages));
            }

            final Map<String, String> env = this.ciOpts.dockerHost().map(host -> singletonMap(ENV_VAR_DOCKER_HOST, host)).orElse(null);
            baseImages.forEach(image -> exec(env, null, DOCKER_EXECUTABLE, "pull", image));
        }
        logger.info("<<<<<<<<<< ---------- pull_base_image ---------- <<<<<<<<<<");
    }

    public static Map.Entry<Integer, String> dockerLogin(
        final Logger logger,
        final String dockerHost,
        final String registry,
        final String user,
        final String pass
    ) {
        final Map<String, String> environment = new LinkedHashMap<>();
        if (registry.startsWith("https://")) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("docker logging into secure registry %s", registry));
            }
        } else {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("docker logging into insecure registry %s", registry));
            }
            environment.put("DOCKER_OPTS", String.format("–insecure-registry %s", registry));
        }
        if (notEmpty(dockerHost)) {
            environment.put(ENV_VAR_DOCKER_HOST, dockerHost);
        }

        final String[] command = {DOCKER_EXECUTABLE, "login", "--password-stdin", "-u=" + user, registry};

        return exec(environment, pass, command);
    }

    static List<String> baseImages(final List<String> dockerfiles) {
        return dockerfiles
            .stream()
            .map(dockerfile -> Unix4j.cat(dockerfile).grep("^FROM[ ]+.+$").toStringList())
            .flatMap(Collection::stream)
            .map(line -> line.replaceAll("\\s+", " "))
            .filter(SupportFunction::isNotEmpty)
            .map(line -> line.split("\\s+"))
            .filter(value -> value.length > 1)
            .map(value -> value[1])
            .distinct()
            .collect(Collectors.toList());
    }

    static List<String> dockerfiles() {
        return Unix4j
            .find(".", "*Docker*")
            .toStringList()
            .stream()
            .filter(line -> !line.contains("/target/classes/"))
            .filter(line -> !PATTERN_FILE_WITH_EXT.matcher(line).matches())
            .collect(Collectors.toList());
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

    static List<String> lines(final String cmdOutput) {
        return Arrays.stream(("" + cmdOutput).split("\\r?\\n"))
            .map(line -> line.replaceAll("\\s+", " "))
            .filter(SupportFunction::isNotEmpty)
            .collect(Collectors.toList());
    }
}
