package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_SETTINGS_FILE;
import static top.infra.maven.extension.mavenbuild.MavenSettingsServersEventAware.absentVarsInSettingsXml;
import static top.infra.maven.extension.mavenbuild.MavenSettingsLocalRepositoryEventAware.ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.settings.building.SettingsBuildingRequest;

import top.infra.maven.extension.mavenbuild.utils.PropertiesUtil;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class MavenSettingsFilesEventAware implements MavenEventAware {

    public static final int ORDER_MAVEN_SETTINGS_FILES = ORDER_MAVEN_SETTINGS_LOCALREPOSITORY + 1;

    private final Logger logger;

    private String mavenSettingsPathname;

    @Inject
    public MavenSettingsFilesEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.mavenSettingsPathname = null;
    }

    @Override
    public int getOrder() {
        return ORDER_MAVEN_SETTINGS_FILES;
    }

    @Override
    public void afterInit(final EventSpy.Context context, final String homeDir, final CiOptionAccessor ciOpts) {
        this.mavenSettingsPathname = ciOpts.getOption(MAVEN_SETTINGS_FILE).orElse(null);

        final GitRepository gitRepository = ciOpts.gitRepository();
        gitRepository.downloadMavenSettingsFile(homeDir, this.mavenSettingsPathname);

        final Map<String, Object> contextData = context.getData();
        final Properties systemProperties = (Properties) contextData.get("systemProperties");
        final Properties absentVarsInSettingsXml = absentVarsInSettingsXml(logger, this.mavenSettingsPathname, systemProperties);
        PropertiesUtil.merge(absentVarsInSettingsXml, systemProperties);

        gitRepository.downloadMavenToolchainFile(homeDir);
    }

    @Override
    public void onSettingsBuildingRequest(
        final SettingsBuildingRequest request,
        final String homeDir,
        final CiOptionAccessor ciOpts
    ) {
        if (this.mavenSettingsPathname != null) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Use userSettingsFile [%s] instead of [%s]",
                    this.mavenSettingsPathname, request.getUserSettingsFile()));
            }

            request.setUserSettingsFile(new File(this.mavenSettingsPathname));
        }
    }
}
