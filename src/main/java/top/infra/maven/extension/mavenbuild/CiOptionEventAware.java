package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.GITHUB_GLOBAL_REPOSITORYOWNER;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.CiOption.ORIGIN_REPO;
import static top.infra.maven.extension.mavenbuild.CiOption.PATTERN_VARS_ENV_DOT_CI;
import static top.infra.maven.extension.mavenbuild.Constants.INFRASTRUCTURE_OPENSOURCE;
import static top.infra.maven.extension.mavenbuild.SystemToUserPropertiesEventAware.ORDER_SYSTEM_TO_USER_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;

import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
import top.infra.maven.extension.mavenbuild.utils.PropertiesUtils;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class CiOptionEventAware implements MavenEventAware {

    public static final int ORDER_CI_OPTION = ORDER_SYSTEM_TO_USER_PROPERTIES + 1;

    private final Logger logger;

    private final GitProperties gitProperties;

    private CiOptionAccessor ciOpts;

    private Properties ciOptProperties;
    private Properties loadedProperties;

    @Inject
    public CiOptionEventAware(
        final org.codehaus.plexus.logging.Logger logger,
        final GitPropertiesBean gitProperties
    ) {
        this.logger = new LoggerPlexusImpl(logger);
        this.gitProperties = gitProperties;
    }

    public CiOptionAccessor getCiOpts(final Context context) {
        if (this.ciOpts != null) {
            return this.ciOpts;
        } else {
            if (context != null) {
                final Properties systemProperties = MavenUtils.systemProperties(context);
                final Properties userProperties = MavenUtils.userProperties(context);
                final CiOptionAccessor result = new CiOptionAccessor(
                    this.gitProperties,
                    systemProperties,
                    userProperties
                );
                result.createCacheInfrastructure();
                result.createCacheSession();

                checkGitAuthToken(logger, result);

                // ci options from file
                this.loadedProperties = result.ciOptsFromFile(logger);
                result.updateSystemProperties(this.loadedProperties);

                // github site options
                result.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).ifPresent(owner ->
                    systemProperties.setProperty(GITHUB_GLOBAL_REPOSITORYOWNER.getSystemPropertyName(), owner));

                // write all ciOpt properties into systemProperties userProperties
                this.ciOptProperties = result.setCiOptPropertiesInto(userProperties);
                return result;
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
        this.ciOpts = this.getCiOpts(context);

        final Optional<String> gitRefName = ciOpts.getOption(GIT_REF_NAME);
        if ((!gitRefName.isPresent() || isEmpty(gitRefName.get())) && logger.isWarnEnabled()) {
            logger.warn(String.format(
                "Can not find value of %s (%s)",
                GIT_REF_NAME.getEnvVariableName(), GIT_REF_NAME.getPropertyName()
            ));
        }

        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- load options from file ---------- >>>>>>>>>>");
            logger.info("    >>>>>>>>>> ---------- loadedProperties ---------- >>>>>>>>>>");
            logger.info(PropertiesUtils.toString(this.loadedProperties, null));
            logger.info("    <<<<<<<<<< ---------- loadedProperties ---------- <<<<<<<<<<");
            logger.info("<<<<<<<<<< ---------- load options from file ---------- <<<<<<<<<<");

            logger.info(">>>>>>>>>> ---------- set options (update userProperties) ---------- >>>>>>>>>>");
            Arrays.stream(CiOption.values()).sorted().forEach(ciOption -> { // TODO better toString methods
                final String displayName = ciOption.getEnvVariableName();
                final String displayValue = this.ciOptProperties.getProperty(ciOption.getPropertyName(), "");
                logger.info(PropertiesUtils.maskSecrets(String.format("setOption %s=%s", displayName, displayValue)));
            });
            logger.info("<<<<<<<<<< ---------- set options (update userProperties) ---------- <<<<<<<<<<");

            final Properties systemProperties = MavenUtils.systemProperties(context);
            final Properties userProperties = MavenUtils.userProperties(context);
            logger.info(PropertiesUtils.toString(systemProperties, PATTERN_VARS_ENV_DOT_CI));
            logger.info(PropertiesUtils.toString(userProperties, null));
        }
    }

    private static void checkGitAuthToken(final Logger logger, final CiOptionAccessor ciOpts) {
        logger.info(">>>>>>>>>> ---------- check GIT_AUTH_TOKEN  ---------- >>>>>>>>>>");
        if (isEmpty(ciOpts.getOption(GIT_AUTH_TOKEN).orElse(null))) {
            final boolean openSource = ciOpts.getOption(INFRASTRUCTURE).map(INFRASTRUCTURE_OPENSOURCE::equals).orElse(FALSE);
            final Boolean originRepo = ciOpts.getOption(ORIGIN_REPO).map(Boolean::parseBoolean).orElse(FALSE);
            if (originRepo && !openSource) {
                final String errorMsg = String.format(
                    "%s not set when using a private git repo, exit.",
                    GIT_AUTH_TOKEN.getEnvVariableName());
                final NoSuchElementException error = new NoSuchElementException(errorMsg);
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
