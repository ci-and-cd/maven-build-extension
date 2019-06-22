package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_FEATURE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_HOTFIX;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.BRANCH_PREFIX_SUPPORT;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_MASTER;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_DEFAULT;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_PRIVATE;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_RELEASE;
import static top.infra.maven.extension.mavenbuild.Constants.PUBLISH_CHANNEL_SNAPSHOT;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_XML;
import static top.infra.maven.extension.mavenbuild.Docker.dockerHost;
import static top.infra.maven.extension.mavenbuild.Docker.dockerfiles;
import static top.infra.maven.extension.mavenbuild.Gpg.gpgVersionGreater;
import static top.infra.maven.extension.mavenbuild.utils.FileUtils.find;
import static top.infra.maven.extension.mavenbuild.utils.FileUtils.pathname;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.exec;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.existsInPath;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemJavaIoTmp;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemJavaVersion;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemUserDir;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemUserHome;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.infra.maven.extension.mavenbuild.utils.SupportFunction;

public enum CiOption {
    /**
     * maven-failsafe-plugin and maven-surefire-plugin's configuration argLine.
     */
    ARGLINE("argLine", "") {
        private Optional<String> addtionalArgs(
            final String argLine,
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> result;

            final Optional<Integer> javaVersion = systemJavaVersion();
            if (javaVersion.map(version -> version >= 9).orElse(FALSE)) {
                final String addExports = " --add-exports java.base/jdk.internal.loader=ALL-UNNAMED"
                    + " --add-exports java.base/sun.security.ssl=ALL-UNNAMED"
                    + " --add-opens java.base/jdk.internal.loader=ALL-UNNAMED"
                    + " --add-opens java.base/sun.security.ssl=ALL-UNNAMED";

                final Optional<String> addModules = JAVA_ADDMODULES.getValue(gitProperties, systemProperties, userProperties);
                final Optional<String> argLineWithModules;
                if (addModules.isPresent() && (argLine == null || !argLine.contains("--add-modules"))) {
                    argLineWithModules = Optional.of(String.format("--add-modules %s", addModules.get()));
                } else {
                    argLineWithModules = Optional.empty();
                }

                if (javaVersion.map(version -> version >= 11).orElse(FALSE)) {
                    result = Optional.of(String.format("%s --illegal-access=permit %s", addExports, argLineWithModules.orElse("")));
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

    FAST("fast"),

    ENFORCER_SKIP("enforcer.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties);
        }
    },

    // /**
    //  * Got warning on maven-surefire-plugin's test goal.
    //  * [WARNING] file.encoding cannot be set as system property, use &lt;argLine&gt;-Dfile.encoding=...&lt;/argLine&gt; instead
    //  */
    // @Deprecated
    // FILE_ENCODING("file.encoding", UTF_8.name()),

    /**
     * Custom property.
     * maven.javadoc.skip and maven.source.skip
     */
    MAVEN_ARTIFACTS_SKIP("maven.artifacts.skip", BOOL_STRING_FALSE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    MAVEN_CLEAN_SKIP("maven.clean.skip", BOOL_STRING_TRUE),
    MAVEN_COMPILER_ENCODING("maven.compiler.encoding", UTF_8.name()),
    MAVEN_INSTALL_SKIP("maven.install.skip"),
    MAVEN_JAVADOC_SKIP("maven.javadoc.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean artifactsSkip = MAVEN_ARTIFACTS_SKIP.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);
            return Optional.of(FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean)
                .filter(fast -> fast || artifactsSkip)
                .map(fast -> BOOL_STRING_TRUE)
                .orElse(BOOL_STRING_FALSE));
        }
    },

    /**
     * See: "https://maven.apache.org/plugins/maven-site-plugin/site-mojo.html".
     * <p/>
     * Convenience parameter that allows you to disable report generation.
     */
    GENERATEREPORTS("generateReports") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean)
                .filter(fast -> fast)
                .map(fast -> BOOL_STRING_FALSE);
        }
    },
    /**
     * See: "https://maven.apache.org/plugins/maven-site-plugin/site-mojo.html".
     * <p/>
     * Set this to 'true' to skip site generation and staging.
     */
    MAVEN_SITE_SKIP("maven.site.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(SITE.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE) ? null : BOOL_STRING_TRUE);
        }
    },
    MAVEN_SITE_DEPLOY_SKIP("maven.site.deploy.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return MAVEN_SITE_SKIP.calculateValue(gitProperties, systemProperties, userProperties);
        }
    },
    MAVEN_SOURCE_SKIP("maven.source.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return MAVEN_JAVADOC_SKIP.calculateValue(gitProperties, systemProperties, userProperties);
        }
    },

    MAVEN_TEST_FAILURE_IGNORE("maven.test.failure.ignore", BOOL_STRING_FALSE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    /**
     * Skip test-compile and skipTests and skipITs.
     * <p/>
     * maven.test.skip property skips compiling the tests. maven.test.skip is honored by Surefire, Failsafe and the Compiler Plugin.
     */
    MAVEN_TEST_SKIP("maven.test.skip", BOOL_STRING_FALSE),
    PROJECT_BUILD_SOURCEENCODING("project.build.sourceEncoding", UTF_8.name()),
    PROJECT_REPORTING_OUTPUTENCODING("project.reporting.outputEncoding", UTF_8.name()),
    /**
     * Since skipTests is also supported by the Surefire Plugin, this will have the effect of not running any tests.
     * If, instead, you want to skip only the integration tests being run by the Failsafe Plugin,
     * you would use the skipITs property instead.
     * see: https://maven.apache.org/surefire/maven-failsafe-plugin/examples/skipping-tests.html
     */
    SKIPITS("skipITs", BOOL_STRING_FALSE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    /**
     * Keep test-compile but do not run tests.
     * <p/>
     * see: https://maven.apache.org/surefire/maven-surefire-plugin/examples/skipping-tests.html
     */
    SKIPTESTS("skipTests", BOOL_STRING_FALSE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return FAST.getValue(gitProperties, systemProperties, userProperties);
        }
    },

    //
    CACHE_INFRASTRUCTURE_PATH("cache.infrastructure.path") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            // We need a stable global cache path
            final String infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .orElse(INFRASTRUCTURE_DEFAULT); // same default value as
            return Optional.of(Paths.get(systemUserHome(), ".ci-and-cd", infrastructure).toString());
        }
    },
    //
    CACHE_SESSION_PATH("cache.session.path") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            // We need a stable path prefix for cache
            // java.io.tmp changed on every time java process start.
            final String prefix = Paths.get(systemUserHome(), ".ci-and-cd", "tmp").toString();
            final String commitId = gitProperties.commitId().map(value -> value.substring(0, 8)).orElse("unknown-commit");
            final String pathname = Paths.get(prefix, SupportFunction.uniqueKey(), commitId).toString();
            return Optional.of(pathname);
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
            final Optional<String> result;
            if (new File(SRC_CI_OPTS_PROPERTIES).exists()) {
                result = Optional.of(SRC_CI_OPTS_PROPERTIES);
            } else {
                final String cacheDirectory = CACHE_INFRASTRUCTURE_PATH.getValue(gitProperties, systemProperties, userProperties)
                    .orElse(systemJavaIoTmp());
                final String cachedCiOptsProperties = Paths.get(cacheDirectory, SRC_CI_OPTS_PROPERTIES).toString();
                result = Optional.of(cachedCiOptsProperties);
            }
            return result;
        }
    },
    // @Deprecated
    // CI_SCRIPT("ci.script"),
    DEPENDENCYCHECK("dependency-check") {
        @Override
        protected Optional<String> calculateValue(
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

    /**
     * Docker enabled.
     */
    DOCKER("docker") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean result;

            if (existsInPath("docker")) {
                // TODO Support named pipe (for windows).
                // Unix sock file
                // [[ -f /var/run/docker.sock ]] || [[ -L /var/run/docker.sock ]]
                final String dockerSockFile = "/var/run/docker.sock"; // TODO windows ?
                final boolean dockerSockFilePresent = Paths.get(dockerSockFile).toFile().exists();
                // TCP
                final boolean envDockerHostPresent = dockerHost(systemProperties).isPresent();
                // [[ -n "$(find . -name '*Docker*')" ]] || [[ -n "$(find . -name '*docker-compose*.yml')" ]]
                final int dockerComposeFilesCount = find(".", "*docker-compose*.yml").size();
                final boolean dockerFilesFound = !dockerfiles().isEmpty() || dockerComposeFilesCount > 0;

                result = dockerFilesFound && (dockerSockFilePresent || envDockerHostPresent);
            } else {
                result = false;
            }

            return Optional.of(result ? BOOL_STRING_TRUE : BOOL_STRING_FALSE);
        }
    },
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
            final Optional<String> dockerRegistryUrl = DOCKER_REGISTRY_URL.getValue(gitProperties, systemProperties, userProperties);
            return dockerRegistryUrl
                .flatMap(CiOption::domainOrHostFromUrl)
                .flatMap(value -> Optional.ofNullable(value.endsWith("docker.io") ? null : value));
        }

        @Override
        public Optional<String> getValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return super.getValue(gitProperties, systemProperties, userProperties)
                .map(value -> value.endsWith("docker.io") ? null : value);
        }
    },
    DOCKER_REGISTRY_PASS("docker.registry.pass"),
    DOCKER_REGISTRY_URL("docker.registry.url"),
    DOCKER_REGISTRY_USER("docker.registry.user"),

    /**
     * com.spotify:docker-maven-plugin
     */
    DOCKER_IMAGENAME("docker.imageName") {
        @Override
        protected Optional<String> calculateValue(
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
        protected Optional<String> calculateValue(
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

    GIT_AUTH_TOKEN("git.auth.token") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .map(infra ->
                    Arrays.stream(CiOption.values())
                        .filter(ciOption -> ciOption.name().equals(infra.toUpperCase() + "_" + GIT_AUTH_TOKEN.name()))
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
    GITHUB_GLOBAL_OAUTH2TOKEN("github.global.oauth2Token") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return GIT_AUTH_TOKEN.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    GITHUB_GLOBAL_REPOSITORYNAME("github.global.repositoryName") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return SITE_PATH_PREFIX.getValue(gitProperties, systemProperties, userProperties);
        }
    },
    GITHUB_GLOBAL_REPOSITORYOWNER("github.global.repositoryOwner", "unknown-owner") {
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
    },
    GPG_KEYID("gpg.keyid"),
    GPG_KEYNAME("gpg.keyname"),
    GPG_LOOPBACK("gpg.loopback") {
        @Override
        protected Optional<String> calculateValue(
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
                result = INFRASTRUCTURE_DEFAULT;
            }

            return Optional.of(result);
        }
    },

    /**
     * Run jacoco if true, skip jacoco and enable cobertura if false, skip bothe jacoco and cobertura if absent.
     */
    JACOCO("jacoco") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final boolean skip = FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE);

            return Optional.ofNullable(skip ? null : BOOL_STRING_TRUE);
        }
    },
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
    LINKXREF("linkXRef", BOOL_STRING_TRUE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE) ? BOOL_STRING_FALSE : null);
        }
    },
    MAVEN_BUILD_OPTS_REPO("maven.build.opts.repo") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties)
                .orElse(INFRASTRUCTURE_DEFAULT);

            final String repoOwner = "ci-and-cd";
            final String repoName = "maven-build-opts-"
                + (INFRASTRUCTURE_DEFAULT.equals(infrastructure) ? INFRASTRUCTURE_OPENSOURCE : infrastructure);

            return GIT_PREFIX.getValue(gitProperties, systemProperties, userProperties)
                .map(gitPrefix -> String.format("%s/%s/%s", gitPrefix, repoOwner, repoName));
        }
    },
    MAVEN_BUILD_OPTS_REPO_REF("maven.build.opts.repo.ref", GIT_REF_NAME_MASTER),
    MAVEN_CENTRAL_PASS("maven.central.pass"),
    MAVEN_CENTRAL_USER("maven.central.user"),
    MAVEN_SETTINGS_FILE("maven.settings.file") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String rootProjectPathname = rootProjectPathname(systemProperties);
            final String settingsFile = Paths.get(rootProjectPathname, SRC_MAVEN_SETTINGS_XML).toString();

            final Optional<String> result;

            if (Paths.get(settingsFile).toFile().exists()) {
                result = Optional.of(settingsFile);
            } else {
                final String cacheDir = CACHE_INFRASTRUCTURE_PATH.getValue(gitProperties, systemProperties, userProperties)
                    .orElse(systemJavaIoTmp());

                final String filename = "settings"
                    + INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties).map(infra -> "-" + infra).orElse("")
                    + ".xml";

                final String targetFile = Paths.get(cacheDir, filename).toString();

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
                if (Paths.get(file).toFile().exists()) {
                    properties.setProperty("settings.security", file);
                }
            });

            return result;
        }
    },
    MVN_DEPLOY_PUBLISH_SEGREGATION("mvn.deploy.publish.segregation"),
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
    OPENSOURCE_SONARQUBE_HOST_URL("opensource.sonarqube.host.url", "https://sonarqube.com"),
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

    PMD_SKIP("pmd.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(GENERATEREPORTS.calculateValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(TRUE) ? null : BOOL_STRING_TRUE);
        }
    },

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
            final Boolean infrastructureMatch = INFRASTRUCTURE.findInProperties(systemProperties, userProperties)
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
    SITE("site", BOOL_STRING_FALSE) {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(GENERATEREPORTS.calculateValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(TRUE) ? null : BOOL_STRING_FALSE);
        }
    },
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
    SONAR_BUILDBREAKER_SKIP("sonar.buildbreaker.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(FAST.getValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(FALSE) ? BOOL_STRING_TRUE : null);
        }
    },
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
                            value = String.format("${%s.sonarqube.host.url}", infrastructure);
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
    SPOTBUGS_SKIP("spotbugs.skip") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            return Optional.ofNullable(GENERATEREPORTS.calculateValue(gitProperties, systemProperties, userProperties)
                .map(Boolean::parseBoolean).orElse(TRUE) ? null : BOOL_STRING_TRUE);
        }
    },
    WAGON_MERGEMAVENREPOS_ARTIFACTDIR("wagon.merge-maven-repos.artifactDir", "${project.groupId}/${project.artifactId}") {
        // TODO System.setProperty("wagon.merge-maven-repos.artifactDir", "${project.groupId}".replace('.', '/') + "/${project.artifactId}")
    },
    WAGON_MERGEMAVENREPOS_SOURCE("wagon.merge-maven-repos.source") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final String commitId = gitProperties.commitId().map(value -> value.substring(0, 8)).orElse("unknown-commit");

            // final String prefix = Paths.get(systemUserHome(), ".ci-and-cd", "local-deploy").toString();
            final String prefix = Paths.get(systemUserDir(), ".mvn", "wagonRepository").toString();

            return Optional.of(Paths.get(prefix, commitId).toString());
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
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties);
            final boolean infraOpenSource = infrastructure.map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            final Optional<String> publishChannel = PUBLISH_CHANNEL.getValue(gitProperties, systemProperties, userProperties);
            final boolean publishRelease = publishChannel.map(PUBLISH_CHANNEL_RELEASE::equals).orElse(FALSE);

            final String result;
            if (infraOpenSource) {
                if (publishRelease) {
                    result = "https://oss.sonatype.org/service/local/staging/deploy/maven2";
                } else {
                    result = "https://oss.sonatype.org/content/repositories/snapshots";
                }
            } else {
                result = infrastructure
                    .map(value -> String.format("${%s.nexus3.repository}/maven-${publish.channel}s", value))
                    .orElse(null);
            }
            return Optional.ofNullable(result);
        }
    },
    WAGON_MERGEMAVENREPOS_TARGETID("wagon.merge-maven-repos.targetId") {
        @Override
        protected Optional<String> calculateValue(
            final GitProperties gitProperties,
            final Properties systemProperties,
            final Properties userProperties
        ) {
            final Optional<String> infrastructure = INFRASTRUCTURE.getValue(gitProperties, systemProperties, userProperties);
            final boolean infraOpenSource = infrastructure.map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);

            final String result;
            if (infraOpenSource) {
                result = "OSSRH-${publish.channel}s";
            } else {
                result = infrastructure
                    .map(value -> String.format("%s-nexus3-${publish.channel}s", value))
                    .orElse(null);
            }
            return Optional.ofNullable(result);
        }
    },
    ;


    public static final Pattern PATTERN_VARS_ENV_DOT_CI = Pattern.compile("^env\\.CI_.+");
    public static final Pattern PATTERN_GIT_REPO_SLUG = Pattern.compile(".*[:/]([^/]+(/[^/.]+))(\\.git)?");
    private static final Pattern PATTERN_URL = Pattern.compile("^(.+://|git@)([^/\\:]+(:\\d+)?).*$");

    private final String defaultValue;
    private final String envVariableName;
    private final String propertyName;
    private final String systemPropertyName;

    CiOption(final String propertyName) {
        this(propertyName, null);
    }

    CiOption(final String propertyName, final String defaultValue) {
        if (!name(propertyName).equals(this.name())) {
            throw new IllegalArgumentException(String.format("invalid property name [%s] for enum name [%s]", this.name(), propertyName));
        }

        this.defaultValue = defaultValue;
        this.envVariableName = envVariableName(propertyName);
        this.propertyName = propertyName;
        this.systemPropertyName = systemPropertyName(propertyName);
    }

    public static String envVariableName(final String propertyName) {
        final String name = name(propertyName);
        return name.startsWith("CI_OPT_") ? name : "CI_OPT_" + name;
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

    private static String name(final String propertyName) {
        if (isEmpty(propertyName)) {
            throw new IllegalArgumentException("propertyName must not empty");
        }
        return propertyName.replaceAll("-", "").replaceAll("\\.", "_").toUpperCase();
    }

    public static String systemPropertyName(final String propertyName) {
        return String.format("env.%s", envVariableName(propertyName));
    }

    static String rootProjectPathname(final Properties systemProperties) {
        final File directory = mavenMultiModuleProjectDirectory(systemProperties).orElseGet(() -> new File(systemUserDir()));
        return pathname(directory);
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

    private static Optional<String> domainOrHostFromUrl(final String url) {
        final Optional<String> result;
        final Matcher matcher = PATTERN_URL.matcher(url);
        if (matcher.matches()) {
            result = Optional.ofNullable(matcher.group(2));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private static Optional<String> urlWithoutPath(final String url) {
        final Optional<String> result;
        final Matcher matcher = PATTERN_URL.matcher(url);
        if (matcher.matches()) {
            result = Optional.of(matcher.group(1) + matcher.group(2));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    public String getEnvVariableName() {
        return this.envVariableName;
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
            final String propertyValue = calculated.orElseGet(() -> this.getDefaultValue().orElse(null));
            if (propertyValue != null) {
                properties.setProperty(this.getPropertyName(), propertyValue);
            }

            result = Optional.ofNullable(propertyValue);
        }

        return result;
    }

    protected Optional<String> findInProperties(
        final Properties systemProperties,
        final Properties userProperties
    ) {
        // systemProperty first
        final Optional<String> systemProperty = getOptionValue(this, systemProperties, CiOption::getSystemPropertyName);
        return systemProperty.isPresent()
            ? systemProperty
            : getOptionValue(this, userProperties, CiOption::getPropertyName);

        // // userProperty first
        // final Optional<String> userProperty = getOptionValue(this, userProperties, CiOption::getPropertyName);
        // return userProperty.isPresent()
        //     ? userProperty
        //     : getOptionValue(this, systemProperties, CiOption::getSystemPropertyName);
    }

    public String getPropertyName() {
        return this.propertyName;
    }

    public String getSystemPropertyName() {
        return this.systemPropertyName;
    }

    private static Optional<String> getOptionValue(
        final CiOption ciOption,
        final Properties properties,
        final Function<CiOption, String> nameFunction
    ) {
        final String key = nameFunction.apply(ciOption);
        return Optional.ofNullable(properties.getProperty(key));
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

    public Optional<String> getDefaultValue() {
        return Optional.ofNullable(this.defaultValue);
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
}
