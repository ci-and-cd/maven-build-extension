package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKERFILE_USEMAVENSETTINGSFORAUTH;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_PASS;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_USER;
import static top.infra.maven.extension.mavenbuild.CiOption.FAST;
import static top.infra.maven.extension.mavenbuild.Docker.dockerHost;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_DEPLOY;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_INSTALL;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_PACKAGE;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_SITE;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;

import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class DockerEventAware implements MavenEventAware {

    public static final int ORDER_DOCKER = MavenGoalEditorEventAware.ORDER_GOAL_EDITOR + 1;

    private Logger logger;

    @Inject
    public DockerEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);
    }

    @Override
    public int getOrder() {
        return ORDER_DOCKER;
    }

    @Override
    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        final List<String> goals = mavenExecution.getGoals();
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
}
