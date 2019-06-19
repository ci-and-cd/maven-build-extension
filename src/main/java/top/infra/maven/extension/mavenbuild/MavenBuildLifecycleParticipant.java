package top.infra.maven.extension.mavenbuild;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.Settings;
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

    @Requirement
    private RuntimeInformation runtime;

    @Requirement
    private MavenSettingsServersEventAware mavenServerInterceptor;

    public MavenBuildLifecycleParticipant() {
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

            final Settings settings = session.getSettings();
            if (settings != null) {
                final List<String> envVars = settings.getServers()
                    .stream()
                    .flatMap(server -> this.mavenServerInterceptor.absentEnvVars(server).stream())
                    .distinct()
                    .collect(Collectors.toList());
                envVars.forEach(envVar -> {
                    logger.info(
                        String.format("Set a value for env variable [%s] (in settings.xml), to avoid passphrase decrypt error.", envVar));
                    session.getSystemProperties().setProperty(envVar, this.mavenServerInterceptor.getEncryptedBlankString());
                });

                this.mavenServerInterceptor.checkServers(settings.getServers());
            }
        }
    }

    @Override
    public void afterSessionEnd(final MavenSession session) throws MavenExecutionException {

    }

    private boolean isOnRootProject(final MavenSession session) {
        return session != null
            && session.getExecutionRootDirectory() != null
            && session.getCurrentProject() != null
            && session.getExecutionRootDirectory().equalsIgnoreCase(session.getCurrentProject().getBasedir().toString());
    }
}
