package top.infra.maven.extension.mavenbuild.options;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_HOTFIX;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_SUPPORT;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Gpg.gpgVersionGreater;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.NEXUS2;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.FAST;
import static top.infra.maven.utils.SystemUtils.exec;
import static top.infra.maven.utils.SystemUtils.systemUserDir;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import top.infra.maven.core.CiOption;
import top.infra.maven.core.CiOptionNames;
import top.infra.maven.core.GitProperties;

public enum MavenBuildPomOption implements CiOption {
    CHECKSTYLE_CONFIG_LOCATION("checkstyle.config.location",
        "https://raw.githubusercontent.com/ci-and-cd/maven-build/master/src/main/checkstyle/google_checks_8.10.xml"),
    // @Deprecated
    // CI_SCRIPT("ci.script"),
    DEPENDENCYCHECK("dependency-check") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.of(FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean)
                .filter(fast -> !fast)
                .map(fast -> BOOL_STRING_TRUE)
                .orElse(BOOL_STRING_FALSE));
        }
    },

    DOCKERFILE_USEMAVENSETTINGSFORAUTH("dockerfile.useMavenSettingsForAuth", BOOL_STRING_FALSE),
    DOCKER_IMAGE_PREFIX("docker.image.prefix"),
    /**
     * com.spotify:docker-maven-plugin
     */
    DOCKER_IMAGENAME("docker.imageName") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.of(DOCKER_REGISTRY.getValue(gitProperties, systemProperties, userProperties)
                .map(registry -> "${docker.registry}/${docker.image.prefix}${project.artifactId}")
                .orElse("${docker.image.prefix}${project.artifactId}"));
        }
    },
    /**
     * com.spotify:dockerfile-maven-plugin
     */
    DOCKERFILE_REPOSITORY("dockerfile.repository") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.of(DOCKER_REGISTRY.getValue(gitProperties, systemProperties, userProperties)
                .map(registry -> "${docker.registry}/${docker.image.prefix}${project.artifactId}")
                .orElse("${docker.image.prefix}${project.artifactId}"));
        }
    },
    // https://npm.taobao.org/mirrors/node/
    FRONTEND_NODEDOWNLOADROOT("frontend.nodeDownloadRoot", "https://nodejs.org/dist/"),
    // http://registry.npm.taobao.org/npm/-/
    FRONTEND_NPMDOWNLOADROOT("frontend.npmDownloadRoot", "https://registry.npmjs.org/npm/-/"),
    GIT_COMMIT_ID_SKIP("git.commit.id.skip", BOOL_STRING_FALSE),
    GITHUB_GLOBAL_OAUTH2TOKEN("github.global.oauth2Token") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return GIT_AUTH_TOKEN.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    GITHUB_GLOBAL_REPOSITORYNAME("github.global.repositoryName") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return SITE_PATH_PREFIX.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    GITHUB_GLOBAL_REPOSITORYOWNER("github.global.repositoryOwner", "unknown-owner") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean generateReports = MavenOption.GENERATEREPORTS.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return generateReports
                ? MavenBuildExtensionOption.gitRepoSlug(gitProperties, systemProperties).map(slug -> slug.split("/")[0])
                : Optional.empty();
        }
    },
    GITHUB_SITE_PUBLISH("github.site.publish", BOOL_STRING_FALSE) {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean generateReports = MavenOption.GENERATEREPORTS.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return generateReports
                ? this.findInProperties(systemProperties, userProperties)
                : Optional.of(BOOL_STRING_FALSE);
        }
    },
    GPG_EXECUTABLE("gpg.executable") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String gpg = exec("which gpg").getKey() == 0 ? "gpg" : null;
            final String gpgExecutable = exec("which gpg2").getKey() == 0 ? "gpg2" : gpg;

            return Optional.ofNullable(gpgExecutable);
        }
    },
    GPG_KEYID("gpg.keyid"),
    GPG_KEYNAME("gpg.keyname"),
    GPG_LOOPBACK("gpg.loopback") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> gpgExecutable = GPG_EXECUTABLE.getValue(gitProperties, systemProperties, userProperties);

            final Optional<String> result;
            if (gpgExecutable.isPresent()) {
                final List<String> gpgVersion = Arrays.asList(gpgExecutable.get(), "--batch=true", "--version");
                final Map.Entry<Integer, String> resultGpgVersion = exec(null, null, gpgVersion);
                if (gpgVersionGreater(resultGpgVersion.getValue(), "2.1")) {
                    result = Optional.of(BOOL_STRING_TRUE);
                } else {
                    result = Optional.empty();
                }
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    GPG_PASSPHRASE("gpg.passphrase"),
    /**
     * Run jacoco if true, skip jacoco and enable cobertura if false, skip bothe jacoco and cobertura if absent.
     */
    JACOCO("jacoco") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean skip = FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return Optional.ofNullable(skip ? null : BOOL_STRING_TRUE);
        }
    },
    MAVEN_CENTRAL_PASS("maven.central.pass"),
    MAVEN_CENTRAL_USER("maven.central.user"),
    NEXUS2_STAGING("nexus2.staging") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> publishChannel = PUBLISH_CHANNEL.getValue(gitProperties, systemProperties, userProperties);
            final boolean publishSnapshot = publishChannel.map(PUBLISH_CHANNEL_SNAPSHOT::equals).orElse(FALSE);
            return publishSnapshot
                ? Optional.of(BOOL_STRING_FALSE)
                : super.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    PMD_RULESET_LOCATION("pmd.ruleset.location",
        "https://raw.githubusercontent.com/ci-and-cd/maven-build/master/src/main/pmd/pmd-ruleset-6.8.0.xml"),

    /**
     * Auto determine current build publish channel by current build ref name.<br/>
     * snapshot or release
     */
    PUBLISH_CHANNEL("publish.channel", "snapshot") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String result;

            final String refName = MavenBuildExtensionOption.GIT_REF_NAME.getValue(
                gitProperties, systemProperties, userProperties).orElse("");
            if (GIT_REF_NAME_DEVELOP.equals(refName)) {
                result = PUBLISH_CHANNEL_SNAPSHOT;
            } else if (refName.startsWith(BRANCH_PREFIX_FEATURE)) {
                result = PUBLISH_CHANNEL_SNAPSHOT;
            } else if (refName.startsWith(BRANCH_PREFIX_HOTFIX)) {
                result = PUBLISH_CHANNEL_RELEASE;
            } else if (refName.startsWith(BRANCH_PREFIX_RELEASE)) {
                result = PUBLISH_CHANNEL_RELEASE;
            } else if (refName.startsWith(BRANCH_PREFIX_SUPPORT)) {
                result = PUBLISH_CHANNEL_RELEASE;
            } else {
                result = PUBLISH_CHANNEL_SNAPSHOT;
            }

            return Optional.of(result);
        }
    },
    SITE_PATH("site.path", "${publish.channel}/${site.path.prefix}"),
    SITE_PATH_PREFIX("site.path.prefix") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean generateReports = MavenOption.GENERATEREPORTS.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            final Optional<String> result;
            if (generateReports) {
                final Boolean githubSitePublish = GITHUB_SITE_PUBLISH.getValue(gitProperties, systemProperties, userProperties)
                    .map(Boolean::parseBoolean).orElse(FALSE);
                final Optional<String> gitRepoSlug = MavenBuildExtensionOption.gitRepoSlug(gitProperties, systemProperties);
                result = githubSitePublish
                    ? gitRepoSlug.map(slug -> slug.split("/")[1])
                    : gitRepoSlug;
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    SONAR("sonar"),
    WAGON_MERGEMAVENREPOS_ARTIFACTDIR("wagon.merge-maven-repos.artifactDir") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            // TODO System.setProperty("wagon.merge-maven-repos.artifactDir", "${project.groupId}".replace('.', '/') + "/${project.artifactId}")
            // TODO Extract all options that depend on project properties to ProjectOption class.
            final boolean segregation = MavenBuildExtensionOption.MVN_DEPLOY_PUBLISH_SEGREGATION.getValue(
                gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);
            return Optional.ofNullable(segregation ? "${project.groupId}/${project.artifactId}" : null);
        }
    },
    WAGON_MERGEMAVENREPOS_SOURCE("wagon.merge-maven-repos.source") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean segregation = MavenBuildExtensionOption.MVN_DEPLOY_PUBLISH_SEGREGATION.getValue(
                gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            final Optional<String> result;
            if (segregation) {
                final String commitId = gitProperties.commitId().map(value -> value.substring(0, 8)).orElse("unknown-commit");
                // final String prefix = Paths.get(systemUserHome(), ".ci-and-cd", "local-deploy").toString();
                final String prefix = Paths.get(systemUserDir(), ".mvn", "wagonRepository").toString();
                result = Optional.of(Paths.get(prefix, commitId).toString());
            } else {
                result = Optional.empty();
            }
            return result;
        }

        @Override
        public Optional<String> setProperties(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties,
            final Properties properties
        ) {
            final Optional<String> result = super.setProperties(gitProperties, systemProperties, userProperties, properties);

            result.ifPresent(source -> properties.setProperty("altDeploymentRepository", "repo::default::file://" + source));

            return result;
        }
    },
    WAGON_MERGEMAVENREPOS_TARGET("wagon.merge-maven-repos.target") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean segregation = MavenBuildExtensionOption.MVN_DEPLOY_PUBLISH_SEGREGATION.
                getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            final Optional<String> result;
            if (segregation) {
                final Optional<String> infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties);
                final Optional<String> nexus2 = NEXUS2.getValue(gitProperties, systemProperties, userProperties);

                final boolean nexus2Staging = NEXUS2_STAGING.getValue(gitProperties, systemProperties, userProperties)
                    .map(Boolean::parseBoolean).orElse(FALSE);
                final Optional<String> publishChannel = PUBLISH_CHANNEL.getValue(gitProperties, systemProperties, userProperties);
                final boolean publishRelease = publishChannel.map(PUBLISH_CHANNEL_RELEASE::equals).orElse(FALSE);

                final String prefix = infrastructure.map(infra -> String.format("%s", infra)).orElse("");
                final String value;
                if (nexus2.isPresent()) {
                    if (publishRelease) {
                        value = nexus2Staging
                            ? String.format("${%snexus2}service/local/staging/deploy/maven2/", prefix)
                            : String.format("${%snexus2}content/repositories/releases/", prefix);
                    } else {
                        value = String.format("${%snexus2}content/repositories/snapshots/", prefix);
                    }
                } else {
                    value = String.format("${%snexus3}repository/maven-${publish.channel}s", prefix);
                }
                result = Optional.ofNullable(value);
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    WAGON_MERGEMAVENREPOS_TARGETID("wagon.merge-maven-repos.targetId") {
        @Override
        public Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean segregation = MavenBuildExtensionOption.MVN_DEPLOY_PUBLISH_SEGREGATION.getValue(
                gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            final Optional<String> result;
            if (segregation) {
                final Optional<String> infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties);
                result = Optional.of(infrastructure
                    .map(infra -> String.format("%s-${publish.channel}s", infra))
                    .orElse("${publish.channel}s"));
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    ;

    private final String defaultValue;
    private final String envVariableName;
    private final String propertyName;
    private final String systemPropertyName;

    MavenBuildPomOption(final String propertyName) {
        this(propertyName, null);
    }

    MavenBuildPomOption(final String propertyName, final String defaultValue) {
        if (!CiOptionNames.name(propertyName).equals(this.name())) {
            throw new IllegalArgumentException(String.format("invalid property name [%s] for enum name [%s]", this.name(), propertyName));
        }

        this.defaultValue = defaultValue;
        this.envVariableName = CiOptionNames.envVariableName(propertyName);
        this.propertyName = propertyName;
        this.systemPropertyName = CiOptionNames.systemPropertyName(propertyName);
    }

    public Optional<String> getDefaultValue() {
        return Optional.ofNullable(this.defaultValue);
    }

    public String getEnvVariableName() {
        return this.envVariableName;
    }

    public String getPropertyName() {
        return this.propertyName;
    }

    public String getSystemPropertyName() {
        return this.systemPropertyName;
    }
}
