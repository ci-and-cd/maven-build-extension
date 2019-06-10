package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.TRUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.CiOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.CiOption.GITHUB_SITE_REPO_OWNER;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.SITE;
import static top.infra.maven.extension.mavenbuild.CiOption.SONAR;
import static top.infra.maven.extension.mavenbuild.CiOption.SONAR_HOST_URL;
import static top.infra.maven.extension.mavenbuild.CiOption.SONAR_ORGANIZATION;
import static top.infra.maven.extension.mavenbuild.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;

import java.util.Properties;

import org.junit.Test;
import org.slf4j.LoggerFactory;

public class CiOptionTests {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(CiOptionTests.class);

    @Test
    public void testDockerRegistry() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(INFRASTRUCTURE.getPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        userProperties.setProperty(SITE.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            new LoggerSlf4jImpl(logger),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        loadedProperties.setProperty(DOCKER_REGISTRY_URL.getEnvVariableName(), "https://docker.io/v2/");
        ciOpts.updateSystemProperties(loadedProperties);

        // ciOpts.githubSiteRepoOwner().ifPresent(githubSiteRepoOwner ->
        //     ciOpts.setSystemProperty(GITHUB_SITE_REPO_OWNER, githubSiteRepoOwner));

        final Properties newProperties = ciOpts.mavenOptsInto(userProperties);
        ciOpts.docker();

        logger.info("{} {}", DOCKER_REGISTRY_URL.getPropertyName(), ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null));
        logger.info("{} {}", DOCKER_REGISTRY.getPropertyName(), ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
        assertEquals("https://docker.io/v2/", ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null));
        // assertEquals("docker.io", ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
        assertNull(ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
    }

    @Test
    public void testGithubSiteRepoOwner() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(INFRASTRUCTURE.getPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        userProperties.setProperty(SITE.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            new LoggerSlf4jImpl(logger),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        ciOpts.updateSystemProperties(loadedProperties);

        // final Optional<String> owner = ciOpts.getOption(GITHUB_SITE_REPO_OWNER);
        logger.info("{} {}", GITHUB_SITE_REPO_OWNER.getPropertyName(), ciOpts.getOption(GITHUB_SITE_REPO_OWNER).orElse(null));
        assertEquals("ci-and-cd", ciOpts.getOption(GITHUB_SITE_REPO_OWNER).orElse(null));
    }

    @Test
    public void testSite() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(INFRASTRUCTURE.getPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        userProperties.setProperty(SITE.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            new LoggerSlf4jImpl(logger),
            systemProperties,
            userProperties
        );

        logger.info("site {}", ciOpts.getOption(SITE).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(SITE).orElse(null));

        final Properties loadedProperties = ciOpts.ciOptsFromFile();
        ciOpts.updateSystemProperties(loadedProperties);

        logger.info("site {}", ciOpts.getOption(SITE).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(SITE).orElse(null));

        ciOpts.mavenOptsInto(userProperties);
        ciOpts.docker();

        logger.info("site {}", ciOpts.getOption(SITE).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(SITE).orElse(null));
    }

    @Test
    public void testSonar() {
        final String expectedSonarHostUrl = "https://sonarqube.com";
        final String expectedSonarOrganization = "home1-oss-github";

        final Properties systemProperties = new Properties();
        systemProperties.setProperty(INFRASTRUCTURE.getSystemPropertyName(), INFRASTRUCTURE_OPENSOURCE);
        systemProperties.setProperty(SITE.getSystemPropertyName(), BOOL_STRING_TRUE);
        systemProperties.setProperty(SONAR.getSystemPropertyName(), BOOL_STRING_TRUE);
        systemProperties.setProperty(SONAR_ORGANIZATION.getSystemPropertyName(), expectedSonarOrganization);

        final Properties userProperties = new Properties();

        final CiOptionAccessor ciOpts = new CiOptionAccessor(
            new LoggerSlf4jImpl(logger),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        loadedProperties.setProperty(SONAR_HOST_URL.getEnvVariableName(), expectedSonarHostUrl);
        ciOpts.updateSystemProperties(loadedProperties);

        final Properties newProperties = ciOpts.mavenOptsInto(userProperties);
        ciOpts.docker();

        logger.info("{} {}", SONAR_HOST_URL.getEnvVariableName(), ciOpts.getOption(SONAR_HOST_URL).orElse(null));
        logger.info("{} {}", SONAR_HOST_URL.getPropertyName(), userProperties.getProperty(SONAR_HOST_URL.getPropertyName()));
        assertEquals(expectedSonarHostUrl, ciOpts.getOption(SONAR_HOST_URL).orElse(null));
        assertEquals(expectedSonarHostUrl, userProperties.getProperty(SONAR_HOST_URL.getPropertyName()));

        logger.info("{} {}", SONAR_ORGANIZATION.getEnvVariableName(), ciOpts.getOption(SONAR_ORGANIZATION).orElse(null));
        logger.info("{} {}", SONAR_ORGANIZATION.getPropertyName(), userProperties.getProperty(SONAR_ORGANIZATION.getPropertyName()));
        assertEquals(expectedSonarOrganization, ciOpts.getOption(SONAR_ORGANIZATION).orElse(null));
        assertEquals(expectedSonarOrganization, userProperties.getProperty(SONAR_ORGANIZATION.getPropertyName()));
    }
}
