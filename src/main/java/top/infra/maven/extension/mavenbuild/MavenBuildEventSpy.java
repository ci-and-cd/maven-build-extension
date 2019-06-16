package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_PASS;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_USER;
import static top.infra.maven.extension.mavenbuild.CiOption.GITHUB_GLOBAL_REPOSITORYOWNER;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_EXECUTABLE;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_KEYID;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_KEYNAME;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_PASSPHRASE;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_SETTINGS_FILE;
import static top.infra.maven.extension.mavenbuild.CiOption.MVN_DEPLOY_PUBLISH_SEGREGATION;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_TO_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.SITE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.Constants.USER_PROPERTY_SETTINGS_LOCALREPOSITORY;
import static top.infra.maven.extension.mavenbuild.Docker.dockerHost;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByBuildProject;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByReadPom;
import static top.infra.maven.extension.mavenbuild.MavenServerInterceptor.absentVarsInSettingsXml;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.systemUserHome;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.toolchain.building.ToolchainsBuildingRequest;
import org.eclipse.aether.RepositorySystemSession;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;

/**
 * Main entry point. Reads properties and exposes them as user properties.
 * Existing user properties will not be overwritten.
 */
// @org.codehaus.plexus.component.annotations.Component(role = org.apache.maven.eventspy.EventSpy.class)
@Named
@Singleton
public class MavenBuildEventSpy extends AbstractEventSpy {

    private static final String GOAL_CLEAN = "clean";
    private static final String GOAL_DEPLOY = "deploy";
    private static final String GOAL_INSTALL = "install";

    private static final String NA = "N/A";
    private static final Pattern PATTERN_CI_ENV_VARS = Pattern.compile("^env\\.CI_.+");

    private final Logger logger;

    private final String homeDir;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final RuntimeInformation runtime;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final ProjectBuilder projectBuilder;

    private final DefaultRepositorySystemSessionFactory repositorySessionFactory;

    private final ProjectBuilderActivatorModelResolver resolver;

    private final SettingsDecrypter settingsDecrypter;

    private String settingsLocalRepository;

    private CiOptionAccessor ciOpts;

    private String mavenSettingsPathname;

    private MavenServerInterceptor mavenServerInterceptor;

    private String rootProjectPathname;

    /**
     * Constructor.
     *
     * @param logger                   inject logger {@link org.codehaus.plexus.logging.Logger}
     * @param runtime                  inject RuntimeInformation of maven-core
     * @param projectBuilder           inject ProjectBuilder of maven-core
     * @param repositorySessionFactory inject DefaultRepositorySystemSessionFactory of maven-core
     * @param settingsDecrypter        settingsDecrypter
     * @param mavenServerInterceptor   mavenServerInterceptor
     * @param resolver                 resolver
     */
    @Inject
    public MavenBuildEventSpy(
        final org.codehaus.plexus.logging.Logger logger,
        final RuntimeInformation runtime,
        final ProjectBuilder projectBuilder,
        final DefaultRepositorySystemSessionFactory repositorySessionFactory,
        final SettingsDecrypter settingsDecrypter,
        final MavenServerInterceptor mavenServerInterceptor,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        this.homeDir = systemUserHome();
        this.logger = new LoggerPlexusImpl(logger);
        this.runtime = runtime;
        this.projectBuilder = projectBuilder;
        this.repositorySessionFactory = repositorySessionFactory;
        this.resolver = resolver;
        this.settingsDecrypter = settingsDecrypter;
        this.mavenServerInterceptor = mavenServerInterceptor;

        this.settingsLocalRepository = null;
        this.mavenSettingsPathname = null;
        this.rootProjectPathname = null;
    }

    @Override
    public void init(final Context context) throws Exception {
        try {
            this.onInit(context);
        } catch (final Exception ex) {
            logger.error("Exception on init.", ex);
            System.exit(1);
        }
    }

    @Override
    public void onEvent(final Object event) throws Exception {
        try {
            if (event instanceof SettingsBuildingRequest) {
                final SettingsBuildingRequest request = (SettingsBuildingRequest) event;

                this.settingsLocalRepository = request.getUserProperties().getProperty(USER_PROPERTY_SETTINGS_LOCALREPOSITORY);

                this.onSettingsBuildingRequest(request, this.homeDir, this.ciOpts);
            } else if (event instanceof SettingsBuildingResult) {
                final SettingsBuildingResult result = (SettingsBuildingResult) event;

                // Allow override value of localRepository in settings.xml by user property settings.localRepository.
                // e.g. ./mvnw -Dsettings.localRepository=${HOME}/.m3/repository clean install
                if (!isEmpty(this.settingsLocalRepository)) {
                    final String currentValue = result.getEffectiveSettings().getLocalRepository();
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format(
                            "Override localRepository [%s] to [%s]", currentValue, this.settingsLocalRepository));
                    }
                    result.getEffectiveSettings().setLocalRepository(this.settingsLocalRepository);
                }
            } else if (event instanceof ToolchainsBuildingRequest) {
                final ToolchainsBuildingRequest request = (ToolchainsBuildingRequest) event;

                this.onToolchainsBuildingRequest(request, this.homeDir, this.ciOpts);
            } else if (event instanceof MavenExecutionRequest) {
                final MavenExecutionRequest request = (MavenExecutionRequest) event;

                if (isEmpty(this.settingsLocalRepository)) {
                    this.settingsLocalRepository = request.getLocalRepository().getBasedir();
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("Current localRepository [%s]", this.settingsLocalRepository));
                    }
                    request.getUserProperties().setProperty(USER_PROPERTY_SETTINGS_LOCALREPOSITORY, this.settingsLocalRepository);
                }

                this.mavenServerInterceptor.checkServers(request.getServers());

                final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();
                if (projectBuildingRequest != null) {
                    // To make profile activation conditions work
                    SupportFunction.merge(request.getSystemProperties(), projectBuildingRequest.getSystemProperties());
                    SupportFunction.merge(request.getUserProperties(), projectBuildingRequest.getUserProperties());
                    if (logger.isInfoEnabled()) {
                        logger.info("     >>>>> projectBuildingRequest (ProfileActivationContext) systemProperties >>>>>");
                        logger.info(SupportFunction.toString(projectBuildingRequest.getSystemProperties(), PATTERN_CI_ENV_VARS));
                        logger.info("     <<<<< projectBuildingRequest (ProfileActivationContext) systemProperties <<<<<");

                        logger.info("     >>>>> projectBuildingRequest (ProfileActivationContext) userProperties >>>>>");
                        logger.info(SupportFunction.toString(projectBuildingRequest.getUserProperties(), null));
                        logger.info("     <<<<< projectBuildingRequest (ProfileActivationContext) userProperties <<<<<");
                    }

                    final boolean repositorySystemSessionNull = this.createRepositorySystemSessionIfAbsent(request);
                    try {
                        this.resolver.setProjectBuildingRequest(projectBuildingRequest);

                        final List<String> goals = this.onMavenExecutionRequest(request, this.homeDir, this.ciOpts);
                        this.prepareDocker(goals, this.homeDir, this.ciOpts);
                    } finally {
                        if (repositorySystemSessionNull) {
                            projectBuildingRequest.setRepositorySession(null);
                        }
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onEvent MavenExecutionRequest %s but projectBuildingRequest is null.", request));
                    }
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("onEvent %s", event));
                }
            }
        } catch (final Exception ex) {
            logger.error(String.format("Exception on handling event [%s].", event), ex);
            System.exit(1);
        }

        super.onEvent(event);
    }

    void onInit(final Context context) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("init with context [%s]", context));
        }

        final Map<String, Object> contextData = context.getData();
        final Properties systemProperties = (Properties) contextData.get("systemProperties");
        final Properties userProperties = (Properties) contextData.get("userProperties");

        if (logger.isInfoEnabled()) {
            contextData.keySet().stream().sorted().forEach(k -> {
                final Object v = contextData.get(k);
                if (v instanceof Properties) {
                    logger.info(String.format("contextData found properties %s => ", k));
                    logger.info(SupportFunction.toString((Properties) v, null));
                } else {
                    logger.info(String.format("contextData found property   %s => %s", k, v));
                }
            });
        }

        if (logger.isInfoEnabled()) {
            classPathEntries(logger, ClassLoader.getSystemClassLoader()).forEach(entry ->
                logger.info(String.format("                system classpath entry: %s", entry)));
            classPathEntries(logger, Thread.currentThread().getContextClassLoader()).forEach(entry ->
                logger.info(String.format("current thread context classpath entry: %s", entry)));
            classPathEntries(logger, context.getClass().getClassLoader()).forEach(entry ->
                logger.info(String.format("          apache-maven classpath entry: %s", entry)));
            classPathEntries(logger, this.getClass().getClassLoader()).forEach(entry ->
                logger.info(String.format(" maven-build-extension classpath entry: %s", entry)));

            logger.info(">>>>>>>>>> ---------- init systemProperties ---------- >>>>>>>>>>");
            logger.info(SupportFunction.toString(systemProperties, PATTERN_CI_ENV_VARS));
            logger.info("<<<<<<<<<< ---------- init systemProperties ---------- <<<<<<<<<<");
            logger.info(">>>>>>>>>> ---------- init userProperties ---------- >>>>>>>>>>");
            logger.info(SupportFunction.toString(userProperties, null));
            logger.info("<<<<<<<<<< ---------- init userProperties ---------- <<<<<<<<<<");
        }

        this.rootProjectPathname = CiOption.rootProjectPathname(systemProperties);
        final String artifactId = new File(this.rootProjectPathname).getName();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("artifactId: [%s]", artifactId));
        }

        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- build context info ---------- >>>>>>>>>>");
            logger.info(String.format("user.language [%s], user.region [%s], user.timezone [%s]",
                System.getProperty("user.language"), System.getProperty("user.region"), System.getProperty("user.timezone")));
            logger.info(String.format("USER [%s]", System.getProperty("user.name")));
            logger.info(String.format("HOME [%s]", homeDir));
            logger.info(String.format("JAVA_HOME [%s]", System.getProperty("java.home")));
            logger.info(String.format("PWD [%s]", this.rootProjectPathname));

            final String runtimeImplVersion = Runtime.class.getPackage().getImplementationVersion();
            final String javaVersion = runtimeImplVersion != null ? runtimeImplVersion : System.getProperty("java.runtime.version");

            logger.info(String.format("Java version [%s]", javaVersion));
            logger.info(String.format("Maven version [%s]", this.runtime.getMavenVersion()));

            final GitlabCiVariables gitlabCi = new GitlabCiVariables(systemProperties);
            logger.info(String.format(
                "gitlab-ci variables: CI_REF_NAME or CI_COMMIT_REF_NAME: [%s], CI_PROJECT_PATH: [%s], CI_PROJECT_URL: [%s]",
                gitlabCi.refName().orElse(NA),
                gitlabCi.repoSlug().orElse(NA),
                gitlabCi.projectUrl().orElse(NA)));

            final TravisCiVariables travisCi = new TravisCiVariables(systemProperties);
            logger.info(String.format(
                "travis-ci variables: TRAVIS_BRANCH: [%s], TRAVIS_EVENT_TYPE: [%s], TRAVIS_REPO_SLUG: [%s], TRAVIS_PULL_REQUEST: [%s]",
                travisCi.branch().orElse(NA),
                travisCi.eventType().orElse(NA),
                travisCi.repoSlug().orElse(NA),
                travisCi.pullRequest().orElse(NA)));
        }

        final GitProperties gitProperties = GitProperties.newInstance(logger).orElseGet(() -> GitProperties.newBlankInstance(logger));
        this.ciOpts = new CiOptionAccessor(
            logger,
            gitProperties,
            systemProperties,
            userProperties
        );

        logger.info("<<<<<<<<<< ---------- build context info ---------- <<<<<<<<<<");

        this.checkGitAuthToken(this.ciOpts);

        final Optional<String> gitRefName = this.ciOpts.getOption(GIT_REF_NAME);
        if ((!gitRefName.isPresent() || isEmpty(gitRefName.get())) && logger.isWarnEnabled()) {
            logger.warn(String.format("Can not find value of %s (%s)", GIT_REF_NAME.getEnvVariableName(), GIT_REF_NAME.getPropertyName()));
        }

        logger.info(">>>>>>>>>> ---------- load options from file ---------- >>>>>>>>>>");
        // ci options from file
        final Properties loadedProperties = ciOpts.ciOptsFromFile();
        ciOpts.updateSystemProperties(loadedProperties);
        if (logger.isInfoEnabled()) {
            logger.info("    >>>>>>>>>> ---------- loadedProperties ---------- >>>>>>>>>>");
            logger.info(SupportFunction.toString(loadedProperties, PATTERN_CI_ENV_VARS));
            logger.info("    <<<<<<<<<< ---------- loadedProperties ---------- <<<<<<<<<<");
        }

        // github site options

        ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).ifPresent(owner ->
            systemProperties.setProperty(GITHUB_GLOBAL_REPOSITORYOWNER.getSystemPropertyName(), owner));
        logger.info("<<<<<<<<<< ---------- load options from file ---------- <<<<<<<<<<");

        // maven options
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- set options (update userProperties) ---------- >>>>>>>>>>");
        }

        final Properties newProperties = ciOpts.mavenOptsInto(userProperties);

        if (logger.isInfoEnabled()) {
            logger.info(SupportFunction.toString(systemProperties, PATTERN_CI_ENV_VARS));
            logger.info(SupportFunction.toString(userProperties, null));
            final Properties mavenOpts = SupportFunction.toProperties(ciOpts.mavenOpts());
            if (!mavenOpts.isEmpty()) {
                logger.info("MAVEN_OPTS");
                logger.info(SupportFunction.toString(mavenOpts, null));
            } else {
                logger.info("no MAVEN_OPTS present");
            }
            logger.info("<<<<<<<<<< ---------- set options (update userProperties) ---------- <<<<<<<<<<");
        }

        this.mavenSettingsPathname = this.ciOpts.getOption(MAVEN_SETTINGS_FILE).orElse(null);
        final GitRepository gitRepository = this.ciOpts.gitRepository();
        gitRepository.downloadMavenSettingsFile(this.homeDir, this.mavenSettingsPathname);

        this.mavenServerInterceptor.setHomeDir(this.homeDir);
        final Properties absentVarsInSettingsXml = absentVarsInSettingsXml(logger, this.mavenSettingsPathname, systemProperties);
        SupportFunction.merge(absentVarsInSettingsXml, systemProperties);

        gitRepository.downloadMavenToolchainFile(this.homeDir);
    }

    private void onSettingsBuildingRequest(
        final SettingsBuildingRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent SettingsBuildingRequest. request: [%s]", request));
            logger.info(String.format("onEvent SettingsBuildingRequest. globalSettingsFile: [%s]", request.getGlobalSettingsFile()));
            logger.info(String.format("onEvent SettingsBuildingRequest. globalSettingsSource: [%s]", request.getGlobalSettingsSource()));
            logger.info(String.format("onEvent SettingsBuildingRequest. userSettingsFile: [%s]", request.getUserSettingsFile()));
            logger.info(String.format("onEvent SettingsBuildingRequest. userSettingsSource: [%s]", request.getUserSettingsSource()));
        }

        if (this.mavenSettingsPathname != null) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Use userSettingsFile [%s] instead of [%s]",
                    this.mavenSettingsPathname, request.getUserSettingsFile()));
            }

            request.setUserSettingsFile(new File(this.mavenSettingsPathname));
        }
    }

    private void onToolchainsBuildingRequest(
        final ToolchainsBuildingRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent ToolchainsBuildingRequest %s", request));
        }
    }

    private List<String> onMavenExecutionRequest(
        final MavenExecutionRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent MavenExecutionRequest %s", request));
        }

        // final File rootProjectDirectory = ((MavenExecutionRequest) request).getMultiModuleProjectDirectory();

        this.decryptFiles(ciOpts, homeDir);

        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- resolve project version ---------- >>>>>>>>>>");
        }
        // Options are not calculated and merged into projectBuildingRequest this time.
        final File pomFile = request.getPom();
        final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();
        final MavenProjectInfo projectInfo = newProjectInfoByReadPom(logger, pomFile)
            .orElseGet(() -> newProjectInfoByBuildProject(logger, this.projectBuilder, pomFile, projectBuildingRequest));
        if (logger.isInfoEnabled()) {
            logger.info(projectInfo.toString());
        }
        if (logger.isInfoEnabled()) {
            logger.info("<<<<<<<<<< ---------- resolve project version ---------- <<<<<<<<<<");
        }

        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- check project version ---------- >>>>>>>>>>");
        }
        final Entry<Boolean, RuntimeException> resultCheckProjectVersion = ciOpts.checkProjectVersion(projectInfo.getVersion());
        final String gitRefName = ciOpts.getOption(GIT_REF_NAME).orElse("");
        if (resultCheckProjectVersion.getKey()) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Valid version [%s] for ref [%s].", projectInfo.getVersion(), gitRefName));
            }
        } else {
            logger.warn("You should use versions with '-SNAPSHOT' suffix on develop branch or feature branches");
            logger.warn("You should use versions like 1.0.0-SNAPSHOT develop branch");
            logger.warn("You should use versions like 1.0.0-feature-SNAPSHOT or 1.0.0-branch-SNAPSHOT on feature branches");
            logger.warn("You should use versions like 1.0.0 without '-SNAPSHOT' suffix on releases");
            final RuntimeException ex = resultCheckProjectVersion.getValue();
            if (ex == null) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("Invalid version [%s] for ref [%s]", projectInfo.getVersion(), gitRefName));
                }
            } else {
                logger.error(ex.getMessage());
                throw ex;
            }
        }
        if (logger.isInfoEnabled()) {
            logger.info("<<<<<<<<<< ---------- check project version ---------- <<<<<<<<<<");
        }

        final Boolean publishToRepo = ciOpts.getOption(PUBLISH_TO_REPO).map(Boolean::parseBoolean).orElse(FALSE)
            && resultCheckProjectVersion.getKey();
        final List<String> goals = this.editMavenGoals(projectInfo.getVersion(), ciOpts, request.getGoals(), publishToRepo);
        request.setGoals(goals);
        return goals;
    }

    private void checkGitAuthToken(final CiOptionAccessor ciOpts) {
        logger.info(">>>>>>>>>> ---------- check GIT_AUTH_TOKEN  ---------- >>>>>>>>>>");
        if (isEmpty(ciOpts.getOption(GIT_AUTH_TOKEN).orElse(null))) {
            final boolean infraOpenSource = ciOpts.getOption(INFRASTRUCTURE).map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);
            final Boolean originRepo = ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(FALSE);
            if (originRepo && infraOpenSource) {
                final String errorMsg = String.format(
                    "%s not set and using origin private repo, exit.", GIT_AUTH_TOKEN.getEnvVariableName());
                final RuntimeException error = new RuntimeException(errorMsg);
                logger.error(errorMsg);
                throw error;
            } else {
                // For PR build on travis-ci or appveyor
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("%s not set.", GIT_AUTH_TOKEN.getEnvVariableName()));
                }
            }
        }
        logger.info("<<<<<<<<<< ---------- check GIT_AUTH_TOKEN ---------- <<<<<<<<<<");
    }

    /**
     * Create repositorySystemSession if absent.
     *
     * @param request mavenExecutionRequest
     * @return repositorySystemSessionNull
     */
    private boolean createRepositorySystemSessionIfAbsent(final MavenExecutionRequest request) {
        /*
        RepositorySystemSession may be null, e.g. maven 3.6.0's MavenExecutionRequest.projectBuildingRequest.repositorySession
        java.lang.NullPointerException
        at org.apache.maven.RepositoryUtils.getWorkspace(RepositoryUtils.java:375)
        at org.apache.maven.plugin.DefaultPluginArtifactsCache$CacheKey.<init>(DefaultPluginArtifactsCache.java:70)
        at org.apache.maven.plugin.DefaultPluginArtifactsCache.createKey(DefaultPluginArtifactsCache.java:135)
        at org.apache.maven.plugin.internal.DefaultMavenPluginManager.setupExtensionsRealm(DefaultMavenPluginManager.java:824)
        at org.apache.maven.project.DefaultProjectBuildingHelper.createProjectRealm(DefaultProjectBuildingHelper.java:197)
        at org.apache.maven.project.DefaultModelBuildingListener.buildExtensionsAssembled(DefaultModelBuildingListener.java:101)
        at org.apache.maven.model.building.ModelBuildingEventCatapult$1.fire(ModelBuildingEventCatapult.java:44)
        at org.apache.maven.model.building.DefaultModelBuilder.fireEvent(DefaultModelBuilder.java:1360)
        at org.apache.maven.model.building.DefaultModelBuilder.build(DefaultModelBuilder.java:452)
        at org.apache.maven.model.building.DefaultModelBuilder.build(DefaultModelBuilder.java:432)
        at org.apache.maven.project.DefaultProjectBuilder.build(DefaultProjectBuilder.java:616)
        at org.apache.maven.project.DefaultProjectBuilder.build(DefaultProjectBuilder.java:385)
        ...
         */
        final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();
        final boolean repositorySystemSessionNull;
        if (projectBuildingRequest != null) {
            repositorySystemSessionNull = projectBuildingRequest.getRepositorySession() == null;
            if (repositorySystemSessionNull) {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("repositorySystemSession not found in %s", projectBuildingRequest));
                }
                final RepositorySystemSession repositorySystemSession = this.repositorySessionFactory.newRepositorySession(request);
                projectBuildingRequest.setRepositorySession(repositorySystemSession);
            }
        } else {
            repositorySystemSessionNull = true;
        }
        return repositorySystemSessionNull;
    }

    private void decryptFiles(
        final CiOptionAccessor ciOpts,
        final String homeDir
    ) {
        logger.info("    >>>>>>>>>> ---------- decrypt files and handle keys ---------- >>>>>>>>>>");
        final Optional<String> executable = ciOpts.getOption(GPG_EXECUTABLE);
        if (executable.isPresent()) {
            final Optional<String> gpgKeyid = ciOpts.getOption(GPG_KEYID);
            final String gpgKeyname = ciOpts.getOption(GPG_KEYNAME).orElse("");
            final Optional<String> gpgPassphrase = ciOpts.getOption(GPG_PASSPHRASE);
            final Gpg gpg = new Gpg(
                logger,
                homeDir,
                this.rootProjectPathname,
                executable.get(),
                gpgKeyid.orElse(null),
                gpgKeyname,
                gpgPassphrase.orElse(null)
            );
            gpg.decryptFiles();
        } else {
            logger.warn("Both gpg and gpg2 are not found.");
        }
        logger.info("    <<<<<<<<<< ---------- decrypt files and handle keys ---------- <<<<<<<<<<");
    }

    private List<String> editMavenGoals(
        final String projectVersion,
        final CiOptionAccessor ciOpts,
        final List<String> requestedGoals,
        final Boolean publishToRepo
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- run_mvn alter_mvn ---------- >>>>>>>>>>");
            logger.info(String.format("onMavenExecutionRequest requested goals: %s", String.join(" ", requestedGoals)));
        }
        // requestedGoals.remove("dependency:resolve");

        final String msgReplaceGoal = "onMavenExecutionRequest replace goal %s to %s (%s: %s)";

        final Boolean docker = ciOpts.getOption(DOCKER).map(Boolean::parseBoolean).orElse(FALSE);
        final Boolean mvnDeployPublishSegregation = ciOpts.getOption(MVN_DEPLOY_PUBLISH_SEGREGATION)
            .map(Boolean::parseBoolean).orElse(FALSE);
        final Boolean site = ciOpts.getOption(SITE).map(Boolean::parseBoolean).orElse(FALSE);

        final List<String> resultGoals = new LinkedList<>();
        for (final String goal : requestedGoals) {
            if (goal.endsWith(GOAL_DEPLOY) && !goal.contains("site")) {
                // deploy, site-deploy, push (docker)
                if (publishToRepo) {
                    if (mvnDeployPublishSegregation) {
                        // maven deploy and publish segregation
                        final String wagonGoal = "org.codehaus.mojo:wagon-maven-plugin:merge-maven-repos@merge-maven-repos-deploy";
                        resultGoals.add(wagonGoal);
                        // if (docker) {
                        //     final String dockerPushGoal = "dockerfile:push";
                        //     resultGoals.add(dockerPushGoal);
                        //     if (logger.isInfoEnabled()) {
                        //         logger.info(String.format(msgReplaceGoal, goal, wagonGoal + " " + dockerPushGoal,
                        //             MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), mvnDeployPublishSegregation.toString()));
                        //     }
                        // } else {
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format(msgReplaceGoal, goal, wagonGoal,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), mvnDeployPublishSegregation.toString()));
                        }
                        // }
                    } else {
                        resultGoals.add(goal);
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)",
                            goal, PUBLISH_TO_REPO.getEnvVariableName(), publishToRepo.toString()));
                    }
                }
            } else if (goal.contains("site")) {
                if (site) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)", goal,
                            SITE.getEnvVariableName(), site.toString()));
                    }
                }
            } else if (goal.endsWith(GOAL_CLEAN) || goal.endsWith(GOAL_INSTALL)) {
                // goals need to alter
                if (mvnDeployPublishSegregation) {
                    // maven deploy and publish segregation
                    if (goal.endsWith(GOAL_CLEAN)) {
                        resultGoals.add(GOAL_CLEAN);
                        final String wagonGoal = "org.apache.maven.plugins:maven-antrun-plugin:run@local-deploy-model-path-clean";
                        resultGoals.add(wagonGoal);
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format(msgReplaceGoal, goal, GOAL_CLEAN + " " + wagonGoal,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), mvnDeployPublishSegregation.toString()));
                        }
                    } else if (goal.endsWith(GOAL_INSTALL)) {
                        resultGoals.add(GOAL_DEPLOY); // deploy artifacts into -DaltDeploymentRepository=wagonRepository
                        // if (docker) {
                        //     final String dockerBuildGoal = "dockerfile:build";
                        //     resultGoals.add(dockerBuildGoal);
                        //     if (logger.isInfoEnabled()) {
                        //         logger.info(String.format(msgReplaceGoal, goal, GOAL_DEPLOY + " " + dockerBuildGoal,
                        //             DOCKER.getEnvVariableName(), docker.toString()));
                        //     }
                        // } else {
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("onMavenExecutionRequest replace goal %s to %s (%s: %s)",
                                goal, GOAL_DEPLOY,
                                DOCKER.getEnvVariableName(), docker.toString()));
                        }
                        // }
                    }
                } else {
                    resultGoals.add(goal);
                }
            } else if (goal.endsWith("sonar")) {
                final Boolean originRepo = ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(FALSE);
                final Optional<String> gitRefName = ciOpts.getOption(GIT_REF_NAME);
                final Boolean isRefNameDevelop = gitRefName.map(ref -> ref.equals(GIT_REF_NAME_DEVELOP)).orElse(FALSE);

                if (originRepo && isRefNameDevelop) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s, %s: %s)",
                            goal,
                            ORIGIN_REPO.getEnvVariableName(), originRepo.toString(),
                            GIT_REF_NAME.getEnvVariableName(), gitRefName.orElse(null)));
                    }
                }
            } else {
                resultGoals.add(goal);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info(String.format("onMavenExecutionRequest result goals: %s", String.join(" ", resultGoals)));
            logger.info("<<<<<<<<<< ---------- run_mvn alter_mvn ---------- <<<<<<<<<<");
        }
        return resultGoals;
    }

    private void prepareDocker(
        final List<String> goals,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        final boolean dockerEnabled = ciOpts.getOption(DOCKER).map(Boolean::parseBoolean).orElse(FALSE)
            && goals
            .stream()
            .filter(goal -> !goal.contains("site"))
            .anyMatch(goal ->
                goal.endsWith("build")
                    || goal.endsWith(GOAL_DEPLOY)
                    || goal.endsWith("push")
                    || goal.equals(GOAL_INSTALL)
                    || goal.equals("package")
            );

        if (dockerEnabled) {
            final Docker docker = new Docker(
                logger,
                dockerHost(ciOpts.getSystemProperties()).orElse(null),
                homeDir,
                ciOpts.getOption(DOCKER_REGISTRY).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_PASS).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_USER).orElse(null)
            );
            docker.initDockerConfig();
            docker.cleanOldImages();
            docker.pullBaseImage();
        }
    }

    private static List<String> classPathEntries(
        final Logger logger,
        final ClassLoader cl
    ) {
        final List<String> result = new LinkedList<>();
        if (cl instanceof URLClassLoader) {
            final URL[] urls = ((URLClassLoader) cl).getURLs();
            for (final URL url : urls) {
                result.add(url.toString());
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("Inspecting entries of [%s] is not supported", cl.getClass().getCanonicalName()));
            }
        }
        return result;
    }
}
