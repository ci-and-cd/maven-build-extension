package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.GPG_EXECUTABLE;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_KEYID;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_KEYNAME;
import static top.infra.maven.extension.mavenbuild.CiOption.GPG_PASSPHRASE;
import static top.infra.maven.extension.mavenbuild.CiOption.rootProjectPathname;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.project.ProjectBuildingRequest;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;
import top.infra.maven.extension.mavenbuild.utils.SystemUtils;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class GpgEventAware implements MavenEventAware {

    public static final int ORDER_GPG = ProjectBuilderActivatorModelResolver.ORDER_MODEL_RESOLVER + 1;

    private Logger logger;

    @Inject
    public GpgEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);
    }

    @Override
    public int getOrder() {
        return ORDER_GPG;
    }

    @Override
    public void onProjectBuildingRequest(
        final MavenExecutionRequest mavenExecution,
        final ProjectBuildingRequest projectBuilding,
        final CiOptionAccessor ciOpts
    ) {
        logger.info("    >>>>>>>>>> ---------- decrypt files and handle keys ---------- >>>>>>>>>>");
        final Optional<String> executable = ciOpts.getOption(GPG_EXECUTABLE);
        if (executable.isPresent()) {
            final Optional<String> gpgKeyid = ciOpts.getOption(GPG_KEYID);
            final String gpgKeyname = ciOpts.getOption(GPG_KEYNAME).orElse("");
            final Optional<String> gpgPassphrase = ciOpts.getOption(GPG_PASSPHRASE);
            final Gpg gpg = new Gpg(
                logger,
                SystemUtils.systemUserHome(),
                rootProjectPathname(projectBuilding.getSystemProperties()),
                executable.get(),
                gpgKeyid.orElse(null),
                gpgKeyname,
                gpgPassphrase.orElse(null)
            );
            gpg.decryptAndImportKeys();
        } else {
            logger.warn("Both gpg and gpg2 are not found.");
        }
        logger.info("    <<<<<<<<<< ---------- decrypt files and handle keys ---------- <<<<<<<<<<");
    }
}
