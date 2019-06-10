package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static top.infra.maven.extension.mavenbuild.CiOption.CACHE_DIRECTORY;
import static top.infra.maven.extension.mavenbuild.CiOption.CI_OPTS_FILE;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO_REF;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_EXTRA_OPTS;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_OPTS;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_CHANNEL;
import static top.infra.maven.extension.mavenbuild.CiOption.systemPropertyName;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_MASTER;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.SupportFunction.download;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exec;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exists;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isSemanticSnapshotVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.maskSecrets;
import static top.infra.maven.extension.mavenbuild.SupportFunction.readFile;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemJavaIoTmp;
import static top.infra.maven.extension.mavenbuild.SupportFunction.writeFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.unix4j.Unix4j;

public class CiOptionAccessor {

    private final Logger logger;

    private final GitProperties gitProperties;
    private final Properties systemProperties;
    private final Properties userProperties;

    public CiOptionAccessor(
        final Logger logger,
        final Properties systemProperties,
        final Properties userProperties
    ) {
        this.logger = logger;
        this.gitProperties = new GitProperties(logger);
        this.systemProperties = systemProperties;
        this.userProperties = userProperties;

        this.cacheDirectory();
    }

    public Optional<String> getOption(final CiOption ciOption) {
        return ciOption.getValue(this.gitProperties, this.systemProperties, this.userProperties);
    }

    private Optional<String> setOption(final CiOption ciOption, final Properties properties) {
        return ciOption.setProperties(this.gitProperties, this.systemProperties, this.userProperties, properties);
    }

    public void setSystemProperty(final CiOption key, final String value) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("set %s: %s", key.getSystemPropertyName(), value));
        }
        this.systemProperties.setProperty(key.getSystemPropertyName(), value);
    }

    private void setUserProperty(final CiOption key, final String value) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("set %s: %s", key.getPropertyName(), value));
        }
        this.userProperties.setProperty(key.getPropertyName(), value);
    }

    public void updateSystemProperties(final Properties properties) {
        for (final String name : properties.stringPropertyNames()) {
            final String key = systemPropertyName(name);
            final String value = properties.getProperty(name);
            if (value != null) {
                this.systemProperties.setProperty(key, value);
            }
        }
    }

    private String cacheDirectory() {
        final String result = this.getOption(CACHE_DIRECTORY).orElse(systemJavaIoTmp());
        if (!exists(Paths.get(result))) {
            try {
                Files.createDirectories(Paths.get(result));
            } catch (final IOException ex) {
                logger.error(String.format("Error create cacheDirectory '%s'. %s", result, ex.getMessage()));
            }
        }
        return result;
    }

    public Map.Entry<Boolean, RuntimeException> checkProjectVersion(final String projectVersion) {
        final String gitRefName = this.getOption(GIT_REF_NAME).orElse("");
        final String msgInvalidVersion = String.format("Invalid version [%s] for ref [%s]", projectVersion, gitRefName);
        final boolean semanticSnapshotVersion = isSemanticSnapshotVersion(projectVersion); // no feature name in version
        final boolean snapshotChannel = PUBLISH_CHANNEL_SNAPSHOT.equals(this.getOption(PUBLISH_CHANNEL).orElse(null));
        final boolean snapshotVersion = projectVersion != null && projectVersion.endsWith("-SNAPSHOT");

        final boolean result;
        final RuntimeException ex;
        if (snapshotChannel) {
            final boolean onDevelopBranch = GIT_REF_NAME_DEVELOP.equals(gitRefName);
            final boolean onFeatureBranches = gitRefName.startsWith(BRANCH_PREFIX_FEATURE);
            if (onFeatureBranches) {
                result = snapshotVersion && !semanticSnapshotVersion;
                ex = null;
            } else if (isEmpty(gitRefName) || onDevelopBranch) {
                result = snapshotVersion && semanticSnapshotVersion;
                ex = null;
            } else {
                result = snapshotVersion;
                ex = result ? null : new IllegalArgumentException(msgInvalidVersion);
            }
        } else {
            result = !snapshotVersion;
            ex = result ? null : new IllegalArgumentException(msgInvalidVersion);
        }

        return new AbstractMap.SimpleImmutableEntry<>(result, ex);
    }

    public Properties ciOptsFromFile() {
        final Properties result = new Properties();

        this.getOption(CI_OPTS_FILE).ifPresent(ciOptsFile -> {
            try {
                if (new File(ciOptsFile).exists()) {
                    result.load(new FileInputStream(ciOptsFile));
                } else if (this.downloadFromGitRepo(SRC_CI_OPTS_PROPERTIES, ciOptsFile)) {
                    result.load(new FileInputStream(ciOptsFile));
                } else {
                    final String errorMsg = String.format("Can not download %s", ciOptsFile);
                    logger.warn(errorMsg);
                }
            } catch (final IOException ex) {
                final String errorMsg = String.format("Can not load ci options file %s", ex.getMessage());
                logger.warn(errorMsg);
            }
        });

        return result;
    }

    /**
     * Docker enabled.
     *
     * @return docker enabled
     */
    public boolean docker() {
        final Boolean result;
        final Optional<String> varValueOptional = this.getOption(DOCKER);
        if (varValueOptional.isPresent()) {
            result = parseBoolean(varValueOptional.get());
        } else {
            if (exec("type -p docker").getKey() == 0) {
                // TODO Support named pipe (for windows).
                // Unix sock file
                // [[ -f /var/run/docker.sock ]] || [[ -L /var/run/docker.sock ]]
                final String dockerSockFile = "/var/run/docker.sock";
                final boolean dockerSockFilePresent = new File(dockerSockFile).exists();
                // TCP
                final boolean dockerHostVarPresent = this.dockerHost().isPresent();
                // [[ -n "$(find . -name '*Docker*')" ]] || [[ -n "$(find . -name '*docker-compose*.yml')" ]]
                final int dockerFilesCount = parseInt(Unix4j.find(".", "*Docker*").wc("-l").toStringResult());
                final int dockerComposeFilesCount = parseInt(Unix4j.find(".", "*docker-compose*.yml").wc("-l").toStringResult());
                final boolean dockerFilesFound = dockerFilesCount > 0 || dockerComposeFilesCount > 0;

                result = dockerFilesFound && (dockerSockFilePresent || dockerHostVarPresent);
            } else {
                result = FALSE;
            }

            this.setUserProperty(DOCKER, result.toString());
        }
        return result;
    }

    public Optional<String> dockerHost() {
        return Optional.ofNullable(this.systemProperties.getProperty("env.DOCKER_HOST"));
    }

    private static final Pattern PATTERN_GITLAB_URL = Pattern.compile("^.+/api/v4/projects/[0-9]+/repository/.+$");

    public boolean downloadFromGitRepo(final String sourceFile, final String targetFile) {
        final boolean result;

        final Optional<String> repo = this.getOption(MAVEN_BUILD_OPTS_REPO);
        if (repo.isPresent()) {
            final String repoRef = this.getOption(MAVEN_BUILD_OPTS_REPO_REF).orElse(GIT_REF_NAME_MASTER);

            final Optional<String> gitAuthToken = this.getOption(GIT_AUTH_TOKEN);
            final Map<String, String> headers = new LinkedHashMap<>();
            gitAuthToken.ifPresent(s -> headers.put("PRIVATE-TOKEN", s));

            if (PATTERN_GITLAB_URL.matcher(repo.get()).matches()) {
                final String fromUrl = repo.get() + sourceFile.replaceAll("/", "%2F") + "?ref=" + repoRef;
                final String saveToFile = targetFile + ".json";
                final Optional<Integer> status = download(logger, fromUrl, saveToFile, headers);
                if (status.map(value -> value >= 200 && value < 300).orElse(FALSE)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("decode %s", saveToFile));
                    }
                    // cat "${target_file}.json" | jq -r ".content" | base64 --decode | tee "${target_file}"
                    final JSONObject jsonFile = new JSONObject(readFile(Paths.get(saveToFile), UTF_8));
                    final String content = jsonFile.getString("content");
                    result = !isEmpty(content)
                        && writeFile(Paths.get(targetFile), Base64.getDecoder().decode(content), StandardOpenOption.SYNC);
                } else {
                    if (logger.isErrorEnabled()) {
                        logger.error(String.format("Can not download %s.", targetFile));
                        logger.error(String.format("Please make sure you have permission to access resources and %s is set.",
                            GIT_AUTH_TOKEN.getEnvVariableName()));
                    }
                    result = false;
                }
            } else {
                final Optional<Integer> status = download(
                    logger,
                    repo.get() + "/raw/" + repoRef + "/" + sourceFile,
                    targetFile,
                    headers
                );
                result = status.map(value -> value >= 200 && value < 300).orElse(FALSE);
            }
        } else {
            result = false;
        }

        return result;
    }

    public Map<String, String> mavenExtraOpts() {
        final Optional<String> opts = this.getOption(MAVEN_EXTRA_OPTS);
        return opts.map(CiOptionAccessor::parseMavenUserProperties).orElse(new LinkedHashMap<>());
    }

    public Map<String, String> mavenOpts() {
        final Optional<String> opts = Optional.ofNullable(this.systemProperties.getProperty(MAVEN_OPTS.getSystemPropertyName()));
        return opts.map(CiOptionAccessor::parseMavenUserProperties).orElse(new LinkedHashMap<>());
    }

    public Properties mavenOptsInto(final Properties intoProperties) {
        final Properties newProperties = new Properties();

        final Map<String, String> mavenOpts = this.mavenOpts();
        if (!mavenOpts.isEmpty()) {
            mavenOpts.forEach(newProperties::setProperty);
        } else {
            Arrays.stream(CiOption.values()).sorted().forEach(ciOption -> {
                final Optional<String> result = this.setOption(ciOption, newProperties);
                if (logger.isInfoEnabled()) {
                    logger.info(maskSecrets(String.format("setOption %s=%s", ciOption.name(), result.orElse(""))));
                }
            });

            final Map<String, String> extraMavenOpts = this.mavenExtraOpts();
            extraMavenOpts.forEach(newProperties::setProperty);

            if (!this.getOption(INFRASTRUCTURE).isPresent()) {
                final String errorMsg = String.format("%s not set", INFRASTRUCTURE);
                if (logger.isErrorEnabled()) {
                    logger.error(errorMsg);
                }
                throw new IllegalArgumentException(errorMsg);
            }
        }

        SupportFunction.merge(newProperties, intoProperties);

        return newProperties;
    }

    static Map<String, String> parseMavenUserProperties(final String options) {
        final Map<String, String> result = new LinkedHashMap<>();
        Arrays
            .stream(options.split("[ ]+"))
            .filter(opt -> opt.startsWith("-D") && opt.contains("="))
            .forEach(opt -> {
                final String name = opt.split("=")[0].substring(2);
                final String value = opt.substring(name.length() + 3);
                result.put(name, value);
            });
        return result;
    }
}
