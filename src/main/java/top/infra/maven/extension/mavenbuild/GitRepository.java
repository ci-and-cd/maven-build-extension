package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_MASTER;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_SECURITY_XML;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_XML;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.os;
import static top.infra.maven.extension.mavenbuild.SupportFunction.readFile;
import static top.infra.maven.extension.mavenbuild.SupportFunction.writeFile;

import java.io.File;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Pattern;

import org.json.JSONObject;

public class GitRepository {

    private static final Pattern PATTERN_GITLAB_URL = Pattern.compile("^.+/api/v4/projects/[0-9]+/repository/.+$");

    private final Logger logger;

    private final String repo;
    private final String repoRef;
    private final String token;

    public GitRepository(
        final Logger logger,
        final String repo,
        final String repoRef,
        final String token
    ) {
        this.logger = logger;

        this.repo = repo;
        this.repoRef = repoRef != null ? repoRef : GIT_REF_NAME_MASTER;
        this.token = token;
    }

    public void downloadMavenSettingsFile(final String homeDir, final String settingsXml) {
        // settings.xml
        logger.info(">>>>>>>>>> ---------- run_mvn settings.xml and settings-security.xml ---------- >>>>>>>>>>");
        if (isNotEmpty(settingsXml)) {
            if (!new File(settingsXml).exists()) {
                final Entry<Optional<String>, Optional<Integer>> result = this.download(SRC_MAVEN_SETTINGS_XML, settingsXml);
                if (!result.getValue().map(SupportFunction::is2xxStatus).orElse(FALSE)) { // TODO ignore 404
                    final String errorMsg = String.format("Can not download from [%s], to [%s], status [%s].",
                        result.getKey().orElse(null), settingsXml, result.getValue().orElse(null));
                    logger.error(errorMsg);
                    throw new RuntimeException(errorMsg);
                }
            }
        }

        // settings-security.xml
        final Entry<Optional<String>, Optional<Integer>> result = this.download(
            SRC_MAVEN_SETTINGS_SECURITY_XML, settingsSecurityXml(homeDir));
        if (!result.getValue().map(SupportFunction::is2xxStatus).orElse(FALSE)) {
            logger.warn(String.format("settings-security.xml not found or error on download from [%s], to [%s], status [%s].",
                result.getKey().orElse(null), settingsSecurityXml(homeDir), result.getValue().orElse(null)));
        }
        logger.info("<<<<<<<<<< ---------- run_mvn settings.xml and settings-security.xml ---------- <<<<<<<<<<");
    }

    public void downloadMavenToolchainFile(final String homeDir) {
        // toolchains.xml
        logger.info(">>>>>>>>>> ---------- run_mvn toolchains.xml ---------- >>>>>>>>>>");
        final String os = os();
        final String toolchainsSource = "generic".equals(os) ? "src/main/maven/toolchains.xml" : "src/main/maven/toolchains-" + os + ".xml";
        final String toolchainsTarget = homeDir + "/.m2/toolchains.xml";
        final Entry<Optional<String>, Optional<Integer>> result = this.download(toolchainsSource, toolchainsTarget);
        if (!result.getValue().map(SupportFunction::is2xxStatus).orElse(FALSE)) {
            final String errorMsg = String.format("Can not download from [%s], to [%s], status [%s].",
                result.getKey().orElse(null), toolchainsTarget, result.getValue().orElse(null));
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        logger.info("<<<<<<<<<< ---------- run_mvn toolchains.xml ---------- <<<<<<<<<<");
    }

    public Entry<Optional<String>, Optional<Integer>> download(final String sourceFile, final String targetFile) {
        final String fromUrl;
        final Optional<Integer> status;

        if (isNotEmpty(this.repo)) {
            final Map<String, String> headers = new LinkedHashMap<>();
            if (isNotEmpty(this.token)) {
                headers.put("PRIVATE-TOKEN", this.token);
            }

            final Entry<Integer, Exception> statusOrException;
            final boolean hasError;
            final boolean is2xxStatus;

            if (PATTERN_GITLAB_URL.matcher(this.repo).matches()) {
                fromUrl = this.repo + sourceFile.replaceAll("/", "%2F") + "?ref=" + this.repoRef;
                final String saveToFile = targetFile + ".json";
                statusOrException = SupportFunction.download(logger, fromUrl, saveToFile, headers, 3);
                hasError = statusOrException.getValue() != null;
                status = Optional.ofNullable(statusOrException.getKey());

                is2xxStatus = status.map(SupportFunction::is2xxStatus).orElse(FALSE);
                if (is2xxStatus) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("decode %s", saveToFile));
                    }
                    // cat "${target_file}.json" | jq -r ".content" | base64 --decode | tee "${target_file}"
                    final JSONObject jsonFile = new JSONObject(readFile(Paths.get(saveToFile), UTF_8));
                    final String content = jsonFile.getString("content");
                    if (!isEmpty(content)) {
                        writeFile(Paths.get(targetFile), Base64.getDecoder().decode(content), StandardOpenOption.SYNC);
                    }
                }
            } else {
                fromUrl = this.repo + "/raw/" + this.repoRef + "/" + sourceFile;
                statusOrException = SupportFunction.download(logger, fromUrl, targetFile, headers, 3);
                hasError = statusOrException.getValue() != null;
                status = Optional.ofNullable(statusOrException.getKey());
            }

            if (hasError) {
                if (statusOrException.getKey() == null) {
                    logger.warn(String.format("Error download %s.", targetFile), statusOrException.getValue());
                } else {
                    if (logger.isWarnEnabled()) {
                        logger.warn(String.format("Can not download %s.", targetFile));
                        logger.warn(String.format(
                            "Please make sure: 1. Resource exists 2. You have permission to access resources and %s is set.",
                            GIT_AUTH_TOKEN.getEnvVariableName())
                        );
                    }
                }
            }
        } else {
            fromUrl = null;
            status = Optional.empty();
        }

        return new AbstractMap.SimpleImmutableEntry<>(Optional.ofNullable(fromUrl), status);
    }

    public static String settingsSecurityXml(final String homeDir) {
        return homeDir + "/.m2/settings-security.xml";
    }
}
