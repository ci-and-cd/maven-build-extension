package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_SETTINGS_FILE;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_SECURITY_XML;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_XML;
import static top.infra.maven.extension.mavenbuild.MavenSettingsLocalRepositoryEventAware.ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.os;

import java.io.File;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;
import org.apache.maven.settings.building.SettingsBuildingRequest;

import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
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
    public void afterInit(final Context context, final CiOptionAccessor ciOpts) {
        this.mavenSettingsPathname = ciOpts.getOption(MAVEN_SETTINGS_FILE).orElse(null);

        final GitRepository gitRepository = ciOpts.gitRepository();

        ciOpts.createCacheInfrastructure();
        this.downloadMavenSettingsFile(gitRepository, this.mavenSettingsPathname);
        this.downloadMavenToolchainFile(gitRepository);
    }

    @Override
    public void onSettingsBuildingRequest(
        final SettingsBuildingRequest request,
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

    private void downloadMavenSettingsFile(final GitRepository gitRepository, final String settingsXml) {
        // settings.xml
        logger.info(">>>>>>>>>> ---------- run_mvn settings.xml and settings-security.xml ---------- >>>>>>>>>>");
        if (isNotEmpty(settingsXml) && !new File(settingsXml).exists()) {
            gitRepository.download(SRC_MAVEN_SETTINGS_XML, settingsXml, true);
        }

        // settings-security.xml
        gitRepository.download(SRC_MAVEN_SETTINGS_SECURITY_XML, MavenUtils.settingsSecurityXml(), false);
        logger.info("<<<<<<<<<< ---------- run_mvn settings.xml and settings-security.xml ---------- <<<<<<<<<<");
    }

    private void downloadMavenToolchainFile(final GitRepository gitRepository) {
        // toolchains.xml
        logger.info(">>>>>>>>>> ---------- download toolchains.xml ---------- >>>>>>>>>>");
        final String os = os();
        final String toolchainsSource = "generic".equals(os)
            ? "src/main/maven/toolchains.xml"
            : "src/main/maven/toolchains-" + os + ".xml";
        gitRepository.download(toolchainsSource, MavenUtils.toolchainsXml(), true);
        logger.info("<<<<<<<<<< ---------- download toolchains.xml ---------- <<<<<<<<<<");
    }
}
