package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByBuildProject;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfo.newProjectInfoByReadPom;

import java.io.File;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.eclipse.aether.RepositorySystemSession;

@Named
@Singleton
public class MavenProjectInfoBean {

    private final Logger logger;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final ProjectBuilder projectBuilder;

    private final DefaultRepositorySystemSessionFactory repositorySessionFactory;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final RuntimeInformation runtime;

    private MavenProjectInfo projectInfo;

    @Inject
    public MavenProjectInfoBean(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilder projectBuilder,
        final DefaultRepositorySystemSessionFactory repositorySessionFactory,
        final RuntimeInformation runtime
    ) {
        this.logger = new LoggerPlexusImpl(logger);
        this.projectBuilder = projectBuilder;
        this.repositorySessionFactory = repositorySessionFactory;
        this.runtime = runtime;
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

    public String getMavenVersion() {
        return this.runtime.getMavenVersion();
    }

    public MavenProjectInfo getProjectInfo() {
        return this.projectInfo;
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
}
