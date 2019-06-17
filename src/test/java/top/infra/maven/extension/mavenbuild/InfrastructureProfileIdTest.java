package top.infra.maven.extension.mavenbuild;

import static org.junit.Assert.assertEquals;
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
    }

    private void assertProfile(final String expected, final String id) {
        final Optional<String> profileInfrastructure = profileInfrastructure(id);
        assertTrue(profileInfrastructure.isPresent());
        slf4jLogger.info(profileInfrastructure.orElse(null));
        assertEquals(expected, profileInfrastructure.orElse(null));
    }
}
