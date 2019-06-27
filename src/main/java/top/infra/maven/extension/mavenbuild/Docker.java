package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.utils.FileUtils.find;
import static top.infra.maven.utils.SupportFunction.isEmpty;
import static top.infra.maven.utils.SupportFunction.isNotEmpty;
import static top.infra.maven.utils.SupportFunction.lines;
import static top.infra.maven.utils.SupportFunction.notEmpty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import top.infra.maven.utils.SupportFunction;
import top.infra.maven.utils.SystemUtils;

/**
 * TODO use a java docker client ?
 */
public class Docker {

    private static final Pattern PATTERN_BASE_IMAGE = Pattern.compile("^FROM[ ]+.+$");

    private static final Pattern PATTERN_FILE_WITH_EXT = Pattern.compile(".+/.+\\..+");

    private final Map<String, String> environment;
    private final String homeDir;
    private final String registry;
    private final String registryPass;
    private final String registryUrl;
    private final String registryUser;

    public Docker(
        final String dockerHost,
        final String homeDir,
        final String registry,
        final String registryPass,
        final String registryUrl,
        final String registryUser
    ) {
        this.environment = environment(dockerHost, registry);
        this.homeDir = homeDir;

        this.registry = registry;
        this.registryPass = registryPass;
        this.registryUrl = registryUrl;
        this.registryUser = registryUser;
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

    public static Optional<String> dockerHost(final Properties systemProperties) {
        return Optional.ofNullable(systemProperties.getProperty("env.DOCKER_HOST"));
    }

    public List<String> imageIdsToClean() {
        final Entry<Integer, String> returnCodeAndStdout = this.docker("images");

        final List<String> imageIds;
        if (returnCodeAndStdout.getKey() == 0) {
            imageIds = imagesToClean(lines(returnCodeAndStdout.getValue()));
        } else {
            imageIds = Collections.emptyList();
        }
        return imageIds;
    }

    /**
     * Delete images.
     *
     * @param imageIds imageIds
     * @return imageId, return code map
     */
    public Map<String, Integer> deleteImages(final List<String> imageIds) {
        return imageIds.stream().collect(Collectors.toMap(id -> id, id -> this.docker("rmi", id).getKey()));
    }

    private Entry<Integer, String> docker(final String... options) {
        return SystemUtils.exec(this.environment, null, dockerCommand(options));
    }

    static List<String> dockerCommand(final String... options) {
        return SupportFunction.asList(new String[]{"docker"}, options);
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

    public void initConfigFile() {
        this.docker("version");

        // TODO config docker log rotation here ?
        final File dockerConfigDir = Paths.get(this.homeDir, ".docker").toFile();
        if (!dockerConfigDir.exists()) {
            dockerConfigDir.mkdirs();
        }
    }

    /**
     * Pull base images found in Dockerfiles.
     *
     * @param dockerfiles Dockerfiles to find base images in.
     * @return base images found / pulled
     */
    public List<String> pullBaseImages(final List<String> dockerfiles) {
        final List<String> baseImages = baseImages(dockerfiles);
        baseImages.forEach(image -> this.docker("pull", image));
        return baseImages;
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

    public static List<String> dockerfiles() {
        return find(".", "*Docker*")
            .stream()
            .filter(line -> !line.contains("/target/classes/")) // TODO windows ?
            .filter(line -> !PATTERN_FILE_WITH_EXT.matcher(line).matches())
            .collect(Collectors.toList());
    }

    public String getLoginTarget() {
        return isNotEmpty(this.registry) ? this.registry : this.registryUrl;
    }

    public Optional<Integer> login(final String target) {
        final Optional<Integer> result;
        if (isNotEmpty(target) && !shouldSkipDockerLogin()) {
            final List<String> command = dockerCommand("login", "--password-stdin", "-u=" + this.registryUser, target);
            result = Optional.of(SystemUtils.exec(this.environment, this.registryPass, command).getKey());
        } else {
            result = Optional.empty();
        }
        return result;
    }

    public boolean shouldSkipDockerLogin() {
        return isEmpty(this.registryPass) || isEmpty(this.registryUser);
    }
}
