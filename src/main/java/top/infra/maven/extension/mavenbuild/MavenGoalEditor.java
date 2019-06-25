package top.infra.maven.extension.mavenbuild;

import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
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
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import top.infra.maven.logging.Logger;

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

    private static boolean isSiteGoal(final String goal) {
        return isNotEmpty(goal) && goal.contains(GOAL_SITE);
    }

    private static boolean isDeployGoal(final String goal) {
        return isNotEmpty(goal) && goal.endsWith(GOAL_DEPLOY) && !isSiteGoal(goal);
    }

    private static boolean isInstallGoal(final String goal) {
        return isNotEmpty(goal) && !isDeployGoal(goal) && !isSiteGoal(goal) && goal.endsWith(GOAL_INSTALL);
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
                if (this.publishToRepo == null || this.publishToRepo) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)",
                            goal, PUBLISH_TO_REPO.getEnvVariableName(), this.publishToRepo));
                    }
                }
            } else if (isSiteGoal(goal)) {
                if (this.site == null || this.site) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s)",
                            goal, SITE.getEnvVariableName(), this.site));
                    }
                }
            } else if (GOAL_PACKAGE.equals(goal) || isInstallGoal(goal)) {
                // goals need to alter
                if (mvnDeployPublishSegregation) {
                    if (GOAL_PACKAGE.equals(goal)) {
                        resultGoals.add(GOAL_DEPLOY); // deploy artifacts into -DaltDeploymentRepository=wagonRepository
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("onMavenExecutionRequest replace goal %s to %s (%s: %s)",
                                goal, GOAL_DEPLOY,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), this.mvnDeployPublishSegregation.toString()));
                        }
                    } else {
                        resultGoals.add(GOAL_DEPLOY); // deploy artifacts into -DaltDeploymentRepository=wagonRepository
                        if (logger.isInfoEnabled()) {
                            logger.info(String.format("onMavenExecutionRequest replace goal %s to %s (%s: %s)",
                                goal, GOAL_DEPLOY,
                                MVN_DEPLOY_PUBLISH_SEGREGATION.getEnvVariableName(), this.mvnDeployPublishSegregation.toString()));
                        }
                    }
                } else {
                    resultGoals.add(goal);
                }
            } else if (goal.endsWith("sonar")) {
                final Boolean isRefNameDevelop = GIT_REF_NAME_DEVELOP.equals(this.gitRefName);

                if (this.originRepo != null && this.originRepo && isRefNameDevelop) {
                    resultGoals.add(goal);
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("onMavenExecutionRequest skip goal %s (%s: %s, %s: %s)",
                            goal,
                            ORIGIN_REPO.getEnvVariableName(), this.originRepo,
                            GIT_REF_NAME.getEnvVariableName(), this.gitRefName));
                    }
                }
            } else {
                resultGoals.add(goal);
            }
        }
        return resultGoals;
    }

    public Properties additionalUserProperties(final List<String> requested, final Collection<String> result) {
        final Collection<MavenPhase> requestedPhases = phases(requested);
        final Collection<MavenPhase> resultPhases = phases(result);

        final Properties properties = new Properties();
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_DEPLOY, BOOL_STRING_FALSE);
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_INSTALL, BOOL_STRING_FALSE);
        properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_FALSE);
        if (requested.contains("clean")) {
            properties.setProperty(MAVEN_CLEAN_SKIP.getPropertyName(), BOOL_STRING_FALSE);
        }

        if (this.mvnDeployPublishSegregation) {
            if (notIncludes(requestedPhases, MavenPhase.INSTALL) && notIncludes(resultPhases, MavenPhase.INSTALL)) {
                properties.setProperty(MAVEN_INSTALL_SKIP.getPropertyName(), BOOL_STRING_TRUE);
            }

            if (includes(resultPhases, MavenPhase.PACKAGE)) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_PACKAGE);
            }

            final Optional<String> requestedInstallGoal = requested.stream().filter(MavenGoalEditor::isInstallGoal).findAny();
            if ((requestedInstallGoal.isPresent() || includes(requestedPhases, MavenPhase.INSTALL))
                && notIncludes(resultPhases, MavenPhase.DEPLOY)) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_INSTALL, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_PACKAGE, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_INSTALL);
            }

            final Optional<String> requestedDeployGoal = requested.stream().filter(MavenGoalEditor::isDeployGoal).findAny();
            if ((requestedDeployGoal.isPresent() || includes(requestedPhases, MavenPhase.DEPLOY))
                && this.publishToRepo) {
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL_DEPLOY, BOOL_STRING_TRUE);
                properties.setProperty(PROP_MVN_DEPLOY_PUBLISH_SEGREGATION_GOAL, GOAL_DEPLOY);
            }
        }
        return properties;
    }

    private static final Map<String, MavenPhase> phaseMap = Collections.unmodifiableMap(
        Arrays.stream(MavenPhase.values())
            .collect(toMap(MavenPhase::getPhase, Function.identity()))
    );

    private static Collection<String> pluginExecution(final Collection<String> in) {
        // things like
        // 'org.apache.maven.plugins:maven-antrun-plugin:run@wagon-repository-clean',
        // 'org.codehaus.mojo:wagon-maven-plugin:merge-maven-repos@merge-maven-repos-deploy'
        return in.stream().filter(it -> it.contains("@")).collect(toCollection(LinkedHashSet::new));
    }

    private static Collection<String> pluginGoal(final Collection<String> in) { // things like 'clean:clean', 'compiler:compile', 'jar:jar'
        return in.stream().filter(it -> it.contains(":") && !it.contains("@")).collect(toCollection(LinkedHashSet::new));
    }

    private static Collection<MavenPhase> phases(final Collection<String> in) { // things like 'clean', 'compile', 'package'.
        return in.stream()
            .filter(it -> !it.contains(":"))
            .map(phaseMap::get)
            .filter(Objects::nonNull)
            .collect(toCollection(LinkedHashSet::new));
    }

    private static boolean notIncludes(final Collection<MavenPhase> phases, final MavenPhase phase) {
        return !includes(phases, phase);
    }

    private static boolean includes(final Collection<MavenPhase> phases, final MavenPhase phase) {
        return phases.stream().anyMatch(it -> it.ordinal() >= phase.ordinal());
    }
}
