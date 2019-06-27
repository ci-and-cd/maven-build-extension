package top.infra.maven.extension.mavenbuild;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;

import top.infra.maven.core.CiOptions;
import top.infra.maven.extension.MavenEventAware;
import top.infra.maven.extension.activator.model.ProjectBuilderActivatorModelResolver;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class ModelResolverEventAware implements MavenEventAware {

    public static final int ORDER_MODEL_RESOLVER = MavenSettingsServersEventAware.ORDER_MAVEN_SETTINGS_SERVERS + 1;

    protected final Logger logger;

    protected final ProjectBuilderActivatorModelResolver resolver;

    @Inject
    protected ModelResolverEventAware(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        this.logger = new LoggerPlexusImpl(logger);
        this.resolver = resolver;
    }

    @Override
    public int getOrder() {
        return ORDER_MODEL_RESOLVER;
    }

    @Override
    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final CiOptions ciOpts
    ) {
        resolver.setProjectBuildingRequest(projectBuilding);
    }
}
