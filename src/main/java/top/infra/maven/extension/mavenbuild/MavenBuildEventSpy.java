package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.PATTERN_CI_ENV_VARS;
import static top.infra.maven.extension.mavenbuild.CiOptionEventAware.ORDER_CI_OPTION;
import static top.infra.maven.extension.mavenbuild.DockerEventAware.ORDER_DOCKER;
import static top.infra.maven.extension.mavenbuild.GpgEventAware.ORDER_GPG;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditorEventAware.ORDER_GOAL_EDITOR;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfoEventAware.ORDER_MAVEN_PROJECT_INFO;
import static top.infra.maven.extension.mavenbuild.MavenSettingsFilesEventAware.ORDER_MAVEN_SETTINGS_FILES;
import static top.infra.maven.extension.mavenbuild.MavenSettingsLocalRepositoryEventAware.ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;
import static top.infra.maven.extension.mavenbuild.MavenSettingsServersEventAware.ORDER_MAVEN_SETTINGS_SERVERS;
import static top.infra.maven.extension.mavenbuild.PrintInfoEventAware.ORDER_PRINT_INFO;
import static top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver.ORDER_MODEL_RESOLVER;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtil.systemUserHome;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.toolchain.building.ToolchainsBuildingRequest;
import org.apache.maven.toolchain.building.ToolchainsBuildingResult;

import top.infra.maven.extension.mavenbuild.utils.PropertiesUtil;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

/**
 * Main entry point. Reads properties and exposes them as user properties.
 * Existing user properties will not be overwritten.
 *
 * see: https://maven.apache.org/examples/maven-3-lifecycle-extensions.html
 */
// @org.codehaus.plexus.component.annotations.Component(role = org.apache.maven.eventspy.EventSpy.class)
@Named
@Singleton
public class MavenBuildEventSpy extends AbstractEventSpy {

    private final Logger logger;

    private final String homeDir;

    private final List<MavenEventAware> eventAwares;

    private final CiOptionEventAware ciOptionEventAware;

    private CiOptionAccessor ciOpts;

    /**
     * Constructor.
     *
     * @param logger             inject logger {@link org.codehaus.plexus.logging.Logger}
     * @param eventAwares        inject eventAwares
     * @param ciOptionEventAware ciOptionEventAware
     */
    @Inject
    public MavenBuildEventSpy(
        final org.codehaus.plexus.logging.Logger logger,
        final List<MavenEventAware> eventAwares,
        final CiOptionEventAware ciOptionEventAware
    ) {
        this.homeDir = systemUserHome();
        this.logger = new LoggerPlexusImpl(logger);

        this.eventAwares = eventAwares.stream().sorted().collect(Collectors.toList());
        this.ciOptionEventAware = ciOptionEventAware;

        this.ciOpts = null;
    }

    @Override
    public void init(final Context context) throws Exception {
        try {
            this.eventAwares.forEach(it -> logger.info(String.format("eventAware [%s]", it.getClass().getSimpleName())));

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
                this.onSettingsBuildingRequest(request, this.homeDir, this.ciOpts);
            } else if (event instanceof SettingsBuildingResult) {
                final SettingsBuildingResult result = (SettingsBuildingResult) event;
                this.onSettingsBuildingResult(result, homeDir, this.ciOpts);
            } else if (event instanceof ToolchainsBuildingRequest) {
                final ToolchainsBuildingRequest request = (ToolchainsBuildingRequest) event;
                this.onToolchainsBuildingRequest(request, this.homeDir, this.ciOpts);
            } else if (event instanceof ToolchainsBuildingResult) {
                final ToolchainsBuildingResult result = (ToolchainsBuildingResult) event;
                this.onToolchainsBuildingResult(result, this.homeDir, this.ciOpts);
            } else if (event instanceof MavenExecutionRequest) {
                final MavenExecutionRequest request = (MavenExecutionRequest) event;
                this.onMavenExecutionRequest(request, this.homeDir, this.ciOpts);
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

    public void onInit(final Context context) {
        // print info
        assert ORDER_PRINT_INFO < ORDER_CI_OPTION;
        // init ci options

        this.eventAwares.forEach(it -> it.onInit(context));

        this.ciOpts = this.ciOptionEventAware.getCiOpts(null);
        this.afterInit(context, this.homeDir, this.ciOpts);
    }

    public void afterInit(final Context context, final String homeDir, final CiOptionAccessor ciOpts) {
        // download maven settings.xml, settings-security.xml (optional) and toolchains.xml
        assert ORDER_CI_OPTION < ORDER_MAVEN_SETTINGS_FILES;

        this.eventAwares.forEach(it -> it.afterInit(context, homeDir, ciOpts));
    }

    public void onSettingsBuildingRequest(
        final SettingsBuildingRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent SettingsBuildingRequest %s", request));
            logger.info(String.format("onEvent SettingsBuildingRequest. globalSettingsFile: [%s]", request.getGlobalSettingsFile()));
            logger.info(String.format("onEvent SettingsBuildingRequest. globalSettingsSource: [%s]", request.getGlobalSettingsSource()));
            logger.info(String.format("onEvent SettingsBuildingRequest. userSettingsFile: [%s]", request.getUserSettingsFile()));
            logger.info(String.format("onEvent SettingsBuildingRequest. userSettingsSource: [%s]", request.getUserSettingsSource()));
        }

        assert ORDER_CI_OPTION < ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;
        // try to read settings.localRepository from request.userProperties
        assert ORDER_MAVEN_SETTINGS_LOCALREPOSITORY < ORDER_MAVEN_SETTINGS_FILES;
        // set custom settings file (if present) into request.userSettingsFile

        this.eventAwares.forEach(it -> it.onSettingsBuildingRequest(request, homeDir, ciOpts));
    }

    public void onSettingsBuildingResult(
        final SettingsBuildingResult result,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent SettingsBuildingResult %s", result));
        }

        // set settings.localRepository (if present) into effectiveSettings
        assert ORDER_CI_OPTION < ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;

        this.eventAwares.forEach(it -> it.onSettingsBuildingResult(result, homeDir, ciOpts));
    }

    public void onToolchainsBuildingRequest(
        final ToolchainsBuildingRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent ToolchainsBuildingRequest %s", request));
        }

        this.eventAwares.forEach(it -> it.onToolchainsBuildingRequest(request, homeDir, ciOpts));
    }

    public void onToolchainsBuildingResult(
        final ToolchainsBuildingResult result,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent ToolchainsBuildingResult %s", result));
        }

        this.eventAwares.forEach(it -> it.onToolchainsBuildingResult(result, homeDir, ciOpts));
    }

    public void onMavenExecutionRequest(
        final MavenExecutionRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent MavenExecutionRequest %s", request));
        }

        // if settings.localRepository absent, set mavenExecutionRequest.ocalRepository into userProperties
        assert ORDER_MAVEN_SETTINGS_LOCALREPOSITORY < ORDER_MAVEN_SETTINGS_SERVERS;
        // check empty or blank property values in settings.servers

        this.eventAwares.forEach(it -> it.onMavenExecutionRequest(request, homeDir, ciOpts));


        final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();
        if (projectBuildingRequest != null) {
            // To make profile activation conditions work
            PropertiesUtil.merge(request.getSystemProperties(), projectBuildingRequest.getSystemProperties());
            PropertiesUtil.merge(request.getUserProperties(), projectBuildingRequest.getUserProperties());
            if (logger.isInfoEnabled()) {
                logger.info("     >>>>> projectBuildingRequest (ProfileActivationContext) systemProperties >>>>>");
                logger.info(PropertiesUtil.toString(projectBuildingRequest.getSystemProperties(), PATTERN_CI_ENV_VARS));
                logger.info("     <<<<< projectBuildingRequest (ProfileActivationContext) systemProperties <<<<<");

                logger.info("     >>>>> projectBuildingRequest (ProfileActivationContext) userProperties >>>>>");
                logger.info(PropertiesUtil.toString(projectBuildingRequest.getUserProperties(), null));
                logger.info("     <<<<< projectBuildingRequest (ProfileActivationContext) userProperties <<<<<");
            }

            this.onProjectBuildingRequest(request, projectBuildingRequest, homeDir, ciOpts);
        } else {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("onEvent MavenExecutionRequest %s but projectBuildingRequest is null.", request));
            }
        }
    }

    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onEvent ProjectBuildingRequest %s", projectBuilding));
        }

        // final File rootProjectDirectory = ((MavenExecutionRequest) request).getMultiModuleProjectDirectory();

        // set projectBuildingRequest into project model resolver
        assert ORDER_MODEL_RESOLVER < ORDER_GPG;
        // decrypt gpg keys
        assert ORDER_GPG < ORDER_MAVEN_PROJECT_INFO;
        // check project version and assert it is valid
        assert ORDER_MAVEN_PROJECT_INFO < ORDER_GOAL_EDITOR;
        // edit goals
        assert ORDER_GOAL_EDITOR < ORDER_DOCKER;
        // prepare docker

        this.eventAwares.forEach(it -> it.onProjectBuildingRequest(mavenExecution, projectBuilding, homeDir, ciOpts));
    }
}
