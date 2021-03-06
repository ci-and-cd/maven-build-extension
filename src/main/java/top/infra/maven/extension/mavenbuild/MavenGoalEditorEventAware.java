package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.MVN_DEPLOY_PUBLISH_SEGREGATION;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_TO_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.SITE;
import static top.infra.maven.extension.mavenbuild.MavenProjectInfoEventAware.ORDER_MAVEN_PROJECT_INFO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;

import top.infra.maven.extension.mavenbuild.utils.PropertiesUtils;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class MavenGoalEditorEventAware implements MavenEventAware {

    public static final int ORDER_GOAL_EDITOR = ORDER_MAVEN_PROJECT_INFO + 1;

    private Logger logger;

    @Inject
    public MavenGoalEditorEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);
    }

    @Override
    public int getOrder() {
        return ORDER_GOAL_EDITOR;
    }

    @Override
    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final CiOptionAccessor ciOpts
    ) {
        final Entry<List<String>, Properties> goalsAndProps = editGoals(logger, mavenExecution, ciOpts);

        if (goalsAndProps.getKey().isEmpty() && !mavenExecution.getGoals().isEmpty()) {
            logger.warn(String.format("No goal to run, all goals requested (%s) were removed.", mavenExecution.getGoals()));
            // request.setGoals(Collections.singletonList("help:active-profiles"));
            mavenExecution.setGoals(Collections.singletonList("validate"));
        } else {
            mavenExecution.setGoals(goalsAndProps.getKey());
        }
        PropertiesUtils.merge(goalsAndProps.getValue(), mavenExecution.getUserProperties());
        PropertiesUtils.merge(goalsAndProps.getValue(), projectBuilding.getUserProperties());
    }

    private static Entry<List<String>, Properties> editGoals(
        final Logger logger,
        final MavenExecutionRequest request,
        final CiOptionAccessor ciOpts
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
            ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(null),
            ciOpts.getOption(PUBLISH_TO_REPO).map(Boolean::parseBoolean).orElse(null), // make sure version is valid too
            ciOpts.getOption(SITE).map(Boolean::parseBoolean).orElse(null)
        );
        final Entry<List<String>, Properties> goalsAndProps = goalEditor.goalsAndUserProperties(request.getGoals());
        if (logger.isInfoEnabled()) {
            logger.info(String.format("onMavenExecutionRequest result goals: %s", String.join(" ", goalsAndProps.getKey())));
            logger.info(">>>>>>>>>> ---------- onMavenExecutionRequest additionalUserProperties ---------- >>>>>>>>>>");
            logger.info(PropertiesUtils.toString(goalsAndProps.getValue(), null));
            logger.info("<<<<<<<<<< ---------- onMavenExecutionRequest additionalUserProperties ---------- <<<<<<<<<<");
            logger.info("<<<<<<<<<< ---------- run_mvn alter_mvn ---------- <<<<<<<<<<");
        }
        return goalsAndProps;
    }
}
