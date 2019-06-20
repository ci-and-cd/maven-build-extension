package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.MAVEN_SETTINGS_FILE;
import static top.infra.maven.extension.mavenbuild.MavenSettingsFilesEventAware.ORDER_MAVEN_SETTINGS_FILES;
import static top.infra.maven.extension.mavenbuild.MavenSettingsLocalRepositoryEventAware.ORDER_MAVEN_SETTINGS_LOCALREPOSITORY;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isNotEmpty;

import cn.home1.tools.maven.MavenSettingsSecurity;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.unix4j.Unix4j;

import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
import top.infra.maven.extension.mavenbuild.utils.SupportFunction;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

/**
 * Auto fill empty or blank properties (e.g. CI_OPT_GPG_PASSPHRASE) in maven settings.xml.
 * Fix 'Failed to decrypt passphrase for server foo: org.sonatype.plexus.components.cipher.PlexusCipherException...'.
 */
@Named
@Singleton
public class MavenSettingsServersEventAware implements MavenEventAware {

    public static final int ORDER_MAVEN_SETTINGS_SERVERS = ORDER_MAVEN_SETTINGS_FILES + 1;// ORDER_MAVEN_SETTINGS_LOCALREPOSITORY + 1;

    static final Pattern PATTERN_ENV_VAR = Pattern.compile("\\$\\{env\\..+?\\}");

    private final Logger logger;

    private final SettingsDecrypter settingsDecrypter;

    private String encryptedBlankString;

    private MavenSettingsSecurity settingsSecurity;

    @Inject
    public MavenSettingsServersEventAware(
        final org.codehaus.plexus.logging.Logger logger,
        final SettingsDecrypter settingsDecrypter
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.settingsDecrypter = settingsDecrypter;

        this.encryptedBlankString = null;
        this.settingsSecurity = null;
    }

    public List<String> absentEnvVars(final Server server) {
        final List<String> found = new LinkedList<>();
        if (this.isSystemPropertyNameOfEnvVar(server.getPassphrase())) {
            found.add(server.getPassphrase());
        }
        if (this.isSystemPropertyNameOfEnvVar(server.getPassword())) {
            found.add(server.getPassword());
        }
        if (this.isSystemPropertyNameOfEnvVar(server.getUsername())) {
            found.add(server.getUsername());
        }
        return found.stream().map(line -> line.substring(2, line.length() - 1)).distinct().collect(Collectors.toList());
    }

    public void checkServers(final List<Server> servers) {
        // see: https://github.com/shyiko/servers-maven-extension/blob/master/src/main/java/com/github/shyiko/sme/ServersExtension.java
        for (final Server server : servers) {

            if (server.getPassphrase() != null) {
                this.serverPassphrase(server);
            }
            if (server.getPassword() != null) {
                this.serverPassword(server);
            }
            if (server.getUsername() != null) {
                this.serverUsername(server);
            }

            // final SettingsDecryptionRequest decryptionRequest = new DefaultSettingsDecryptionRequest(server);
            // final SettingsDecryptionResult decryptionResult = this.settingsDecrypter.decrypt(decryptionRequest);
            // final Server decryptedServer = decryptionResult.getServer();
        }
    }

    public String getEncryptedBlankString() {
        return this.encryptedBlankString;
    }

    @Override
    public int getOrder() {
        return ORDER_MAVEN_SETTINGS_SERVERS;
    }

    @Override
    public void afterInit(final Context context, final CiOptionAccessor ciOpts) {
        final String settingsXmlPathname = ciOpts.getOption(MAVEN_SETTINGS_FILE).orElse(null);

        final Properties systemProperties = (Properties) context.getData().get("systemProperties");
        // final Properties absentVarsInSettingsXml = absentVarsInSettingsXml(logger, settingsXmlPathname, systemProperties);
        // PropertiesUtils.merge(absentVarsInSettingsXml, systemProperties);

        final List<String> envVars = absentVarsInSettingsXml(logger, settingsXmlPathname, systemProperties);
        envVars.forEach(envVar -> {
            if (!systemProperties.containsKey(envVar)) {
                logger.warn(String.format(
                    "Please set a value for env variable [%s] (in settings.xml), to avoid passphrase decrypt error.", envVar));
            }
        });
    }

    @Override
    public void onMavenExecutionRequest(final MavenExecutionRequest request, final CiOptionAccessor ciOpts) {
        this.settingsSecurity = new MavenSettingsSecurity(MavenUtils.settingsSecurityXml(), false);
        this.encryptedBlankString = this.settingsSecurity.encodeText(" ");
        this.checkServers(request.getServers());
    }

    private boolean isSystemPropertyNameOfEnvVar(final String str) {
        return !isEmpty(str) && PATTERN_ENV_VAR.matcher(str).matches();
    }

    private String replaceEmptyValue(final String value) {
        final String result;
        if (value == null) {
            result = null; // not a field
        } else if ("".equals(value)) {
            result = this.encryptedBlankString;
        } else {
            if (isSystemPropertyNameOfEnvVar(value)) {
                result = this.encryptedBlankString;
            } else {
                result = value;
            }
        }
        return result;
    }

    private void serverPassphrase(final Server server) {
        final String passphrase = this.replaceEmptyValue(server.getPassphrase());
        if (passphrase != null && !passphrase.equals(server.getPassphrase())) {
            logger.info(String.format("server [%s] has a empty passphrase [%s]", server.getId(), server.getPassphrase()));
            server.setPassphrase(passphrase);
        }
    }

    private void serverPassword(final Server server) {
        final String password = this.replaceEmptyValue(server.getPassword());
        if (password != null && !password.equals(server.getPassword())) {
            logger.info(String.format("server [%s] has a empty password [%s]", server.getId(), server.getPassword()));
            server.setPassword(password);
        }
    }

    private void serverUsername(final Server server) {
        final String username = this.replaceEmptyValue(server.getUsername());
        if (username != null && !username.equals(server.getUsername())) {
            logger.info(String.format("server [%s] has a empty username [%s]", server.getId(), server.getUsername()));
            server.setUsername(username);
        }
    }

    /**
     * Find properties that absent in systemProperties but used in settings.xml.
     *
     * @param logger              logger
     * @param settingsXmlPathname settings.xml
     * @param systemProperties    systemProperties of current maven session
     * @return variables absent in systemProperties but used in settings.xml
     */
    private static List<String> absentVarsInSettingsXml(
        final Logger logger,
        final String settingsXmlPathname,
        final Properties systemProperties
    ) {
        return isNotEmpty(settingsXmlPathname)
            ? SupportFunction.lines(Unix4j.cat(settingsXmlPathname).toStringResult())
            .stream()
            .flatMap(line -> {
                final Matcher matcher = PATTERN_ENV_VAR.matcher(line);
                final List<String> matches = new LinkedList<>();
                while (matcher.find()) {
                    matches.add(matcher.group(0));
                }
                return matches.stream();
            })
            .distinct()
            .map(line -> line.substring(2, line.length() - 1))
            .collect(Collectors.toList())
            : Collections.emptyList();
    }
}
