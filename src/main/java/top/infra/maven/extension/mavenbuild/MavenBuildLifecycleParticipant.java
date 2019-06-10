package top.infra.maven.extension.mavenbuild;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "MavenBuildLifecycleParticipant")
public class MavenBuildLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    @Requirement
    private RuntimeInformation runtime;

    public MavenBuildLifecycleParticipant() {
    }

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        if (isOnRootProject(session)) {
            logger.info(String.format("Run afterProjectsRead on executionRoot %s", session.getCurrentProject()));
        }
    }

    @Override
    public void afterSessionEnd(final MavenSession session) throws MavenExecutionException {

    }

    @Override
    public void afterSessionStart(final MavenSession session) throws MavenExecutionException {

    }

    private boolean isOnRootProject(final MavenSession session) {
        return session != null
            && session.getExecutionRootDirectory() != null
            && session.getCurrentProject() != null
            && session.getExecutionRootDirectory().equalsIgnoreCase(session.getCurrentProject().getBasedir().toString());
    }
}
