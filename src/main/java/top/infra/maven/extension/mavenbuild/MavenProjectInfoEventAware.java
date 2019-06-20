package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.GpgEventAware.ORDER_GPG;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByBuildProject;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByReadPom;

import java.io.File;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.RepositorySystemSession;

import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class MavenProjectInfoEventAware implements MavenEventAware {

    public static final int ORDER_MAVEN_PROJECT_INFO = ORDER_GPG + 1;

    private final Logger logger;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final ProjectBuilder projectBuilder;

    private final DefaultRepositorySystemSessionFactory repositorySessionFactory;

    private MavenProjectInfo projectInfo;

    @Inject
    public MavenProjectInfoEventAware(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilder projectBuilder,
        final DefaultRepositorySystemSessionFactory repositorySessionFactory
    ) {
        this.logger = new LoggerPlexusImpl(logger);
        this.projectBuilder = projectBuilder;
        this.repositorySessionFactory = repositorySessionFactory;
    }

    public MavenProjectInfo getMavenProjectInfo(final MavenExecutionRequest request) {
        final boolean repositorySystemSessionNull = this.createRepositorySystemSessionIfAbsent(request);
        final ProjectBuildingRequest projectBuildingRequest = request.getProjectBuildingRequest();

        try {
            final File pomFile = request.getPom();
            this.projectInfo = newProjectInfoByReadPom(logger, pomFile)
                .orElseGet(() -> newProjectInfoByBuildProject(logger, this.projectBuilder, pomFile, projectBuildingRequest));
            return this.projectInfo;
        } finally {
            if (repositorySystemSessionNull) {
                projectBuildingRequest.setRepositorySession(null);
            }
        }
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

    @Override
    public int getOrder() {
        return ORDER_MAVEN_PROJECT_INFO;
    }

    public MavenProjectInfo getProjectInfo() {
        return this.projectInfo;
    }

    @Override
    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final CiOptionAccessor ciOpts
    ) {
        // Options are not calculated and merged into projectBuildingRequest this time.
        final MavenProjectInfo mavenProjectInfo = this.getMavenProjectInfo(mavenExecution);
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- resolve project version ---------- >>>>>>>>>>");
            logger.info(mavenProjectInfo.toString());
            logger.info("<<<<<<<<<< ---------- resolve project version ---------- <<<<<<<<<<");
        }

        final String gitRefName = ciOpts.getOption(GIT_REF_NAME).orElse("");
        final Map.Entry<Boolean, RuntimeException> checkResult = ciOpts.checkProjectVersion(mavenProjectInfo.getVersion());
        final boolean valid = checkResult.getKey();
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- check project version ---------- >>>>>>>>>>");
            logger.info(String.format("%s version [%s] for ref [%s].",
                valid ? "Valid" : "Invalid", mavenProjectInfo.getVersion(), gitRefName));
            logger.info("<<<<<<<<<< ---------- check project version ---------- <<<<<<<<<<");
        }

        if (!valid) {
            logger.warn("You should use versions with '-SNAPSHOT' suffix on develop branch or feature branches");
            logger.warn("You should use versions like 1.0.0-SNAPSHOT develop branch");
            logger.warn("You should use versions like 1.0.0-feature-SNAPSHOT or 1.0.0-branch-SNAPSHOT on feature branches");
            logger.warn("You should use versions like 1.0.0 without '-SNAPSHOT' suffix on releases");
            final RuntimeException ex = checkResult.getValue();
            if (ex != null) {
                logger.error(ex.getMessage());
                throw ex;
            }
        }
    }
}
