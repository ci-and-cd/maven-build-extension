package top.infra.maven.extension.mavenbuild;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static top.infra.maven.extension.mavenbuild.SupportFunction.concat;
import static top.infra.maven.extension.mavenbuild.SupportFunction.exists;
import static top.infra.maven.extension.mavenbuild.SupportFunction.lines;
import static top.infra.maven.extension.mavenbuild.SupportFunction.readFile;
import static top.infra.maven.extension.mavenbuild.SupportFunction.stackTrace;
import static top.infra.maven.extension.mavenbuild.SupportFunction.writeFile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class Gpg {

    private static final String CODESIGNING_PUB = "codesigning.pub";
    private static final String CODESIGNING_ASC = "codesigning.asc";
    private static final String CODESIGNING_ASC_ENC = "codesigning.asc.enc";
    private static final String CODESIGNING_ASC_GPG = "codesigning.asc.gpg";
    private static final String DOT_GNUPG = ".gnupg";

    private final Logger logger;

    private final String homeDir;
    private final String workingDir;
    private final String keyId;
    private final String keyName;
    private final String passphrase;

    private final String[] cmd;
    private final Map<String, String> environment;

    public Gpg(
        final Logger logger,
        final String homeDir,
        final String workingDir,
        final String executable,
        final String keyId,
        final String keyName,
        final String passphrase
    ) {
        this.logger = logger;
        this.homeDir = homeDir;
        this.workingDir = workingDir;
        this.keyId = keyId;
        this.keyName = keyName;
        this.passphrase = passphrase;

        this.cmd = "gpg2".equals(executable) ? new String[]{"gpg2", "--use-agent"} : new String[]{"gpg"};
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Using %s", Arrays.toString(this.cmd)));
        }

        final Map<String, String> env = new LinkedHashMap<>();
        env.put("LC_CTYPE", "UTF-8");
        final Entry<Integer, String> tty = SupportFunction.exec("tty");
        if (tty.getKey() == 0) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("GPG_TTY=%s", tty.getValue()));
            }
            env.put("GPG_TTY", tty.getValue());
        }
        this.environment = Collections.unmodifiableMap(env);
    }

    public void decryptFiles() {
        // config gpg (version > 2.1)
        this.configFile();

        // decrypt gpg key
        this.decryptKey();

        this.importPublicKeys();

        this.importPrivateKeys();
    }

    public void configFile() {
        // use --batch=true to avoid 'gpg tty not a tty' error
        final Entry<Integer, String> resultGpgVersion = this.exec(this.gpgBatch("--version"));
        logger.info(resultGpgVersion.getValue());

        final Path dotGnupg = Paths.get(homeDir, DOT_GNUPG);
        final Path dotGnupgGpgConf = Paths.get(homeDir, DOT_GNUPG, "gpg.conf");
        if (gpgVersionGreater(resultGpgVersion.getValue(), "2.1")) {
            logger.info("gpg version greater than 2.1");
            try {
                Files.createDirectories(dotGnupg);
            } catch (final FileAlreadyExistsException ex) {
                // ignored
            } catch (final IOException ex) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("%s%n%s", ex.getMessage(), stackTrace(ex)));
                }
            }
            try {
                Files.setPosixFilePermissions(dotGnupg, PosixFilePermissions.fromString("rwx------"));
            } catch (final IOException ex) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("%s%n%s", ex.getMessage(), stackTrace(ex)));
                }
            }
            logger.info("add 'use-agent' to '~/.gnupg/gpg.conf'");
            writeFile(dotGnupgGpgConf, "use-agent\n".getBytes(UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING);
            if (logger.isInfoEnabled()) {
                logger.info(readFile(dotGnupgGpgConf, UTF_8).orElse(""));
            }

            if (gpgVersionGreater(resultGpgVersion.getValue(), "2.2")) {
                // on gpg-2.1.11 'pinentry-mode loopback' is invalid option
                logger.info("add 'pinentry-mode loopback' to '~/.gnupg/gpg.conf'");
                writeFile(dotGnupgGpgConf, "pinentry-mode loopback\n".getBytes(UTF_8),
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                if (logger.isInfoEnabled()) {
                    logger.info(readFile(dotGnupgGpgConf, UTF_8).orElse(""));
                }
            }
            // gpg_cmd="${gpg_cmd} --pinentry-mode loopback"
            // export GPG_OPTS='--pinentry-mode loopback'
            // echo GPG_OPTS: ${GPG_OPTS}

            logger.info("add 'allow-loopback-pinentry' to '~/.gnupg/gpg-agent.conf'");
            final Path dotGnupgGpgAgentConf = Paths.get(homeDir, DOT_GNUPG, "gpg-agent.conf");
            writeFile(dotGnupgGpgAgentConf, "allow-loopback-pinentry\n".getBytes(UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING);
            if (logger.isInfoEnabled()) {
                logger.info(readFile(dotGnupgGpgAgentConf, UTF_8).orElse(""));
            }

            logger.info("restart the agent");
            this.exec("RELOADAGENT", singletonList("gpg-connect-agent"));
        }
    }

    public void decryptKey() {
        final Entry<Integer, String> opensslVersion = this.exec(Arrays.asList("openssl", "version", "-a"));
        logger.info(opensslVersion.getValue());

        if (exists(Paths.get(this.workingDir, CODESIGNING_ASC_ENC)) && this.passphrase != null) {
            logger.info("decrypt private key");
            // bad decrypt
            // 140611360391616:error:06065064:digital envelope routines:EVP_DecryptFinal_ex:bad decrypt:../crypto/evp/evp_enc.c:536:
            // see: https://stackoverflow.com/questions/34304570/how-to-resolve-the-evp-decryptfinal-ex-bad-decrypt-during-file-decryption
            // openssl aes-256-cbc -k ${CI_OPT_GPG_PASSPHRASE} -in codesigning.asc.enc -out codesigning.asc -d -md md5
            final Entry<Integer, String> resultOpensslDecrypt = this.exec(Arrays.asList(
                "openssl", "aes-256-cbc",
                "-k", this.passphrase,
                "-in", CODESIGNING_ASC_ENC,
                "-out", CODESIGNING_ASC,
                "-d", "-md", "md5"));
            logger.info(resultOpensslDecrypt.getValue());
        }

        if (exists(Paths.get(this.workingDir, CODESIGNING_ASC_GPG)) && this.passphrase != null) {
            logger.info("decrypt private key");
            // LC_CTYPE="UTF-8" echo ${CI_OPT_GPG_PASSPHRASE}
            //   | ${gpg_cmd_batch_yes} --passphrase-fd 0 --cipher-algo AES256 -o codesigning.asc codesigning.asc.gpg
            final List<String> gpgDecrypt = this.gpgBatchYes(
                "--passphrase-fd", "0",
                "--cipher-algo", "AES256",
                "-o", CODESIGNING_ASC,
                CODESIGNING_ASC_GPG);
            final Entry<Integer, String> resultGpgDecrypt = this.exec(this.passphrase, gpgDecrypt);
            logger.info(resultGpgDecrypt.getValue());
        }
    }

    public void importPublicKeys() {
        if (exists(Paths.get(this.workingDir, CODESIGNING_PUB))) {
            logger.info("import public keys");
            // ${gpg_cmd_batch_yes} --import codesigning.pub
            final Entry<Integer, String> resultImportKey = this.exec(null, this.gpgBatchYes("--import", CODESIGNING_PUB));
            logger.info(resultImportKey.getValue());

            logger.info("list public keys");
            // ${gpg_cmd_batch} --list-keys
            final Entry<Integer, String> gpgListKeys = this.exec(null, this.gpgBatch("--list-keys"));
            logger.info(gpgListKeys.getValue());
        }
    }

    public void importPrivateKeys() {
        if (exists(Paths.get(this.workingDir, CODESIGNING_ASC))) {
            logger.info("import private keys");
            // some versions only can import public key from a keypair file, some can import key pair
            if (exists(Paths.get(this.workingDir, CODESIGNING_PUB))) {
                // ${gpg_cmd_batch_yes} --import codesigning.asc
                final List<String> importKey = this.gpgBatchYes("--import", CODESIGNING_ASC);
                final Entry<Integer, String> resultImportKey = this.exec(null, importKey);
                logger.info(resultImportKey.getValue());
            } else {
                // $(${gpg_cmd} --list-secret-keys | { grep ${CI_OPT_GPG_KEYNAME} || true; }
                final Entry<Integer, String> resultListSecKeys = this.exec(null, this.gpgBatch("--list-secret-keys"));
                final List<String> secretKeyFound = lines(resultListSecKeys.getValue())
                    .stream()
                    .filter(line -> line.contains(this.keyName))
                    .collect(Collectors.toList());
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("find '%s'in '%s'. '%s' found", this.keyName, resultListSecKeys.getValue(), secretKeyFound));
                }

                if (secretKeyFound.isEmpty()) {
                    // ${gpg_cmd_batch_yes} --fast-import codesigning.asc
                    final List<String> fastImportKey = this.gpgBatchYes("--fast-import", CODESIGNING_ASC);
                    final Entry<Integer, String> resultFastImportPrivateKey = this.exec(null, fastImportKey);
                    logger.info(resultFastImportPrivateKey.getValue());
                }

                logger.info("list private keys");
                // ${gpg_cmd_batch} --list-secret-keys
                logger.info(this.exec(null, this.gpgBatch("--list-secret-keys")).getValue());

                //   issue: You need a passphrase to unlock the secret key
                //   no-tty causes "gpg: Sorry, no terminal at all requested - can't get input"
                // echo 'no-tty' >> ~/.gnupg/gpg.conf
                // echo 'default-cache-ttl 600' > ~/.gnupg/gpg-agent.conf
                //
                //   test key
                //   this test not working on appveyor
                //   gpg: skipped "KEYID": secret key not available
                //   gpg: signing failed: secret key not available
                // if [[ -f LICENSE ]]; then
                //     echo test private key imported
                //     echo ${CI_OPT_GPG_PASSPHRASE} | gpg --passphrase-fd 0 --yes --batch=true -u ${CI_OPT_GPG_KEYNAME} --armor --detach-sig LICENSE
                // fi

                logger.info("set default key");
                // echo -e "trust\n5\ny\n" | gpg --cmd-fd 0 --batch=true --edit-key ${CI_OPT_GPG_KEYNAME}
                final List<String> setDefaultKey = this.gpgBatch("--cmd-fd", "0", "--edit-key", this.keyName);
                final Entry<Integer, String> resultSetDefaultKey = this.exec("", setDefaultKey);
                logger.info(resultSetDefaultKey.getValue());

                if (this.keyId != null) {
                    logger.info("export secret key for gradle build");
                    // ${gpg_cmd_batch} --keyring secring.gpg --export-secret-key ${CI_OPT_GPG_KEYID} > secring.gpg;
                    final List<String> exportKey = this.gpgBatch("--keyring", "secring.gpg", "--export-secret-key", this.keyId);
                    final Entry<Integer, String> resultExportKey = this.exec("", exportKey);
                    if (resultExportKey.getKey() == 0) {
                        final Path secringGpg = Paths.get(this.workingDir, "secring.gpg");
                        writeFile(secringGpg, resultExportKey.getValue().getBytes(UTF_8), StandardOpenOption.SYNC);
                    }
                }
            }
        }
    }

    static boolean gpgVersionGreater(
        final String gpgVersionInfo,
        final String thanVersion
    ) {
        // e.g. "gpg (GnuPG) 2.2.14\nlibgcrypt 1.8.4\nCopyright ( ... pressed, ZIP, ZLIB, BZIP2"
        final Matcher matcher = Pattern
            .compile("[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+).*", DOTALL | MULTILINE)
            .matcher(gpgVersionInfo);

        final boolean result;
        if (matcher.matches()) {
            final String versionValue = matcher.group(1);
            result = new DefaultArtifactVersion(versionValue).compareTo(new DefaultArtifactVersion(thanVersion)) > 0;
        } else {
            result = false;
        }
        return result;
    }

    private List<String> gpgBatchYes(final String... command) {
        return SupportFunction.asList(concat(this.cmd, "--batch=true", "--yes"), command);
    }

    private List<String> gpgBatch(final String... command) {
        return SupportFunction.asList(concat(this.cmd, "--batch=true"), command);
    }

    private Map.Entry<Integer, String> exec(final List<String> command) {
        return this.exec(null, command);
    }

    private Map.Entry<Integer, String> exec(final String stdIn, final List<String> command) {
        return SupportFunction.exec(this.environment, stdIn, command);
    }
}
