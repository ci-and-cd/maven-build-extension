package top.infra.maven.extension.mavenbuild;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.infra.maven.extension.mavenbuild.InfrastructureActivator.profileInfrastructure;

import java.util.Optional;

import org.junit.Test;
import org.slf4j.LoggerFactory;

public class InfrastructureProfileIdTest {

    private static final org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(InfrastructureProfileIdTest.class);

    @Test
    public void testInfrastructureProfileIds() {
        this.assertProfile("opensource", "infrastructure_opensource");
        this.assertProfile("opensource", "infrastructure_opensource-github_site");
        this.assertProfile("opensource", "infrastructure_opensource-nexus2-staging");
        this.assertProfile("opensource", "infrastructure_opensource-site");

        this.assertProfile("private", "infrastructure_private");
        this.assertProfile("private", "infrastructure_private-site");

        this.assertNotInfraProfile("run-on-multi-module-root-and-sub-modules");
        this.assertNotInfraProfile("parent-java-8-profile2");
    }

    private void assertProfile(final String expected, final String id) {
        final Optional<String> profileInfrastructure = profileInfrastructure(id);
        slf4jLogger.info(profileInfrastructure.orElse(null));
        assertTrue(profileInfrastructure.isPresent());
        assertEquals(expected, profileInfrastructure.orElse(null));
    }

    private void assertNotInfraProfile(final String id) {
        final Optional<String> profileInfrastructure = profileInfrastructure(id);
        slf4jLogger.info(profileInfrastructure.orElse(null));
        assertFalse(profileInfrastructure.isPresent());
    }
}
