package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.PrintInfoEventAware.ORDER_PRINT_INFO;

import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;

import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
import top.infra.maven.extension.mavenbuild.utils.SystemUtils;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

/**
 * Move -Dproperty=value in MAVEN_OPTS from systemProperties into userProperties (maven does not do this automatically).
 * We need this to activate profiles depend on these properties correctly.
 */
@Named
@Singleton
public class SystemToUserPropertiesEventAware implements MavenEventAware {

    public static final int ORDER_SYSTEM_TO_USER_PROPERTIES = ORDER_PRINT_INFO + 1;

    private static final String PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY = "maven.multiModuleProjectDirectory";

    private final Logger logger;

    @Inject
    public SystemToUserPropertiesEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);
    }

    @Override
    public int getOrder() {
        return ORDER_SYSTEM_TO_USER_PROPERTIES;
    }

    @Override
    public void onInit(final Context context) {
        final Properties systemProperties = MavenUtils.systemProperties(context);
        final Properties userProperties = MavenUtils.userProperties(context);

        if (userProperties.getProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY) == null) {
            final String mavenMultiModuleProjectDirectory = systemProperties.getProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY);
            if (mavenMultiModuleProjectDirectory != null) {
                userProperties.setProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, mavenMultiModuleProjectDirectory);
            } else {
                final String systemUserDir = SystemUtils.systemUserDir();
                logger.warn(String.format(
                    "Value of system property [%s] not found, use user.dir [%s] instead.",
                    PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, systemUserDir
                ));
                userProperties.setProperty(PROP_MAVEN_MULTIMODULEPROJECTDIRECTORY, systemUserDir);
            }
        }
    }
}
