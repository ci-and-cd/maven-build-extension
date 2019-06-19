package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_CLEAN_SKIP;
import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_INSTALL_SKIP;
import static top.infra.maven.extension.mavenbuild.CiOption.MVN_DEPLOY_PUBLISH_SEGREGATION;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_TO_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.SITE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_FALSE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.newTuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;

public class MavenGoalEditor {

    static final String GOAL_DEPLOY = "deploy";
    static final String GOAL_INSTALL = "install";
    static final String GOAL_PACKAGE = "package";
    static final String GOAL_SITE = "site";

    private static final String PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL = "mvn.deploy.publish.segregation.goal";
    private static final String PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_DEPLOY = "mvn.deploy.publish.segregation.goal.deploy";
    private static final String PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_INSTALL = "mvn.deploy.publish.segregation.goal.install";
    private static final String PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE = "mvn.deploy.publish.segregation.goal.package";

    private final Logger logger;
    private final String gitRefName;
    private final Boolean mvnDeployPublishSegregation;
    private final Boolean originRepo;
    private final Boolean publishToRepo;
    private final Boolean site;

    public MavenGoalEditor(
        final Logger logger,
        final String gitRefName,
        final Boolean mvnDeployPublishSegregation,
        final Boolean originRepo,
        final Boolean publishToRepo,
        final Boolean site
    ) {
        this.logger = logger;

        this.gitRefName = gitRefName;
        this.mvnDeployPublishSegregation = mvnDeployPublishSegregation;
        this.originRepo = originRepo;
        this.publishToRepo = publishToRepo;
        this.site = site;
    }

    public Entry<List<String>, Properties> goalsAndUserProperties(final List<String> requestedGoals) {
        final Collection<String> resultGoals = this.editGoals(requestedGoals);
        final Properties userProperties = this.additionalUserProperties(requestedGoals, resultGoals);
        return newTuple(new ArrayList<>(resultGoals), userProperties);
    }

    public Collection<String> editGoals(final List<String> requestedGoals) {
        final Collection<String> resultGoals = new LinkedHashSet<>();
        for (final String goal : requestedGoals) {
            if (isDeployGoal(goal)) {
                // deploy, site-deploy
                if (this.publishToRepo) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)",
                            goal, PUBLISH_TO_REPO.getEnvVariableName(), this.publishToRepo.toString()));
                    }
                }
            } else if (isSiteGoal(goal)) {
                if (this.site) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)",
                            goal, SITE.getEnvVariableName(), this.site.toString()));
                    }
                }
            } else if (GOAL_PACKAGE.equals(goal) || isInstallGoal(goal)) {
                // goals need to alter
                if (mvnDeployPublishSegregation) {
                    if (GOAL_PACKAGE.equals(goal)) {
                        resultGoals.add(goal);
                        resultGoals.add(GOAL_DEPLOY); // deploy artifacts into -DaltDeploymentRepository=wagonRepository
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("onMavenExecutionRequest add goal %s after %s (%s: %s)",
                                GOAL_DEPLOY, goal,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), this.mvnDeployPublishSegregation.toString()));
                        }
                    } else {
                        resultGoals.add(goal);
                        resultGoals.add(GOAL_DEPLOY); // deploy artifacts into -DaltDeploymentRepository=wagonRepository
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("onMavenExecutionRequest add goal %s after %s (%s: %s)",
                                GOAL_DEPLOY, goal,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), this.mvnDeployPublishSegregation.toString()));
                        }
                    }
                } else {
                    resultGoals.add(goal);
                }
            } else if (goal.endsWith("sonar")) {
                final Boolean isRefNameDevelop = GIT_REF_NAME_DEVELOP.equals(this.gitRefName);

                if (this.originRepo && isRefNameDevelop) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s, %s: %s)",
                            goal,
                            ORIGIN_REPO.getEnvVariableName(), this.originRepo.toString(),
                            GIT_REF_NAME.getEnvVariableName(), this.gitRefName));
                    }
                }
            } else {
                resultGoals.add(goal);
            }
        }
        return resultGoals;
    }

    public Properties additionalUserProperties(final List<String> requestedGoals, final Collection<String> resultGoals) {
        final Properties properties = new Properties();
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_DEPLOY, BOOL_STRING_FALSE);
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_INSTALL, BOOL_STRING_FALSE);
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_FALSE);
        if (requestedGoals.contains("clean")) {
            properties.setProperty(MAVEN_CLEAN_SKIP.getPropertyName(), BOOL_STRING_FALSE);
        }
        if (this.mvnDeployPublishSegregation) {
            if (!requestedGoals.contains(GOAL_INSTALL) && !resultGoals.contains(GOAL_INSTALL)) {
                properties.setProperty(MAVEN_INSTALL_SKIP.getPropertyName(), BOOL_STRING_TRUE);
            }

            if (resultGoals.contains(GOAL_PACKAGE)) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_PACKAGE);
            }

            final Optional<String> requestedInstallGoal = requestedGoals.stream().filter(MavenGoalEditor::isInstallGoal).findAny();
            if (requestedInstallGoal.isPresent() && resultGoals.contains(GOAL_DEPLOY)) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_INSTALL, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_INSTALL);
            }

            final Optional<String> requestedDeployGoal = requestedGoals.stream().filter(MavenGoalEditor::isDeployGoal).findAny();
            if (requestedDeployGoal.isPresent() && this.publishToRepo) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_DEPLOY, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_DEPLOY);
            }
        }
        return properties;
    }

    private static boolean isSiteGoal(final String goal) {
        return isNotEmpty(goal) && goal.contains(GOAL_SITE);
    }

    private static boolean isDeployGoal(final String goal) {
        return isNotEmpty(goal) && goal.endsWith(GOAL_DEPLOY) && !isSiteGoal(goal);
    }

    private static boolean isInstallGoal(final String goal) {
        return isNotEmpty(goal) && !isDeployGoal(goal) && !isSiteGoal(goal) && goal.endsWith(GOAL_INSTALL);
    }
}
