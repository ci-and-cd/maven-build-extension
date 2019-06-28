package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.Constants.SRC_CI_OPTS_PROPERTIES;
import static top.infra.maven.core.CiOptionNames.PATTERN_VARS_ENV_DOT_CI;
import static top.infra.maven.extension.mavenbuild.SystemToUserPropertiesEventAware.ORDER_SYSTEM_TO_USER_PROPERTIES;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.CACHE_SETTINGS_PATH;
import static top.infra.maven.extension.mavenbuild.multiinfra.InfraOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.CACHE_SESSION_PATH;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption.GIT_REF_NAME;
import static top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption.GITHUB_GLOBAL_REPOSITORYOWNER;
import static top.infra.maven.utils.SupportFunction.isEmpty;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;

import top.infra.maven.core.CiOptions;
import top.infra.maven.core.GitProperties;
import top.infra.maven.exception.RuntimeIOException;
import top.infra.maven.extension.MavenEventAware;
import top.infra.maven.extension.mavenbuild.multiinfra.GitPropertiesBean;
import top.infra.maven.extension.mavenbuild.multiinfra.GitRepository;
import top.infra.maven.extension.mavenbuild.multiinfra.InfraOption;
import top.infra.maven.extension.mavenbuild.options.MavenBuildExtensionOption;
import top.infra.maven.extension.mavenbuild.options.MavenBuildPomOption;
import top.infra.maven.extension.mavenbuild.options.MavenOption;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;
import top.infra.maven.utils.FileUtils;
import top.infra.maven.utils.MavenUtils;
import top.infra.maven.utils.PropertiesUtils;

@Named
@Singleton
public class CiOptionEventAware implements MavenEventAware {

    public static final int ORDER_CI_OPTION = ORDER_SYSTEM_TO_USER_PROPERTIES + 1;

    private final Logger logger;

    private final GitProperties gitProperties;

    private CiOptions ciOpts;

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

    public CiOptions getCiOpts(final Context context) {
        if (this.ciOpts != null) {
            return this.ciOpts;
        } else {
            if (context != null) {
                final Properties systemProperties = MavenUtils.systemProperties(context);
                final Properties userProperties = MavenUtils.userProperties(context);
                final CiOptions result = new CiOptions(
                    this.gitProperties,
                    systemProperties,
                    userProperties
                );
                result.getOption(CACHE_SETTINGS_PATH).ifPresent(FileUtils::createDirectories);
                result.getOption(CACHE_SESSION_PATH).ifPresent(FileUtils::createDirectories);

                checkGitAuthToken(logger, result);

                // ci options from file
                this.loadedProperties = ciOptsFromFile(result, logger).orElse(null);
                result.updateSystemProperties(this.loadedProperties);

                // github site options
                result.getOption(GITHUB_GLOBAL_REPOSITORYOWNER).ifPresent(owner ->
                    systemProperties.setProperty(GITHUB_GLOBAL_REPOSITORYOWNER.getSystemPropertyName(), owner));

                // write all ciOpt properties into systemProperties userProperties
                this.ciOptProperties = setCiOptPropertiesInto(result, userProperties);
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
            if (this.loadedProperties != null) {
                logger.info(">>>>>>>>>> ---------- load options from file ---------- >>>>>>>>>>");
                logger.info(PropertiesUtils.toString(this.loadedProperties, null));
                logger.info("<<<<<<<<<< ---------- load options from file ---------- <<<<<<<<<<");
            }

            logger.info(">>>>>>>>>> ---------- set options (update userProperties) ---------- >>>>>>>>>>");
            Stream.of(
                Arrays.asList(InfraOption.values()),
                Arrays.asList(MavenBuildExtensionOption.values()),
                Arrays.asList(MavenOption.values()),
                Arrays.asList(MavenBuildPomOption.values()))
                .flatMap(collection -> collection.stream().sorted())
                .forEach(ciOption -> { // TODO better toString methods
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

    private static void checkGitAuthToken(final Logger logger, final CiOptions ciOpts) {
        if (isEmpty(ciOpts.getOption(GIT_AUTH_TOKEN).orElse(null))) {
            // if (!originRepo) { // For PR build on travis-ci or appveyor }
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("%s not set.", GIT_AUTH_TOKEN.getEnvVariableName()));
            }
        }
    }

    static Optional<Properties> ciOptsFromFile(
        final CiOptions ciOpts,
        final Logger logger
    ) {
        return ciOpts.getOption(InfraOption.CI_OPTS_FILE).map(ciOptsFile -> {
            final Properties properties = new Properties();

            ciOpts.getOption(CACHE_SETTINGS_PATH).ifPresent(FileUtils::createDirectories);

            final boolean offline = MavenUtils.cmdArgOffline(ciOpts.getSystemProperties()).orElse(FALSE);
            final boolean update = MavenUtils.cmdArgUpdate(ciOpts.getSystemProperties()).orElse(FALSE);
            GitRepository.newGitRepository(ciOpts, logger).ifPresent(repo -> {
                repo.download(SRC_CI_OPTS_PROPERTIES, ciOptsFile, true, offline, update);

                try {
                    properties.load(new FileInputStream(ciOptsFile));
                } catch (final IOException ex) {
                    final String errorMsg = String.format("Can not load ci options file %s", ex.getMessage());
                    throw new RuntimeIOException(errorMsg, ex);
                }
            });

            return properties;
        });
    }

    static Properties setCiOptPropertiesInto(final CiOptions ciOpts, final Properties... targetProperties) {
        final Properties ciOptProperties = new Properties();

        Stream.of(
            Arrays.asList(InfraOption.values()),
            Arrays.asList(MavenBuildExtensionOption.values()),
            Arrays.asList(MavenOption.values()),
            Arrays.asList(MavenBuildPomOption.values()))
            .flatMap(collection -> collection.stream().sorted())
            .forEach(ciOption -> ciOption.setProperties(
                ciOpts.getGitProperties(),
                ciOpts.getSystemProperties(),
                ciOpts.getUserProperties(),
                ciOptProperties));

        for (final Properties target : targetProperties) {
            PropertiesUtils.merge(ciOptProperties, target);
        }

        return ciOptProperties;
    }
}
