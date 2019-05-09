package top.infra.maven.extension.mavenbuild;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "mavenBuildExtension")
public class MavenBuildExtension extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    @Requirement
    private RuntimeInformation runtime;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
    }

    @Override
    public void afterProjectsRead(final MavenSession session) {
    }
}
