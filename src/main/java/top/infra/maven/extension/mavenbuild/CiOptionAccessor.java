package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.CACHE_DIRECTORY;
import static top.infra.maven.extension.mavenbuild.CiOption.CI_OPTS_FILE;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_BUILD_OPTS_REPO_REF;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_CHANNEL;
import static top.infra.maven.extension.mavenbuild.CiOption.systemPropertyName;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.utils.PropertiesUtil.maskSecrets;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.exists;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isSemanticSnapshotVersion;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTuple;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtil.systemJavaIoTmp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;

import top.infra.exception.RuntimeIOException;
import top.infra.maven.extension.mavenbuild.utils.PropertiesUtil;
import top.infra.maven.extension.mavenbuild.utils.SystemUtil;
import top.infra.maven.logging.Logger;

public class CiOptionAccessor {

    private static final String PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY = "maven.multiModuleProjectDirectory";

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

        if (userProperties.getProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY) == null) {
            final String mavenMultiModuleProjectDirectory = systemProperties.getProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY);
            if (mavenMultiModuleProjectDirectory != null) {
                userProperties.setProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, mavenMultiModuleProjectDirectory);
            } else {
                final String systemUserDir = SystemUtil.systemUserDir();
                logger.warn(String.format(
                    "Value of system property [%s] not found, use user.dir [%s] instead.",
                    PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, systemUserDir
                ));
                userProperties.setProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, systemUserDir);
            }
        }

        this.cacheDirectory();
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

        return newTuple(result, ex);
    }

    public Properties ciOptsFromFile() {
        final Properties properties = new Properties();

        this.getOption(CI_OPTS_FILE).ifPresent(ciOptsFile -> {
            final boolean ciOptsFileExists = new File(ciOptsFile).exists();
            if (!ciOptsFileExists) {
                this.gitRepository().download(SRC_CI_OPTS_PROPERTIES, ciOptsFile, true);
            }
            try {
                properties.load(new FileInputStream(ciOptsFile));
            } catch (final IOException ex) {
                final String errorMsg = String.format("Can not load ci options file %s", ex.getMessage());
                logger.error(errorMsg);
                throw new RuntimeIOException(errorMsg, ex);
            }
        });

        return properties;
    }

    public GitRepository gitRepository() {
        return new GitRepository(
            logger,
            this.getOption(MAVEN_BUILD_OPTS_REPO).orElse(null),
            this.getOption(MAVEN_BUILD_OPTS_REPO_REF).orElse(null),
            this.getOption(GIT_AUTH_TOKEN).orElse(null)
        );
    }

    public Properties mergeCiOptsInto(final Properties intoProperties) {
        final Properties newProperties = new Properties();

        Arrays.stream(CiOption.values()).sorted().forEach(ciOption -> {
            final Optional<String> result = this.setOption(ciOption, newProperties);
            if (logger.isInfoEnabled()) {
                logger.info(maskSecrets(String.format("setOption %s=%s", ciOption.getEnvVariableName(), result.orElse(""))));
            }
        });

        PropertiesUtil.merge(newProperties, intoProperties);

        return newProperties;
    }

    private Optional<String> setOption(final CiOption ciOption, final Properties properties) {
        return ciOption.setProperties(this.gitProperties, this.systemProperties, this.userProperties, properties);
    }
}
