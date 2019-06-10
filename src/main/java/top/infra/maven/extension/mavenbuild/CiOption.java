package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_HOTFIX;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_SUPPORT;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_MASTER;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_PRIVATE;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_XML;
import static top.infra.maven.extension.mavenbuild.Gpg.gpgVersionGreater;
import static top.infra.maven.extension.mavenbuild.SupportFunction.domainOrHostFromUrl;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exec;
import static top.infra.maven.extension.mavenbuild.SupportFunction.gitRepoSlugFromUrl;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemJavaIoTmp;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemUserDir;
import static top.infra.maven.extension.mavenbuild.SupportFunction.urlWithoutPath;

import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

public enum CiOption {
    /**
     * maven-failsafe-plugin and maven-surefire-plugin's configuration argLine.
     */
    ARGLINE("argLine") {
        private Optional<String> addtionalArgs(
            final String argLine,
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> result;

            final Optional<Integer> javaVersion = systemJavaVersion();
            if (javaVersion.map(version -> version >= 9).orElse(FALSE)) {
                final Optional<String> addModules = JAVA_ADDMODULES.getValue(gitProperties, systemProperties, userProperties);
                final Optional<String> argLineWithModules;
                if (addModules.isPresent() && (argLine == null || !argLine.contains("--add-modules"))) {
                    argLineWithModules = Optional.of("--add-modules " + addModules.get());
                } else {
                    argLineWithModules = Optional.empty();
                }

                if (javaVersion.map(version -> version >= 11).orElse(FALSE)) {
                    result = argLineWithModules.map(value -> "--illegal-access=permit " + value);
                } else {
                    result = argLineWithModules;
                }
            } else {
                result = Optional.empty();
            }

            return result;
        }

        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return this.addtionalArgs(null, gitProperties, systemProperties, userProperties);
        }

        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String argLine = this.findInProperties(systemProperties, userProperties).orElse(null);
            return this.addtionalArgs(argLine, gitProperties, systemProperties, userProperties);
        }
    },
    FILE_ENCODING("file.encoding", UTF_8.name()),
    MAVEN_CLEAN_SKIP("maven.clean.skip", BOOL_STRING_TRUE),
    MAVEN_COMPILER_ENCODING("maven.compiler.encoding", UTF_8.name()),
    MAVEN_INTEGRATIONTEST_SKIP("maven.integration-test.skip", BOOL_STRING_FALSE),
    MAVEN_TEST_FAILURE_IGNORE("maven.test.failure.ignore", BOOL_STRING_FALSE),
    MAVEN_TEST_SKIP("maven.test.skip", BOOL_STRING_FALSE),
    PROJECT_BUILD_SOURCEENCODING("project.build.sourceEncoding", UTF_8.name()),
    PROJECT_REPORTING_OUTPUTENCODING("project.reporting.outputEncoding", UTF_8.name()),
    //
    CACHE_DIRECTORY("cache.directory") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> gitCommitId = gitProperties.commitId();

            // System.getProperty("user.home") + "/.ci-and-cd/tmp/" + groupId + "/" + artifactId + "/" + version;
            final String prefix = systemJavaIoTmp() + "/.ci-and-cd/tmp/" + SupportFunction.uniqueKey();

            return Optional.of(gitCommitId
                .map(commitId -> prefix + "/" + commitId)
                .orElseGet(() -> prefix + "/" + "cache-of-unknown-git-commit-id"));
        }
    },
    CHECKSTYLE_CONFIG_LOCATION("checkstyle.config.location",
        "https://raw.githubusercontent.com/ci-and-cd/maven-build/master/src/main/checkstyle/google_checks_8.10.xml"),
    CI_OPTS_FILE("ci.opts.file") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> infrastructureCiOptsProperties = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(infrastructure -> "../maven-build-opts-" + infrastructure + "/" + SRC_CI_OPTS_PROPERTIES);

            final Optional<String> result;
            if (new File(SRC_CI_OPTS_PROPERTIES).exists()) {
                result = Optional.of(SRC_CI_OPTS_PROPERTIES);
            } else if (infrastructureCiOptsProperties.isPresent()) {
                final String pathname = infrastructureCiOptsProperties.get();
                if (new File(pathname).exists()) {
                    result = Optional.of(pathname);
                } else {
                    final String cacheDirectory = CACHE_DIRECTORY.getValue(gitProperties, systemProperties, userProperties)
                        .orElse(systemJavaIoTmp());
                    final String cachedCiOptsProperties = cacheDirectory + "/" + SRC_CI_OPTS_PROPERTIES;
                    result = Optional.of(cachedCiOptsProperties);
                }
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    // @Deprecated
    // CI_SCRIPT("ci.script"),
    DEPENDENCYCHECK("dependency-check", BOOL_STRING_FALSE),

    DOCKER("docker"),
    DOCKERFILE_USEMAVENSETTINGSFORAUTH("dockerfile.useMavenSettingsForAuth", BOOL_STRING_FALSE),
    DOCKER_IMAGE_PREFIX("docker.image.prefix"),

    /**
     * Domain / hostname of docker registry. e.g. docker-registry.some.org
     */
    DOCKER_REGISTRY("docker.registry") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> dockerRegistryUrlOptional = DOCKER_REGISTRY_URL
                .getValue(gitProperties, systemProperties, userProperties);

            return dockerRegistryUrlOptional.flatMap(SupportFunction::domainOrHostFromUrl);
        }

        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return super.getValue(gitProperties, systemProperties, userProperties).map(value -> value.endsWith("docker.io") ? null : value);
        }
    },
    DOCKER_REGISTRY_PASS("docker.registry.pass"),
    DOCKER_REGISTRY_URL("docker.registry.url"),
    DOCKER_REGISTRY_USER("docker.registry.user"),

    // https://npm.taobao.org/mirrors/node/
    FRONTEND_NODEDOWNLOADROOT("frontend.nodeDownloadRoot", "https://nodejs.org/dist/"),
    // http://registry.npm.taobao.org/npm/-/
    FRONTEND_NPMDOWNLOADROOT("frontend.npmDownloadRoot", "https://registry.npmjs.org/npm/-/"),

    GIT_AUTH_TOKEN("git.auth.token") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(infrastructure ->
                    Arrays.stream(CiOption.values())
                        .filter(ciOption -> ciOption.name().equals(infrastructure.toUpperCase() + "_" + GIT_AUTH_TOKEN.name()))
                        .findFirst()
                        .map(ciOption -> ciOption.findInProperties(systemProperties, userProperties).orElse(null))
                        .orElse(null)
                );
        }
    },
    GIT_COMMIT_ID_SKIP("git.commit.id.skip", BOOL_STRING_FALSE),
    /**
     * Auto determine CI_OPT_GIT_PREFIX by infrastructure for further download.<br/>
     * prefix of git service url (infrastructure specific), i.e. https://github.com
     */
    GIT_PREFIX("git.prefix") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> result;

            final GitlabCiVariables gitlabCi = new GitlabCiVariables(systemProperties);
            final Optional<String> infrastructure = INFRASTRUCTURE.findInProperties(systemProperties, userProperties);
            final boolean infraOpenSource = infrastructure.map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);
            final boolean infraPrivate = infrastructure.map(INFRASTRUCTURE_PRIVATE::equals).orElse(FALSE);

            final Optional<String> gitPrefixFromOriginUrl = gitProperties.remoteOriginUrl()
                .map(url -> url.startsWith("http")
                    ? urlWithoutPath(url).orElse(null)
                    : domainOrHostFromUrl(url).map(value -> "http://" + value).orElse(null));

            if (infraOpenSource) {
                result = OPENSOURCE_GIT_PREFIX.getValue(gitProperties, systemProperties, userProperties);
            } else if (infraPrivate) {
                result = PRIVATE_GIT_PREFIX.getValue(gitProperties, systemProperties, userProperties);
            } else if (gitlabCi.ciProjectUrl().isPresent()) {
                result = gitlabCi.ciProjectUrl().map(url -> urlWithoutPath(url).orElse(null));
            } else {
                result = gitPrefixFromOriginUrl;
            }

            return result;
        }
    },

    /**
     * Auto detect current build ref name by CI environment variables or local git info.
     * Current build ref name, i.e. develop, release ...
     * <p>
     * travis-ci<br/>
     * TRAVIS_BRANCH for travis-ci, see: https://docs.travis-ci.com/user/environment-variables/<br/>
     * for builds triggered by a tag, this is the same as the name of the tag (TRAVIS_TAG).<br/>
     * </p>
     * <p>
     * appveyor<br/>
     * APPVEYOR_REPO_BRANCH - build branch. For Pull Request commits it is base branch PR is merging into<br/>
     * APPVEYOR_REPO_TAG - true if build has started by pushed tag; otherwise false<br/>
     * APPVEYOR_REPO_TAG_NAME - contains tag name for builds started by tag; otherwise this variable is<br/>
     * </p>
     */
    GIT_REF_NAME("git.ref.name") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> result;

            final Optional<String> appveyorRefName = new AppveyorVariables(systemProperties).refName();
            final Optional<String> gitlabCiRefName = new GitlabCiVariables(systemProperties).refName();
            final Optional<String> travisBranch = new TravisCiVariables(systemProperties).branch();

            if (appveyorRefName.isPresent()) {
                result = appveyorRefName;
            } else if (gitlabCiRefName.isPresent()) {
                result = gitlabCiRefName;
            } else if (travisBranch.isPresent()) {
                result = travisBranch;
            } else {
                result = gitProperties.refName();
            }

            return result;
        }
    },
    GITHUB_SITE_PUBLISH("github.site.publish", BOOL_STRING_FALSE) {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean site = SITE.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);
            final boolean openSource = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return site && openSource ? this.findInProperties(systemProperties, userProperties) : Optional.of(BOOL_STRING_FALSE);
        }
    },
    @Deprecated
    GITHUB_SITE_REPO_NAME("github.site.repo.name") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean openSource = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return openSource
                ? SITE_PATH_PREFIX.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    GITHUB_SITE_REPO_OWNER("github.site.repo.owner", "unknown-owner") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean openSource = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return openSource
                ? this.gitRepoSlug(gitProperties, systemProperties).map(slug -> slug.split("/")[0])
                : Optional.empty();
        }
    },

    GPG_EXECUTABLE("gpg.executable") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String gpg = exec("which gpg").getKey() == 0 ? "gpg" : null;
            final String gpgExecutable = exec("which gpg2").getKey() == 0 ? "gpg2" : gpg;

            return Optional.ofNullable(gpgExecutable);
        }

        @Override
        public Optional<String> setProperties(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties,
            final Properties properties
        ) {
            final Optional<String> result = super.setProperties(gitProperties, systemProperties, userProperties, properties);

            if (result.isPresent()
                && gpgVersionGreater(
                exec(null, null, result.get(), " --batch=true --version").getValue(), "2.1")
            ) {
                properties.setProperty(GPG_LOOPBACK.getPropertyName(), BOOL_STRING_TRUE);
            }

            return result;
        }
    },
    GPG_KEYID("gpg.keyid"),
    GPG_KEYNAME("gpg.keyname"),
    GPG_LOOPBACK("gpg.loopback"),
    GPG_PASSPHRASE("gpg.passphrase"),
    /**
     * Auto detect infrastructure using for this build.<br/>
     * example of gitlab-ci's CI_PROJECT_URL: "https://example.com/gitlab-org/gitlab-ce"<br/>
     * opensource, private or customized infrastructure name.
     */
    INFRASTRUCTURE("infrastructure") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String result;

            final Optional<String> appveyorRepoSlug = new AppveyorVariables(systemProperties).repoSlug();
            final Optional<String> ciProjectUrl = new GitlabCiVariables(systemProperties).ciProjectUrl();
            final Optional<String> privateGitPrefix = PRIVATE_GIT_PREFIX.getValue(gitProperties, systemProperties, userProperties);
            final Optional<String> travisCiRepoSlug = new TravisCiVariables(systemProperties).repoSlug();

            if (travisCiRepoSlug.isPresent() || appveyorRepoSlug.isPresent()) {
                result = INFRASTRUCTURE_OPENSOURCE;
            } else if (ciProjectUrl.isPresent()
                && privateGitPrefix.isPresent()
                && ciProjectUrl.get().startsWith(privateGitPrefix.get())) {
                result = INFRASTRUCTURE_PRIVATE;
            } else {
                result = null;
            }

            return Optional.ofNullable(result);
        }
    },

    JACOCO("jacoco"),
    JAVA_ADDMODULES("java.addModules") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<Integer> javaVersion = systemJavaVersion();
            return javaVersion
                .map(version -> {
                    final String defaultModules;
                    if (version == 9) {
                        defaultModules = "java.xml.bind,java.xml.ws,java.xml.ws.annotation";
                    } else {
                        defaultModules = null;
                    }
                    return defaultModules;
                });
        }
    },
    JIRA_PROJECTKEY("jira.projectKey"),
    JIRA_USER("jira.user") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> jiraProjectKey = JIRA_PROJECTKEY.getValue(gitProperties, systemProperties, userProperties);
            return jiraProjectKey.isPresent()
                ? super.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    JIRA_PASSWORD("jira.password") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> jiraProjectKey = JIRA_PROJECTKEY.getValue(gitProperties, systemProperties, userProperties);
            return jiraProjectKey.isPresent()
                ? super.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    LINKXREF("linkXRef", BOOL_STRING_TRUE),
    MAVEN_BUILD_OPTS_REPO("maven.build.opts.repo") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            // TODO default infrastructure ?
            final String infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .orElse(INFRASTRUCTURE_OPENSOURCE);

            return GIT_PREFIX.getValue(gitProperties, systemProperties, userProperties)
                .map(gitPrefix -> gitPrefix + "/ci-and-cd/maven-build-opts-" + infrastructure);
        }
    },
    MAVEN_BUILD_OPTS_REPO_REF("maven.build.opts.repo.ref", GIT_REF_NAME_MASTER),
    MAVEN_CENTRAL_PASS("maven.central.pass"),
    MAVEN_CENTRAL_USER("maven.central.user"),
    MAVEN_EXTRA_OPTS("maven.extra.opts"),
    MAVEN_OPTS("maven.opts"),
    MAVEN_QUALITY_SKIP("maven.quality.skip", BOOL_STRING_FALSE),
    MAVEN_SETTINGS_FILE("maven.settings.file") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String rootProjectPathname = rootProjectPathname(systemProperties);
            final String settingsFile = rootProjectPathname + "/" + SRC_MAVEN_SETTINGS_XML;

            final Optional<String> result;

            if (new File(settingsFile).exists()) {
                result = Optional.of(settingsFile);
            } else {
                final String cacheDirectory = CACHE_DIRECTORY.getValue(gitProperties, systemProperties, userProperties)
                    .orElse(systemJavaIoTmp());

                final String targetFile = cacheDirectory
                    + "/settings"
                    + INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                    .map(value -> "-" + value).orElse("") + ".xml";

                result = Optional.of(targetFile);
            }

            return result;
        }
    },
    @Deprecated
    MAVEN_SETTINGS_SECURITY_FILE("maven.settings.security.file") {
        @Override
        public Optional<String> setProperties(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties,
            final Properties properties
        ) {
            final Optional<String> result = super.setProperties(gitProperties, systemProperties, userProperties, properties);

            result.ifPresent(file -> {
                if (new File(file).exists()) {
                    properties.setProperty("settings.security", file);
                }
            });

            return result;
        }
    },
    MVN_DEPLOY_PUBLISH_SEGREGATION("mvn.deploy.publish.segregation", BOOL_STRING_FALSE),
    NEXUS3("nexus3"),

    OPENSOURCE_GIT_AUTH_TOKEN("opensource.git.auth.token") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return infrastructureMatch
                ? GIT_AUTH_TOKEN.findInProperties(systemProperties, userProperties)
                : Optional.empty();
        }
    },
    OPENSOURCE_GIT_PREFIX("opensource.git.prefix", "https://github.com") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.findInProperties(systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return infrastructureMatch
                ? GIT_PREFIX.findInProperties(systemProperties, userProperties)
                : Optional.empty();
        }
    },
    // OPENSOURCE_MVNSITE_PASSWORD("opensource.mvnsite.password"),
    // OPENSOURCE_MVNSITE_USERNAME("opensource.mvnsite.username"),
    OPENSOURCE_NEXUS3_REPOSITORY("opensource.nexus3.repository", "http://nexus3:28081/nexus/repository") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return infrastructureMatch
                ? NEXUS3.getValue(gitProperties, systemProperties, userProperties).map(value -> value + "/nexus/repository")
                : Optional.empty();
        }
    },
    OPENSOURCE_SONARQUBE_HOST_URL("opensource.sonarqube.host.url"),
    /**
     * Determine current is origin (original) or forked.
     */
    ORIGIN_REPO("origin.repo") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final AppveyorVariables appveyor = new AppveyorVariables(systemProperties);
            final Optional<String> gitRepoSlug = this.gitRepoSlug(gitProperties, systemProperties);
            final Optional<String> originRepoSlug = ORIGIN_REPO_SLUG.getValue(gitProperties, systemProperties, userProperties);
            final TravisCiVariables travisCi = new TravisCiVariables(systemProperties);

            return Optional.of(
                originRepoSlug.isPresent() && gitRepoSlug.isPresent() && gitRepoSlug.get().equals(originRepoSlug.get())
                    && !travisCi.isPullRequestEvent()
                    && !appveyor.isPullRequest()
                    ? BOOL_STRING_TRUE
                    : BOOL_STRING_FALSE
            );
        }
    },
    ORIGIN_REPO_SLUG("origin.repo.slug", "unknown/unknown"),

    PMD_RULESET_LOCATION("pmd.ruleset.location",
        "https://raw.githubusercontent.com/ci-and-cd/maven-build/master/src/main/pmd/pmd-ruleset-6.8.0.xml"),

    PRIVATE_GIT_AUTH_TOKEN("private.git.auth.token") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_PRIVATE::equals).orElse(FALSE);

            return infrastructureMatch
                ? GIT_AUTH_TOKEN.findInProperties(systemProperties, userProperties)
                : Optional.empty();
        }
    },
    PRIVATE_GIT_PREFIX("private.git.prefix", "http://gitlab") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.findInProperties( systemProperties, userProperties)
                .map(INFRASTRUCTURE_PRIVATE::equals).orElse(FALSE);

            return infrastructureMatch
                ? GIT_PREFIX.findInProperties(systemProperties, userProperties)
                : Optional.empty();
        }
    },
    PRIVATE_NEXUS3_REPOSITORY("private.nexus3.repository", "http://nexus3:28081/nexus/repository") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean infrastructureMatch = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_PRIVATE::equals).orElse(FALSE);

            return infrastructureMatch
                ? NEXUS3.getValue(gitProperties, systemProperties, userProperties).map(value -> value + "/nexus/repository")
                : Optional.empty();
        }
    },
    PRIVATE_SONARQUBE_HOST_URL("private.sonarqube.host.url", "http://sonarqube:9000"),
    /**
     * Auto determine current build publish channel by current build ref name.<br/>
     * snapshot or release
     */
    PUBLISH_CHANNEL("publish.channel", "snapshot") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String result;

            final String refName = GIT_REF_NAME.getValue(gitProperties, systemProperties, userProperties).orElse("");
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
    PUBLISH_TO_REPO("publish.to.repo") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String result;

            final String refName = GIT_REF_NAME.getValue(gitProperties, systemProperties, userProperties).orElse("");
            final boolean originRepo = ORIGIN_REPO.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            if (originRepo) {
                if (GIT_REF_NAME_DEVELOP.equals(refName)
                    || refName.startsWith(BRANCH_PREFIX_FEATURE)
                    || refName.startsWith(BRANCH_PREFIX_HOTFIX)
                    || refName.startsWith(BRANCH_PREFIX_RELEASE)
                    || refName.startsWith(BRANCH_PREFIX_SUPPORT)
                ) {
                    result = BOOL_STRING_TRUE;
                } else {
                    result = BOOL_STRING_FALSE;
                }
            } else {
                result = refName.startsWith(BRANCH_PREFIX_FEATURE) ? BOOL_STRING_TRUE : BOOL_STRING_FALSE;
            }

            return Optional.of(result);
        }
    },
    SITE("site", BOOL_STRING_FALSE),
    SITE_PATH("site.path", "${site.path.prefix}/${publish.channel}"),
    SITE_PATH_PREFIX("site.path.prefix") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean site = SITE.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            final Optional<String> result;
            if (site) {
                final Boolean openSource = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                    .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

                final Optional<String> gitRepoSlug = this.gitRepoSlug(gitProperties, systemProperties);
                result = openSource ? gitRepoSlug.map(slug -> slug.split("/")[1]) : gitRepoSlug;
            } else {
                result = Optional.empty();
            }
            return result;
        }
    },
    SONAR("sonar"),
    SONAR_HOST_URL("sonar.host.url") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean sonar = SONAR.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return sonar
                ? INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(infrastructure -> {
                    final String value;
                    switch (infrastructure) {
                        case INFRASTRUCTURE_OPENSOURCE:
                            value = OPENSOURCE_SONARQUBE_HOST_URL
                                .getValue(gitProperties, systemProperties, userProperties).orElse(null);
                            break;
                        case INFRASTRUCTURE_PRIVATE:
                            value = PRIVATE_SONARQUBE_HOST_URL
                                .getValue(gitProperties, systemProperties, userProperties).orElse(null);
                            break;
                        default:
                            value = null;
                            break;
                    }
                    return value;
                })
                : Optional.empty();
        }
    },
    SONAR_LOGIN("sonar.login") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean sonar = SONAR.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return sonar
                ? super.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    SONAR_ORGANIZATION("sonar.organization") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Boolean sonar = SONAR.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);
            final Boolean openSource = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            return sonar && openSource
                ? super.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    SONAR_PASSWORD("sonar.password") {
        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean sonar = SONAR.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return sonar
                ? super.getValue(gitProperties, systemProperties, userProperties)
                : Optional.empty();
        }
    },
    WAGON_MERGEMAVENREPOS_ARTIFACTDIR("wagon.merge-maven-repos.artifactDir", "${project.groupId}/${project.artifactId}"),
    WAGON_MERGEMAVENREPOS_SOURCE("wagon.merge-maven-repos.source", "${user.home}/.ci-and-cd/local-deploy/${git.commit.id}") {
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
    WAGON_MERGEMAVENREPOS_TARGET("wagon.merge-maven-repos.target", "${private.nexus3.repository}/maven-${publish.channel}s"),
    WAGON_MERGEMAVENREPOS_TARGETID("wagon.merge-maven-repos.targetId", "private-nexus3-${publish.channel}s"),
    ;

    private final String defaultValue;
    private final String envVariableName;
    private final String propertyName;
    private final String systemPropertyName;

    CiOption(final String propertyName, final String defaultValue) {
        if (!name(propertyName).equals(this.name())) {
            throw new IllegalArgumentException("invalid name " + this.name() + " property name " + propertyName);
        }

        this.defaultValue = defaultValue;
        this.envVariableName = envVariableName(propertyName);
        this.propertyName = propertyName;
        this.systemPropertyName = systemPropertyName(propertyName);
    }

    CiOption(final String propertyName) {
        this(propertyName, null);
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

    /**
     * Calculate value.
     *
     * @param gitProperties    gitProperties
     * @param systemProperties systemProperties
     * @param userProperties   userProperties
     * @return Optional value
     */
    protected Optional<String> calculateValue(
        final GitProperties gitProperties,
        final Properties systemProperties,
        final Properties userProperties
    ) {
        return Optional.empty();
    }

    protected Optional<String> findInProperties(
        final Properties systemProperties,
        final Properties userProperties
    ) {
        final Optional<String> systemProperty = getOptionValue(this, systemProperties, CiOption::getSystemPropertyName);
        return systemProperty.isPresent()
            ? systemProperty
            : getOptionValue(this, userProperties, CiOption::getPropertyName);
    }

    /**
     * Get value.
     *
     * @param gitProperties    gitProperties
     * @param systemProperties systemProperties
     * @param userProperties   userProperties
     * @return Optional value
     */
    public Optional<String> getValue(
        final GitProperties gitProperties,
        final Properties systemProperties,
        final Properties userProperties
    ) {
        final Optional<String> foundInProperties = this.findInProperties(systemProperties, userProperties);
        final Optional<String> value = foundInProperties.isPresent()
            ? foundInProperties
            : this.calculateValue(gitProperties, systemProperties, userProperties);
        return value.isPresent() ? value : this.getDefaultValue();
    }

    /**
     * Get slug info of current repository (directory).
     *
     * @param gitProperties    gitProperties
     * @param systemProperties systemProperties
     * @return 'group/project' or 'owner/project'
     */
    public Optional<String> gitRepoSlug(
        final GitProperties gitProperties,
        final Properties systemProperties
    ) {
        final Optional<String> appveyorRepoSlug = new AppveyorVariables(systemProperties).repoSlug();
        final Optional<String> gitlabCiRepoSlug = new GitlabCiVariables(systemProperties).repoSlug();
        final Optional<String> travisRepoSlug = new TravisCiVariables(systemProperties).repoSlug();

        final Optional<String> result;
        if (appveyorRepoSlug.isPresent()) {
            result = appveyorRepoSlug;
        } else if (gitlabCiRepoSlug.isPresent()) {
            result = gitlabCiRepoSlug;
        } else if (travisRepoSlug.isPresent()) {
            result = travisRepoSlug;
        } else {
            final Optional<String> gitRemoteOriginUrl = gitProperties.remoteOriginUrl();
            if (gitRemoteOriginUrl.isPresent()) {
                result = gitRepoSlugFromUrl(gitRemoteOriginUrl.get());
            } else {
                result = Optional.empty();
            }
        }

        return result;
    }

    /**
     * Set value into properties, use defaultValue if value absent.
     *
     * @param gitProperties    gitProperties
     * @param systemProperties systemProperties
     * @param userProperties   userProperties
     * @param properties       properties to set key/value in
     * @return Optional value
     */
    public Optional<String> setProperties(
        final GitProperties gitProperties,
        final Properties systemProperties,
        final Properties userProperties,
        final Properties properties
    ) {
        final Optional<String> result;

        final String defaultVal = this.getDefaultValue().orElse(null);
        final Optional<String> foundInProperties = this.findInProperties(systemProperties, userProperties);
        if (foundInProperties.isPresent()) { // found in properties
            final Optional<String> got = this.getValue(gitProperties, systemProperties, userProperties);
            if (got.map(value -> value.equals(foundInProperties.get())).orElse(FALSE)) {
                properties.setProperty(this.getPropertyName(), foundInProperties.get());
            } else { // getValue is overridden by custom CiOption impl (got present and not equals to value found in properties).
                // final boolean gotDefaultValue = got.map(value -> value.equals(defaultVal)).orElse(FALSE);
                got.ifPresent(value -> properties.setProperty(this.getPropertyName(), value));
            }

            result = foundInProperties;
        } else { // not found in properties
            final Optional<String> calculated = this.calculateValue(gitProperties, systemProperties, userProperties);
            final String propertyValue = calculated.orElse(defaultVal);
            if (propertyValue != null) {
                properties.setProperty(this.getPropertyName(), propertyValue);
            }

            result = Optional.ofNullable(propertyValue);
        }

        return result;
    }

    public static String envVariableName(final String propertyName) {
        final String name = name(propertyName);
        return name.startsWith("CI_OPT_") ? name : "CI_OPT_" + name;
    }

    private static Optional<String> getOptionValue(
        final CiOption ciOption,
        final Properties properties,
        final Function<CiOption, String> nameFunction
    ) {
        final String key = nameFunction.apply(ciOption);
        return Optional.ofNullable(properties.getProperty(key));
    }

    static Optional<File> mavenMultiModuleProjectDirectory(final Properties systemProperties) {
        final Optional<File> result;
        if (systemProperties != null) {
            final String value = systemProperties.getProperty("maven.multiModuleProjectDirectory");
            result = Optional.ofNullable(value != null ? new File(value) : null);
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private static String name(final String propertyName) {
        if (isEmpty(propertyName)) {
            throw new IllegalArgumentException("propertyName must not empty");
        }
        return propertyName.replaceAll("-", "").replaceAll("\\.", "_").toUpperCase();
    }

    static String rootProjectPathname(final Properties systemProperties) {
        final File directory = mavenMultiModuleProjectDirectory(systemProperties).orElseGet(() -> new File(systemUserDir()));
        return SupportFunction.pathname(directory);
    }

    public static String systemPropertyName(final String propertyName) {
        return "env." + envVariableName(propertyName);
    }
}
