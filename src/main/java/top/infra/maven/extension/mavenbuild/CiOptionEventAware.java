package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.GITHUB_GLOBAL_REPOSITORYOWNER;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PATTERN_CI_ENV_VARS;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;

import top.infra.maven.extension.mavenbuild.utils.PropertiesUtil;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class CiOptionEventAware implements MavenEventAware {

    public static final int ORDER_CI_OPTION = PrintInfoEventAware.ORDER_PRINT_INFO + 1;

    private Logger logger;

    private CiOptionAccessor ciOpts;

    @Inject
    public CiOptionEventAware(
        final org.codehaus.plexus.logging.Logger logger
    ) {
        this.logger = new LoggerPlexusImpl(logger);
    }

    public CiOptionAccessor getCiOpts(final Context context) {
        if (this.ciOpts != null) {
            return this.ciOpts;
        } else {
            if (context != null) {
                this.onInit(context);
                return this.ciOpts;
            } else {
                throw new IllegalStateException("ciOpts not initialized.");
            }
        }
    }

    @Override
    public int getOrder() {
        return ORDER_CI_OPTION;
    }

    @Override
    public void onInit(final Context context) {
        final Map<String, Object> contextData = context.getData();
        final Properties systemProperties = (Properties) contextData.get("systemProperties");
        final Properties userProperties = (Properties) contextData.get("userProperties");
        final GitProperties gitProperties = GitProperties.newInstance(logger).orElseGet(() -> GitProperties.newBlankInstance(logger));
        this.ciOpts = new CiOptionAccessor(
            logger,
            gitProperties,
            systemProperties,
            userProperties
        );

        checkGitAuthToken(logger, this.ciOpts);

        final Optional<String> gitRefName = this.ciOpts.getOption(GIT_REF_NAME);
        if ((!gitRefName.isPresent() || isEmpty(gitRefName.get())) && logger.isWarnEnabled()) {
            logger.warn(String.format("Can not find value of %s (%s)", GIT_REF_NAME.getEnvVariableName(), GIT_REF_NAME.getPropertyName()));
        }

        logger.info(">>>>>>>>>> ---------- load options from file ---------- >>>>>>>>>>");
        // ci options from file
        final Properties loadedProperties = ciOpts.ciOptsFromFile();
        this.ciOpts.updateSystemProperties(loadedProperties);
        if (logger.isInfoEnabled()) {
            logger.info("    >>>>>>>>>> ---------- loadedProperties ---------- >>>>>>>>>>");
            logger.info(PropertiesUtil.toString(loadedProperties, PATTERN_CI_ENV_VARS));
            logger.info("    <<<<<<<<<< ---------- loadedProperties ---------- <<<<<<<<<<");
        }

        // github site options

        this.ciOpts.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).ifPresent(owner ->
            systemProperties.setProperty(GITHUB_GLOBAL_REPOSITORYOWNER.getSystemPropertyName(), owner));
        logger.info("<<<<<<<<<< ---------- load options from file ---------- <<<<<<<<<<");

        // maven options
        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- set options (update userProperties) ---------- >>>>>>>>>>");
        }

        final Properties newProperties = this.ciOpts.mergeCiOptsInto(userProperties);

        if (logger.isInfoEnabled()) {
            logger.info(PropertiesUtil.toString(systemProperties, PATTERN_CI_ENV_VARS));
            logger.info(PropertiesUtil.toString(userProperties, null));
            logger.info("<<<<<<<<<< ---------- set options (update userProperties) ---------- <<<<<<<<<<");
        }
    }

    private static void checkGitAuthToken(final Logger logger, final CiOptionAccessor ciOpts) {
        logger.info(">>>>>>>>>> ---------- check GIT_AUTH_TOKEN  ---------- >>>>>>>>>>");
        if (isEmpty(ciOpts.getOption(GIT_AUTH_TOKEN).orElse(null))) {
            final boolean infraOpenSource = ciOpts.getOption(INFRASTRUCTURE).map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);
            final Boolean originRepo = ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(FALSE);
            if (originRepo && infraOpenSource) {
                final String errorMsg = String.format(
                    "%s not set and using origin private repo, exit.", GIT_AUTH_TOKEN.getEnvVariableName());
                final RuntimeException error = new RuntimeException(errorMsg);
                logger.error(errorMsg);
                throw error;
            } else {
                // For PR build on travis-ci or appveyor
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("%s not set.", GIT_AUTH_TOKEN.getEnvVariableName()));
                }
            }
        }
        logger.info("<<<<<<<<<< ---------- check GIT_AUTH_TOKEN ---------- <<<<<<<<<<");
    }
}
