package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PUBLISH_TO_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.SITE;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_DEVELOP;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isSemanticSnapshotVersion;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.Test;
import org.slf4j.LoggerFactory;

import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerSlf4jImpl;

public class ProjectVersionTest {

    private static final org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(ProjectVersionTest.class);

    private static Logger logger() {
        return new LoggerSlf4jImpl(slf4jLogger);
    }

    private static GitProperties gitProperties() {
        final Logger logger = logger();
        return GitProperties.newInstance(logger).orElseGet(() -> GitProperties.newBlankInstance(logger));
    }

    @Test
    public void testSemanticSnapshotVersion() {
        assertTrue(isSemanticSnapshotVersion("2.0.1-SNAPSHOT"));
        assertTrue(isSemanticSnapshotVersion("1.0.0-SNAPSHOT"));
        assertTrue(isSemanticSnapshotVersion("1.0-SNAPSHOT"));
        assertTrue(isSemanticSnapshotVersion("1-SNAPSHOT"));

        assertFalse(isSemanticSnapshotVersion("2.0.1-feature-SNAPSHOT"));
        assertFalse(isSemanticSnapshotVersion("2.0.1"));
    }

    @Test
    public void testVersionsOnDevelopBranch() {
        final Properties systemProperties = new Properties();
        systemProperties.setProperty(GIT_REF_NAME.getSystemPropertyName(), GIT_REF_NAME_DEVELOP);
        systemProperties.setProperty(ORIGIN_REPO.getSystemPropertyName(), BOOL_STRING_TRUE);

        final Properties userProperties = new Properties();
        userProperties.setProperty(INFRASTRUCTURE.getPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        userProperties.setProperty(SITE.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            logger(),
            gitProperties(),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        ciOpts.updateSystemProperties(loadedProperties);

        final Properties newProperties = ciOpts.setCiOptPropertiesInto(userProperties);

        final Optional<String> gitRefName = ciOpts.getOption(GIT_REF_NAME);
        slf4jLogger.info("{} [{}]", GIT_REF_NAME.getPropertyName(), gitRefName.orElse(null));

        slf4jLogger.info("{} [{}]", PUBLISH_TO_REPO.getPropertyName(), ciOpts.getOption(PUBLISH_TO_REPO).orElse(null));
        assertTrue(ciOpts.getOption(PUBLISH_TO_REPO).map(Boolean::parseBoolean).orElse(FALSE));

        final String projectVersion = "2.0.1-SNAPSHOT";
        final Map.Entry<Boolean, RuntimeException> checkProjectVersionResult = ciOpts.checkProjectVersion(projectVersion);
        slf4jLogger.info("checkProjectVersion result: [{}]", checkProjectVersionResult);
        assertTrue(checkProjectVersionResult.getKey());

        assertFalse(ciOpts.checkProjectVersion("2.0.1-feature1-SNAPSHOT").getKey());
        assertFalse(ciOpts.checkProjectVersion("2.0.1").getKey());
    }

    @Test
    public void testVersionsOnFeatureBranch() {
        final Properties systemProperties = new Properties();
        systemProperties.setProperty(GIT_REF_NAME.getSystemPropertyName(), "feature/feature1");
        systemProperties.setProperty(ORIGIN_REPO.getSystemPropertyName(), BOOL_STRING_TRUE);

        final Properties userProperties = new Properties();
        userProperties.setProperty(INFRASTRUCTURE.getPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        userProperties.setProperty(SITE.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            logger(),
            gitProperties(),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        ciOpts.updateSystemProperties(loadedProperties);

        final Properties newProperties = ciOpts.setCiOptPropertiesInto(userProperties);

        final Optional<String> gitRefName = ciOpts.getOption(GIT_REF_NAME);
        slf4jLogger.info("{} [{}]", GIT_REF_NAME.getPropertyName(), gitRefName.orElse(null));

        slf4jLogger.info("{} [{}]", PUBLISH_TO_REPO.getPropertyName(), ciOpts.getOption(PUBLISH_TO_REPO).orElse(null));
        assertTrue(ciOpts.getOption(PUBLISH_TO_REPO).map(Boolean::parseBoolean).orElse(FALSE));

        final String projectVersion = "2.0.1-feature1-SNAPSHOT";
        final Map.Entry<Boolean, RuntimeException> checkProjectVersionResult = ciOpts.checkProjectVersion(projectVersion);
        slf4jLogger.info("checkProjectVersion result: [{}]", checkProjectVersionResult);
        assertTrue(checkProjectVersionResult.getKey());

        assertFalse(ciOpts.checkProjectVersion("2.0.1-SNAPSHOT").getKey());
        assertFalse(ciOpts.checkProjectVersion("2.0.1").getKey());
    }
}
