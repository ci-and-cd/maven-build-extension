package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.TRUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static top.infra.maven.Constants.BOOL_STRING_TRUE;
import static top.infra.maven.extension.mavenbuild.multiinfra.GitPropertiesBean.newJgitProperties;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.DOCKER_REGISTRY_URL;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.SONAR_HOST_URL;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.SONAR_ORGANIZATION;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption.GITHUB_GLOBAL_REPOSITORYOWNER;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption.SONAR;
import static top.infra.maven.extension.mavenbuild.options.MavenOption.GENERATEREPORTS;

import java.util.Properties;

import org.junit.Test;
import org.slf4j.LoggerFactory;

import top.infra.maven.core.CiOptions;
import top.infra.maven.core.GitProperties;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerSlf4jImpl;

public class CiOptionTests {

    private static final org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(CiOptionTests.class);

    private static Logger logger() {
        return new LoggerSlf4jImpl(slf4jLogger);
    }

    private static GitProperties gitProperties() {
        final Logger logger = logger();
        return newJgitProperties(logger)
            .map(GitProperties::newGitProperties)
            .orElseGet(GitProperties::newBlankGitProperties);
    }

    @Test
    public void testDockerRegistry() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(GENERATEREPORTS.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptions ciOpts = new CiOptions(
            gitProperties(),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        loadedProperties.setProperty(DOCKER_REGISTRY_URL.getEnvVariableName(), "https://docker.io/v2/");
        ciOpts.updateSystemProperties(loadedProperties);

        // ciOpts.githubSiteRepoOwner().ifPresent(githubSiteRepoOwner ->
        //     ciOpts.setSystemProperty(GITHUB_GLOBAL_REPOSITORYOWNER, githubSiteRepoOwner));

        final Properties newProperties = CiOptionEventAware.setCiOptPropertiesInto(ciOpts, userProperties);

        slf4jLogger.info("{} {}", DOCKER_REGISTRY_URL.getPropertyName(), ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null));
        slf4jLogger.info("{} {}", DOCKER_REGISTRY.getPropertyName(), ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
        assertEquals("https://docker.io/v2/", ciOpts.getOption(DOCKER_REGISTRY_URL).orElse(null));
        // assertEquals("docker.io", ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
        assertNull(ciOpts.getOption(DOCKER_REGISTRY).orElse(null));
        assertNull(newProperties.getProperty(DOCKER_REGISTRY.getPropertyName()));
        assertFalse(newProperties.containsKey(DOCKER_REGISTRY.getPropertyName()));
    }

    @Test
    public void testGithubSiteRepoOwner() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(GENERATEREPORTS.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptions ciOpts = new CiOptions(
            gitProperties(),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        ciOpts.updateSystemProperties(loadedProperties);

        // final Optional<String> owner = ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER);
        slf4jLogger.info("{} {}", GITHUB_GLOBAL_REPOSITORYOWNER.getPropertyName(), ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).orElse(null));
        assertEquals("ci-and-cd", ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).orElse(null));
    }

    @Test
    public void testGenerateReports() {
        final Properties systemProperties = new Properties();

        final Properties userProperties = new Properties();
        userProperties.setProperty(GENERATEREPORTS.getPropertyName(), BOOL_STRING_TRUE);

        final CiOptions ciOpts = new CiOptions(
            gitProperties(),
            systemProperties,
            userProperties
        );

        slf4jLogger.info("generateReports {}", ciOpts.getOption(GENERATEREPORTS).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(GENERATEREPORTS).orElse(null));

        CiOptionEventAware.ciOptsFromFile(ciOpts, logger()).ifPresent(ciOpts::updateSystemProperties);

        slf4jLogger.info("generateReports {}", ciOpts.getOption(GENERATEREPORTS).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(GENERATEREPORTS).orElse(null));

        CiOptionEventAware.setCiOptPropertiesInto(ciOpts, userProperties);

        slf4jLogger.info("generateReports {}", ciOpts.getOption(GENERATEREPORTS).orElse(null));
        assertEquals(TRUE.toString(), ciOpts.getOption(GENERATEREPORTS).orElse(null));
    }

    @Test
    public void testSonar() {
        final String expectedSonarHostUrl = "https://sonarqube.com";
        final String expectedSonarOrganization = "home1-oss-github";

        final Properties systemProperties = new Properties();
        systemProperties.setProperty(GENERATEREPORTS.getSystemPropertyName(), BOOL_STRING_TRUE);
        systemProperties.setProperty(SONAR.getSystemPropertyName(), BOOL_STRING_TRUE);
        systemProperties.setProperty(SONAR_ORGANIZATION.getSystemPropertyName(), expectedSonarOrganization);

        final Properties userProperties = new Properties();

        final CiOptions ciOpts = new CiOptions(
            gitProperties(),
            systemProperties,
            userProperties
        );

        final Properties loadedProperties = new Properties();
        loadedProperties.setProperty(SONAR_HOST_URL.getEnvVariableName(), expectedSonarHostUrl);
        ciOpts.updateSystemProperties(loadedProperties);

        final Properties newProperties = CiOptionEventAware.setCiOptPropertiesInto(ciOpts, userProperties);

        slf4jLogger.info("{} {}", SONAR_HOST_URL.getEnvVariableName(), ciOpts.getOption(SONAR_HOST_URL).orElse(null));
        slf4jLogger.info("{} {}", SONAR_HOST_URL.getPropertyName(), userProperties.getProperty(SONAR_HOST_URL.getPropertyName()));
        assertEquals(expectedSonarHostUrl, ciOpts.getOption(SONAR_HOST_URL).orElse(null));
        assertEquals(expectedSonarHostUrl, userProperties.getProperty(SONAR_HOST_URL.getPropertyName()));

        slf4jLogger.info("{} {}", SONAR_ORGANIZATION.getEnvVariableName(), ciOpts.getOption(SONAR_ORGANIZATION).orElse(null));
        slf4jLogger.info("{} {}", SONAR_ORGANIZATION.getPropertyName(), userProperties.getProperty(SONAR_ORGANIZATION.getPropertyName()));
        assertEquals(expectedSonarOrganization, ciOpts.getOption(SONAR_ORGANIZATION).orElse(null));
        assertEquals(expectedSonarOrganization, userProperties.getProperty(SONAR_ORGANIZATION.getPropertyName()));
    }
}
