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

    private String settingsXmlPathname;

    @Inject
    public MavenSettingsFilesEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.settingsXmlPathname = null;
    }

    @Override
    public int getOrder() {
        return ORDER_MAVEN_SETTINGS_FILES;
    }

    @Override
    public void afterInit(final Context context, final CiOptionAccessor ciOpts) {
        this.settingsXmlPathname = ciOpts.getOption(MAVEN_SETTINGS_FILE).orElse(null);

        final GitRepository gitRepository = ciOpts.gitRepository();

        ciOpts.createCacheInfrastructure();
        logger.info(">>>>>>>>>> ---------- download settings.xml and settings-security.xml ---------- >>>>>>>>>>");
        this.downloadSettingsXml(gitRepository);
        this.downloadSettingsSecurityXml(gitRepository);
        logger.info("<<<<<<<<<< ---------- download settings.xml and settings-security.xml ---------- <<<<<<<<<<");

        logger.info(">>>>>>>>>> ---------- download toolchains.xml ---------- >>>>>>>>>>");
        this.downloadToolchainsXml(gitRepository);
        logger.info("<<<<<<<<<< ---------- download toolchains.xml ---------- <<<<<<<<<<");
    }

    @Override
    public void onSettingsBuildingRequest(
        final SettingsBuildingRequest request,
        final CiOptionAccessor ciOpts
    ) {
        if (this.settingsXmlPathname != null) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Use userSettingsFile [%s] instead of [%s]",
                    this.settingsXmlPathname, request.getUserSettingsFile()));
            }

            request.setUserSettingsFile(new File(this.settingsXmlPathname));
        }
    }

    private void downloadSettingsXml(final GitRepository gitRepository) {
        // settings.xml
        final String settingsXml = this.settingsXmlPathname;
        if (isNotEmpty(settingsXml) && !new File(settingsXml).exists()) {
            gitRepository.download(SRC_MAVEN_SETTINGS_XML, settingsXml, true);
        }
    }

    private void downloadSettingsSecurityXml(final GitRepository gitRepository) {
        // settings-security.xml (optional)
        final String settingsSecurityXml = MavenUtils.settingsSecurityXml();
        gitRepository.download(SRC_MAVEN_SETTINGS_SECURITY_XML, settingsSecurityXml, false);
    }

    private void downloadToolchainsXml(final GitRepository gitRepository) {
        // toolchains.xml
        final String os = os();
        final String sourceFile = "generic".equals(os)
            ? "src/main/maven/toolchains.xml"
            : "src/main/maven/toolchains-" + os + ".xml";
        final String toolchainsXml = MavenUtils.toolchainsXml();
        gitRepository.download(sourceFile, toolchainsXml, true);
    }
}
