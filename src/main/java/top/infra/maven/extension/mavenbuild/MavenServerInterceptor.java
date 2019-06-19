package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.GitRepository.settingsSecurityXml;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;

import cn.home1.tools.maven.MavenSettingsSecurity;

import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.unix4j.Unix4j;

import top.infra.maven.extension.mavenbuild.utils.SupportFunction;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

/**
 * Fix 'Failed to decrypt passphrase for server foo: org.sonatype.plexus.components.cipher.PlexusCipherException...'.
 */
@Named
@Singleton
public class MavenServerInterceptor {

    static final Pattern PATTERN_ENV_VAR = Pattern.compile("\\$\\{env\\..+?\\}");

    private final Logger logger;
    private final SettingsDecrypter settingsDecrypter;
    private MavenSettingsSecurity settingsSecurity;
    private String encryptedBlankString;

    @Inject
    public MavenServerInterceptor(
        final org.codehaus.plexus.logging.Logger logger,
        final SettingsDecrypter settingsDecrypter
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.settingsDecrypter = settingsDecrypter;
    }

    public static Properties absentVarsInSettingsXml(
        final Logger logger,
        final String mavenSettingsPathname,
        final Properties systemProperties
    ) {
        final Properties result = new Properties();

        final List<String> envVars = SupportFunction.lines(Unix4j.cat(mavenSettingsPathname).toStringResult())
            .stream()
            .flatMap(line -> {
                final Matcher matcher = MavenServerInterceptor.PATTERN_ENV_VAR.matcher(line);
                final List<String> matches = new LinkedList<>();
                while (matcher.find()) {
                    matches.add(matcher.group(0));
                }
                return matches.stream();
            })
            .distinct()
            .map(line -> line.substring(2, line.length() - 1))
            .collect(Collectors.toList());

        envVars.forEach(envVar -> {
            if (!systemProperties.containsKey(envVar)) {
                logger.warn(String.format(
                    "Please set a value for env variable [%s] (in settings.xml), to avoid passphrase decrypt error.", envVar));
            }
        });

        return result;
    }

    public List<String> absentEnvVars(final Server server) {
        final List<String> found = new LinkedList<>();
        if (this.isAbsentEnvVar(server.getPassphrase())) {
            found.add(server.getPassphrase());
        }
        if (this.isAbsentEnvVar(server.getPassword())) {
            found.add(server.getPassword());
        }
        if (this.isAbsentEnvVar(server.getUsername())) {
            found.add(server.getUsername());
        }
        return found.stream().map(line -> line.substring(2, line.length() - 1)).distinct().collect(Collectors.toList());
    }

    public boolean isAbsentEnvVar(final String str) {
        return !isEmpty(str) && PATTERN_ENV_VAR.matcher(str).matches();
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

    public void setHomeDir(final String homeDir) {
        this.settingsSecurity = new MavenSettingsSecurity(settingsSecurityXml(homeDir), false);
        this.encryptedBlankString = this.settingsSecurity.encodeText(" ");
    }

    private String replaceEmptyValue(final String value) {
        final String result;
        if (value == null) {
            result = null; // not a field
        } else if ("".equals(value)) {
            result = this.encryptedBlankString;
        } else {
            if (isAbsentEnvVar(value)) {
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
}
