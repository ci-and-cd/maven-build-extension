package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.MAVEN_BUILD_OPTS_REPO;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.MAVEN_BUILD_OPTS_REPO_REF;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.CACHE_SESSION_PATH;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.exists;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isSemanticSnapshotVersion;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTuple;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemJavaIoTmp;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import top.infra.exception.RuntimeIOException;
import top.infra.maven.extension.mavenbuild.multiinfra.GitRepository;
import top.infra.maven.extension.mavenbuild.multiinfra.InfraOption;
import top.infra.maven.extension.mavenbuild.options.CiOption;
import top.infra.maven.extension.mavenbuild.options.CiOptionNames;
import top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption;
import top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption;
import top.infra.maven.extension.mavenbuild.options.MavenOption;
import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
import top.infra.maven.extension.mavenbuild.utils.PropertiesUtils;
import top.infra.maven.logging.Logger;

public class CiOptionAccessor {

    private final GitProperties gitProperties;

    private final Properties systemProperties;
    private final Properties userProperties;

    public CiOptionAccessor(
        final GitProperties gitProperties,
        final Properties systemProperties,
        final Properties userProperties
    ) {
        this.gitProperties = gitProperties;
        this.systemProperties = systemProperties;
        this.userProperties = userProperties;
    }

    private String createCache(final CiOption ciOpt) {
        final String pathname = this.getOption(ciOpt).orElse(systemJavaIoTmp());
        if (!exists(Paths.get(pathname))) {
            try {
                Files.createDirectories(Paths.get(pathname));
            } catch (final IOException ex) {
                final String errorMsg = String.format(
                    "Error create [%s] directory '%s'. %s",
                    ciOpt.getEnvVariableName(), pathname, ex.getMessage());
                throw new RuntimeIOException(errorMsg, ex);
            }
        }
        return pathname;
    }

    public Entry<Boolean, RuntimeException> checkProjectVersion(final String projectVersion) {
        final String gitRefName = this.getOption(MavenBuildExtensionOption.GIT_REF_NAME).orElse("");
        final String msgInvalidVersion = String.format("Invalid version [%s] for ref [%s]", projectVersion, gitRefName);
        final boolean semanticSnapshotVersion = isSemanticSnapshotVersion(projectVersion); // no feature name in version
        final boolean snapshotChannel = PUBLISH_CHANNEL_SNAPSHOT.equals(
            this.getOption(MavenBuildPomOption.PUBLISH_CHANNEL).orElse(null));
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

        return newTuple(result, ex);
    }

    public Properties ciOptsFromFile(final Logger logger) {
        final Properties properties = new Properties();

        this.getOption(InfraOption.CI_OPTS_FILE).ifPresent(ciOptsFile -> {
            this.createCacheInfrastructure();
            final boolean offline = MavenUtils.cmdArgOffline(this.systemProperties).orElse(FALSE);
            final boolean update = MavenUtils.cmdArgUpdate(this.systemProperties).orElse(FALSE);
            this.gitRepository(logger).ifPresent(repo -> {
                repo.download(SRC_CI_OPTS_PROPERTIES, ciOptsFile, true, offline, update);

                try {
                    properties.load(new FileInputStream(ciOptsFile));
                } catch (final IOException ex) {
                    final String errorMsg = String.format("Can not load ci options file %s", ex.getMessage());
                    throw new RuntimeIOException(errorMsg, ex);
                }
            });
        });

        return properties;
    }

    public String createCacheInfrastructure() {
        return this.createCache(InfraOption.CACHE_SETTINGS_PATH);
    }

    public String createCacheSession() {
        return this.createCache(CACHE_SESSION_PATH);
    }

    public Optional<String> getOption(final CiOption ciOption) {
        return ciOption.getValue(this.gitProperties, this.systemProperties, this.userProperties);
    }

    public Properties getSystemProperties() {
        return this.systemProperties;
    }

    public Optional<GitRepository> gitRepository(final Logger logger) {
        return this.getOption(MAVEN_BUILD_OPTS_REPO).map(repo -> new GitRepository(
            logger,
            repo,
            this.getOption(MAVEN_BUILD_OPTS_REPO_REF).orElse(null),
            this.getOption(GIT_AUTH_TOKEN).orElse(null)
        ));
    }

    public Properties setCiOptPropertiesInto(final Properties... targetProperties) {
        final Properties ciOptProperties = new Properties();

        Stream.of(
            Arrays.asList(InfraOption.values()),
            Arrays.asList(MavenBuildExtensionOption.values()),
            Arrays.asList(MavenOption.values()),
            Arrays.asList(MavenBuildPomOption.values()))
            .flatMap(collection -> collection.stream().sorted())
            .forEach(ciOption -> ciOption.setProperties(this.gitProperties, this.systemProperties, this.userProperties, ciOptProperties));

        for (final Properties target : targetProperties) {
            PropertiesUtils.merge(ciOptProperties, target);
        }

        return ciOptProperties;
    }

    public void updateSystemProperties(final Properties properties) {
        for (final String name : properties.stringPropertyNames()) {
            final String key = CiOptionNames.systemPropertyName(name);
            final String value = properties.getProperty(name);
            if (value != null && !this.systemProperties.containsKey(key)) {
                this.systemProperties.setProperty(key, value);
            }
        }
    }
}
