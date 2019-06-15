package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.CACHE_DIRECTORY;
import static top.infra.maven.extension.mavenbuild.CiOption.CI_OPTS_FILE;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO_REF;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_EXTRA_OPTS;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_OPTS;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_CHANNEL;
import static top.infra.maven.extension.mavenbuild.CiOption.systemPropertyName;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exists;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isSemanticSnapshotVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.maskSecrets;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemJavaIoTmp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;

public class CiOptionAccessor {

    private final Logger logger;

    private final GitProperties gitProperties;

    private final Properties systemProperties;
    private final Properties userProperties;

    public CiOptionAccessor(
        final Logger logger,
        final GitProperties gitProperties,
        final Properties systemProperties,
        final Properties userProperties
    ) {
        this.logger = logger;
        this.gitProperties = gitProperties;
        this.systemProperties = systemProperties;
        this.userProperties = userProperties;

        this.cacheDirectory();
    }

    public Optional<String> getOption(final CiOption ciOption) {
        return ciOption.getValue(this.gitProperties, this.systemProperties, this.userProperties);
    }

    public Properties getSystemProperties() {
        return this.systemProperties;
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

    public GitRepository gitRepository() {
        return new GitRepository(
            logger,
            this.getOption(MAVEN_BUILD_OPTS_REPO).orElse(null),
            this.getOption(MAVEN_BUILD_OPTS_REPO_REF).orElse(null),
            this.getOption(GIT_AUTH_TOKEN).orElse(null)
        );
    }

    public Entry<Boolean, RuntimeException> checkProjectVersion(final String projectVersion) {
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
        final Properties properties = new Properties();

        this.getOption(CI_OPTS_FILE).ifPresent(ciOptsFile -> {
            try {
                if (new File(ciOptsFile).exists()) {
                    properties.load(new FileInputStream(ciOptsFile));
                } else {
                    final Entry<Optional<String>, Optional<Integer>> result = this.gitRepository()
                        .download(SRC_CI_OPTS_PROPERTIES, ciOptsFile);
                    if (result.getValue().map(SupportFunction::is2xxStatus).orElse(FALSE)) {
                        properties.load(new FileInputStream(ciOptsFile));
                    } else {
                        final String errorMsg = String.format("Can not download from [%s], to [%s], status [%s].",
                            result.getKey().orElse(null), ciOptsFile, result.getValue().orElse(null));
                        logger.warn(errorMsg);
                    }
                }
            } catch (final IOException ex) {
                final String errorMsg = String.format("Can not load ci options file %s", ex.getMessage());
                logger.warn(errorMsg);
            }
        });

        return properties;
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
                    logger.info(maskSecrets(String.format("setOption %s=%s", ciOption.getEnvVariableName(), result.orElse(""))));
                }
            });

            final Map<String, String> extraMavenOpts = this.mavenExtraOpts();
            extraMavenOpts.forEach(newProperties::setProperty);
        }

        SupportFunction.merge(newProperties, intoProperties);

        return newProperties;
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

    private Optional<String> setOption(final CiOption ciOption, final Properties properties) {
        return ciOption.setProperties(this.gitProperties, this.systemProperties, this.userProperties, properties);
    }

    @Deprecated
    private void setSystemProperty(final CiOption key, final String value) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("set %s: %s", key.getSystemPropertyName(), value));
        }
        this.systemProperties.setProperty(key.getSystemPropertyName(), value);
    }

    @Deprecated
    private void setUserProperty(final CiOption key, final String value) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("set %s: %s", key.getPropertyName(), value));
        }
        this.userProperties.setProperty(key.getPropertyName(), value);
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
