package top.infra.maven.extension.mavenbuild;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

/**
 * see: https://maven.apache.org/examples/maven-3-lifecycle-extensions.html
 */
@Component(role = AbstractMavenLifecycleParticipant.class, hint = "MavenBuildLifecycleParticipant")
public class MavenBuildLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    public MavenBuildLifecycleParticipant() {
        // no-op
    }

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        if (session != null) {
            if (isOnRootProject(session)) {
                logger.info(String.format("LifecycleParticipant afterProjectsRead on executionRoot [%s]", session.getCurrentProject()));
            } else {
                logger.info(String.format("LifecycleParticipant afterProjectsRead [%s]", session.getCurrentProject()));
            }
        }
    }

    @Override
    public void afterSessionStart(final MavenSession session) throws MavenExecutionException {
        if (session != null) {
            if (isOnRootProject(session)) {
                logger.info(String.format("LifecycleParticipant afterSessionStart on executionRoot [%s]", session.getCurrentProject()));
            } else {
                logger.info(String.format("LifecycleParticipant afterSessionStart [%s]", session.getCurrentProject()));
            }
        }
    }

    @Override
    public void afterSessionEnd(final MavenSession session) throws MavenExecutionException {
        // no-op
    }

    private boolean isOnRootProject(final MavenSession session) {
        return session != null
            && session.getExecutionRootDirectory() != null
            && session.getCurrentProject() != null
            && session.getExecutionRootDirectory().equalsIgnoreCase(session.getCurrentProject().getBasedir().toString());
    }
}
