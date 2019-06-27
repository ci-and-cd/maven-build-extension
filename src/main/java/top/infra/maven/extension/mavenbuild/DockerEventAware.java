package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.Docker.dockerHost;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_DEPLOY;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_INSTALL;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_PACKAGE;
import static top.infra.maven.extension.mavenbuild.MavenGoalEditor.GOAL_SITE;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY_PASS;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY_USER;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.DOCKER;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.FAST;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption.DOCKERFILE_USEMAVENSETTINGSFORAUTH;
import static top.infra.maven.utils.SupportFunction.isNotEmpty;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;

import top.infra.maven.core.CiOptions;
import top.infra.maven.extension.MavenEventAware;
import top.infra.maven.utils.SystemUtils;
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
        final CiOptions ciOpts
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
                dockerHost(ciOpts.getSystemProperties()).orElse(null),
                SystemUtils.systemUserHome(),
                ciOpts.getOption(DOCKER_REGISTRY).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_PASS).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null),
                ciOpts.getOption(DOCKER_REGISTRY_USER).orElse(null)
            );

            docker.initConfigFile();

            if (!ciOpts.getOption(DOCKERFILE_USEMAVENSETTINGSFORAUTH).map(Boolean::parseBoolean).orElse(FALSE)) {
                this.login(docker);
            }

            if (!ciOpts.getOption(FAST).map(Boolean::parseBoolean).orElse(FALSE)) {
                this.cleanOldImages(docker);

                this.pullBaseImages(docker);
            }
        }
    }

    private void cleanOldImages(final Docker docker) {
        final List<String> imageIds = docker.imageIdsToClean();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Found imageIdsToClean %s", imageIds));
        }
        docker.deleteImages(imageIds).forEach((id, retCode) -> {
            if (retCode == 0) {
                logger.info(String.format("Image [%s] deleted.", id));
            } else {
                logger.warn(String.format("Failed to delete image [%s].", id));
            }
        });
    }

    private void login(final Docker docker) {
        final String target = docker.getLoginTarget();
        if (isNotEmpty(target) && target.startsWith("https://")) {
            logger.info(String.format("docker logging into secure registry %s", target));
        } else {
            logger.info(String.format("docker logging into insecure registry %s", target));
        }

        final Optional<Integer> result = docker.login(target);
        if (result.isPresent()) {
            logger.info(String.format("docker login [%s] result [%s]", target, result.orElse(null)));
        } else {
            logger.info(String.format("docker login [%s] skipped", target));
        }
    }

    private void pullBaseImages(final Docker docker) {
        logger.info(">>>>>>>>>> ---------- pull_base_image ---------- >>>>>>>>>>");
        final List<String> dockerfiles = Docker.dockerfiles();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Found dockerfiles %s", dockerfiles));
        }
        final List<String> baseImages = docker.pullBaseImages(dockerfiles);
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Found baseImages %s", baseImages));
        }
        logger.info("<<<<<<<<<< ---------- pull_base_image ---------- <<<<<<<<<<");
    }
}
