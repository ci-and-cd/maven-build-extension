package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKERFILE_USEMAVENSETTINGSFORAUTH;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_PASS;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_USER;
import static top.infra.maven.extension.mavenbuild.CiOption.FAST;
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
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.Constants.USER_PROPERTY_SETTINGS_LOCALREPOSITORY;
import static top.infra.maven.extension.mavenbuild.Docker.dockerHost;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_DEPLOY;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_INSTALL;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_PACKAGE;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_SITE;
import static top.infra.maven.extension.mavenbuild.MavenServerInterceptor.absentVarsInSettingsXml;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTupleOptional;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtil.systemUserHome;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
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
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.toolchain.building.ToolchainsBuildingRequest;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;
import top.infra.maven.extension.mavenbuild.utils.SupportFunction;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

/**
 * Main entry point. Reads properties and exposes them as user properties.
 * Existing user properties will not be overwritten.
 */
// @org.codehaus.plexus.component.annotations.Component(role = org.apache.maven.eventspy.EventSpy.class)
@Named
@Singleton
public class MavenBuildEventSpy extends AbstractEventSpy {

    private static final Pattern PATTERN_CI_ENV_VARS = Pattern.compile("^env\\.CI_.+");

    private final Logger logger;

    private final String homeDir;

    private final MavenProjectInfoBean projectInfoBean;

    private final ProjectBuilderActivatorModelResolver resolver;
    // @org.codehaus.plexus.component.annotations.Requirement
    private final RuntimeInformation runtime;
    private String settingsLocalRepository;
    private CiOptionAccessor ciOpts;
    private String mavenSettingsPathname;
    private MavenServerInterceptor mavenServerInterceptor;
    private String rootProjectPathname;

    /**
     * Constructor.
     *
     * @param logger                 inject logger {@link org.codehaus.plexus.logging.Logger}
     * @param resolver               resolver
     * @param mavenServerInterceptor mavenServerInterceptor
     * @param projectInfoBean        inject projectInfoBean
     */
    @Inject
    public MavenBuildEventSpy(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilderActivatorModelResolver resolver,
        final MavenServerInterceptor mavenServerInterceptor,
        final MavenProjectInfoBean projectInfoBean,
        final RuntimeInformation runtime
    ) {
        this.homeDir = systemUserHome();
        this.logger = new LoggerPlexusImpl(logger);
        this.resolver = resolver;
        this.mavenServerInterceptor = mavenServerInterceptor;
        this.projectInfoBean = projectInfoBean;
        this.runtime = runtime;

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

                    this.resolver.setProjectBuildingRequest(projectBuildingRequest);

                    final Entry<List<String>, Properties> goalsAndProps = this.onMavenExecutionRequest(request, this.homeDir, this.ciOpts);
                    if (goalsAndProps.getKey().isEmpty() && !request.getGoals().isEmpty()) {
                        logger.warn(String.format("No goal to run, all goals requested (%s) were removed.", request.getGoals()));
                        // request.setGoals(Collections.singletonList("help:active-profiles"));
                        request.setGoals(Collections.singletonList("validate"));
                    } else {
                        request.setGoals(goalsAndProps.getKey());
                    }
                    SupportFunction.merge(goalsAndProps.getValue(), request.getUserProperties());
                    SupportFunction.merge(goalsAndProps.getValue(), projectBuildingRequest.getUserProperties());
                    prepareDocker(logger, goalsAndProps.getKey(), this.homeDir, this.ciOpts);
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

    private Entry<List<String>, Properties> onMavenExecutionRequest(
        final MavenExecutionRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent MavenExecutionRequest %s", request));
        }

        // final File rootProjectDirectory = ((MavenExecutionRequest) request).getMultiModuleProjectDirectory();

        this.decryptFiles(ciOpts, homeDir);

        final Entry<Optional<MavenProjectInfo>, Optional<RuntimeException>> resultCheckProjectVer =
            this.resolveAndCheckProjectVersion(request, ciOpts);
        final boolean projectVersionValid = resultCheckProjectVer.getKey().isPresent();
        resultCheckProjectVer.getValue().ifPresent(ex -> {
            logger.error(ex.getMessage());
            throw ex;
        });

        return editGoals(logger, request, ciOpts, projectVersionValid);
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
            gpg.decryptAndImportKeys();
        } else {
            logger.warn("Both gpg and gpg2 are not found.");
        }
        logger.info("    <<<<<<<<<< ---------- decrypt files and handle keys ---------- <<<<<<<<<<");
    }

    private Entry<Optional<MavenProjectInfo>, Optional<RuntimeException>> resolveAndCheckProjectVersion(
        final MavenExecutionRequest request,
        final CiOptionAccessor ciOpts
    ) {
        // Options are not calculated and merged into projectBuildingRequest this time.
        final MavenProjectInfo projectInfo = this.projectInfoBean.getMavenProjectInfo(request);
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- resolve project version ---------- >>>>>>>>>>");
            logger.info(projectInfo.toString());
            logger.info("<<<<<<<<<< ---------- resolve project version ---------- <<<<<<<<<<");
        }

        final String gitRefName = ciOpts.getOption(GIT_REF_NAME).orElse("");
        final Entry<Boolean, RuntimeException> resultCheckProjectVer = ciOpts.checkProjectVersion(projectInfo.getVersion());
        final boolean verValid = resultCheckProjectVer.getKey();
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- check project version ---------- >>>>>>>>>>");
            logger.info(String.format("%s version [%s] for ref [%s].",
                verValid ? "Valid" : "Invalid", projectInfo.getVersion(), gitRefName));
            logger.info("<<<<<<<<<< ---------- check project version ---------- <<<<<<<<<<");
        }

        final Entry<Optional<MavenProjectInfo>, Optional<RuntimeException>> result;
        if (verValid) {
            result = newTupleOptional(projectInfo, null);
        } else {
            logger.warn("You should use versions with '-SNAPSHOT' suffix on develop branch or feature branches");
            logger.warn("You should use versions like 1.0.0-SNAPSHOT develop branch");
            logger.warn("You should use versions like 1.0.0-feature-SNAPSHOT or 1.0.0-branch-SNAPSHOT on feature branches");
            logger.warn("You should use versions like 1.0.0 without '-SNAPSHOT' suffix on releases");
            final RuntimeException ex = resultCheckProjectVer.getValue();
            result = newTupleOptional(null, ex);
        }
        return result;
    }

    private static Entry<List<String>, Properties> editGoals(
        final Logger logger,
        final MavenExecutionRequest request,
        final CiOptionAccessor ciOpts,
        final boolean projectVersionValid
    ) {
        final List<String> requestedGoals = new ArrayList<>(request.getGoals());
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- run_mvn alter_mvn ---------- >>>>>>>>>>");
            logger.info(String.format("onMavenExecutionRequest requested goals: %s", String.join(" ", requestedGoals)));
        }

        final MavenGoalEditor goalEditor = new MavenGoalEditor(
            logger,
            ciOpts.getOption(GIT_REF_NAME).orElse(null),
            ciOpts.getOption(MVN_DEPLOY_PUBLISH_SEGREGATION).map(Boolean::parseBoolean).orElse(FALSE),
            ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(FALSE),
            ciOpts.getOption(PUBLISH_TO_REPO).map(Boolean::parseBoolean).orElse(FALSE) && projectVersionValid,
            ciOpts.getOption(SITE).map(Boolean::parseBoolean).orElse(FALSE)
        );
        final Entry<List<String>, Properties> goalsAndProps = goalEditor.goalsAndUserProperties(request.getGoals());
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onMavenExecutionRequest result goals: %s", String.join(" ", goalsAndProps.getKey())));
            logger.info(">>>>>>>>>> ---------- onMavenExecutionRequest additionalUserProperties ---------- >>>>>>>>>>");
            logger.info(SupportFunction.toString(goalsAndProps.getValue(), null));
            logger.info("<<<<<<<<<< ---------- onMavenExecutionRequest additionalUserProperties ---------- <<<<<<<<<<");
            logger.info("<<<<<<<<<< ---------- run_mvn alter_mvn ---------- <<<<<<<<<<");
        }
        return goalsAndProps;
    }

    private static void prepareDocker(
        final Logger logger,
        final List<String> goals,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        final boolean dockerEnabled = ciOpts.getOption(DOCKER).map(Boolean::parseBoolean).orElse(FALSE)
            && goals
            .stream()
            .filter(goal -> !goal.contains(GOAL_SITE))
            .anyMatch(goal ->
                goal.endsWith("build")
                    || goal.endsWith(GOAL_DEPLOY)
                    || goal.endsWith("push")
                    || goal.equals(GOAL_INSTALL)
                    || goal.equals(GOAL_PACKAGE)
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

            if (!ciOpts.getOption(DOCKERFILE_USEMAVENSETTINGSFORAUTH).map(Boolean::parseBoolean).orElse(FALSE)) {
                docker.dockerLogin();
            }

            if (!ciOpts.getOption(FAST).map(Boolean::parseBoolean).orElse(FALSE)) {
                docker.cleanOldImages();
                docker.pullBaseImage();
            }
        }
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
            logger.info(String.format("HOME [%s]", this.homeDir));
            logger.info(String.format("JAVA_HOME [%s]", System.getProperty("java.home")));
            logger.info(String.format("PWD [%s]", this.rootProjectPathname));

            final String runtimeImplVersion = Runtime.class.getPackage().getImplementationVersion();
            final String javaVersion = runtimeImplVersion != null ? runtimeImplVersion : System.getProperty("java.runtime.version");

            logger.info(String.format("Java version [%s]", javaVersion));
            logger.info(String.format("Maven version [%s]", this.runtime.getMavenVersion()));

            logger.info(new AppveyorVariables(systemProperties).toString());
            logger.info(new GitlabCiVariables(systemProperties).toString());
            logger.info(new TravisCiVariables(systemProperties).toString());
        }

        final GitProperties gitProperties = GitProperties.newInstance(logger).orElseGet(() -> GitProperties.newBlankInstance(logger));
        this.ciOpts = new CiOptionAccessor(
            logger,
            gitProperties,
            systemProperties,
            userProperties
        );

        logger.info("<<<<<<<<<< ---------- build context info ---------- <<<<<<<<<<");

        checkGitAuthToken(logger, this.ciOpts);

        final Optional<String> gitRefName = this.ciOpts.getOption(GIT_REF_NAME);
        if ((!gitRefName.isPresent() || isEmpty(gitRefName.get())) && logger.isWarnEnabled()) {
            logger.warn(String.format("Can not find value of %s (%s)", GIT_REF_NAME.getEnvVariableName(), GIT_REF_NAME.getPropertyName()));
        }

        logger.info(">>>>>>>>>> ---------- load options from file ---------- >>>>>>>>>>");
        // ci options from file
        final Properties loadedProperties = ciOpts.ciOptsFromFile();
        this.ciOpts.updateSystemProperties(loadedProperties);
        if (logger.isInfoEnabled()) {
            logger.info("    >>>>>>>>>> ---------- loadedProperties ---------- >>>>>>>>>>");
            logger.info(SupportFunction.toString(loadedProperties, PATTERN_CI_ENV_VARS));
            logger.info("    <<<<<<<<<< ---------- loadedProperties ---------- <<<<<<<<<<");
        }

        // github site options

        this.ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).ifPresent(owner ->
            systemProperties.setProperty(GITHUB_GLOBAL_REPOSITORYOWNER.getSystemPropertyName(), owner));
        logger.info("<<<<<<<<<< ---------- load options from file ---------- <<<<<<<<<<");

        // maven options
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- set options (update userProperties) ---------- >>>>>>>>>>");
        }

        final Properties newProperties = this.ciOpts.mergeCiOptsInto(userProperties);

        if (logger.isInfoEnabled()) {
            logger.info(SupportFunction.toString(systemProperties, PATTERN_CI_ENV_VARS));
            logger.info(SupportFunction.toString(userProperties, null));
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

    private static void checkGitAuthToken(final Logger logger, final CiOptionAccessor ciOpts) {
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
