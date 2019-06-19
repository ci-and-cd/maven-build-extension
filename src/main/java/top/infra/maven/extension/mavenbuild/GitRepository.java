package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static top.infra.maven.extension.mavenbuild.CiOption.GIT_AUTH_TOKEN;
import static top.infra.maven.extension.mavenbuild.Constants.GIT_REF_NAME_MASTER;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_SECURITY_XML;
import static top.infra.maven.extension.mavenbuild.Constants.SRC_MAVEN_SETTINGS_XML;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.isNotEmpty;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTuple;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTupleOptional;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.os;
import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.readFile;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtil.writeFile;

import java.io.File;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Pattern;

import org.json.JSONObject;

import top.infra.maven.extension.mavenbuild.utils.DownloadUtil;
import top.infra.maven.logging.Logger;

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
        if (isNotEmpty(settingsXml) && !new File(settingsXml).exists()) {
            this.download(SRC_MAVEN_SETTINGS_XML, settingsXml, true);
        }

        // settings-security.xml
        this.download(SRC_MAVEN_SETTINGS_SECURITY_XML, settingsSecurityXml(homeDir), false);
        logger.info("<<<<<<<<<< ---------- run_mvn settings.xml and settings-security.xml ---------- <<<<<<<<<<");
    }

    /**
     * Download sourceFile from git repository.
     * Throws RuntimeException on error.
     *
     * @param sourceFile       relative path in git repository
     * @param targetFile       target local file
     * @param reThrowException re-throw exception
     */
    public void download(
        final String sourceFile,
        final String targetFile,
        final boolean reThrowException
    ) {
        final Entry<Optional<String>, Entry<Optional<Integer>, Optional<Exception>>> result = this.downloadAndDecode(
            sourceFile, targetFile);

        final Optional<Integer> status = result.getValue().getKey();
        final Optional<Exception> error = result.getValue().getValue();
        final boolean is2xxStatus = status.map(DownloadUtil::is2xxStatus).orElse(FALSE);
        final boolean is404Status = status.map(DownloadUtil::is404Status).orElse(FALSE);

        if (error.isPresent() || !is2xxStatus) {
            if (!is404Status) {
                final String errorMsg = String.format(
                    "Download error. From [%s], to [%s], error [%s].",
                    result.getKey().orElse(null),
                    targetFile,
                    error.map(Throwable::getMessage).orElseGet(() -> status.map(Object::toString).orElse(null))
                );
                if (reThrowException) {
                    logger.error(errorMsg);
                    throw new RuntimeException(errorMsg); // TODO fix all new RuntimeException
                } else {
                    logger.warn(errorMsg);
                }
            } else {
                logger.warn(String.format("Resource [%s] not found.", result.getKey().orElse(null)));
            }
        }
    }

    /**
     * Download sourceFile from git repository.
     *
     * @param sourceFile relative path in git repository
     * @param targetFile target local file
     * @return tuple(url, tuple ( status, exception))
     */
    private Entry<Optional<String>, Entry<Optional<Integer>, Optional<Exception>>> downloadAndDecode(
        final String sourceFile,
        final String targetFile
    ) {
        final Entry<Optional<Integer>, Optional<Exception>> statusOrException;

        final String fromUrl;

        if (isNotEmpty(this.repo)) {
            final Map<String, String> headers = new LinkedHashMap<>();
            if (isNotEmpty(this.token)) {
                headers.put("PRIVATE-TOKEN", this.token);
            }

            final Optional<Integer> status;
            if (PATTERN_GITLAB_URL.matcher(this.repo).matches()) {
                fromUrl = this.repo + sourceFile.replaceAll("/", "%2F") + "?ref=" + this.repoRef;
                final String saveToFile = targetFile + ".json";
                statusOrException = DownloadUtil.download(logger, fromUrl, saveToFile, headers, 3);
                status = statusOrException.getKey();

                final boolean is2xxStatus = status.map(DownloadUtil::is2xxStatus).orElse(FALSE);
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
                statusOrException = DownloadUtil.download(logger, fromUrl, targetFile, headers, 3);
                status = statusOrException.getKey();
            }

            final boolean hasError = statusOrException.getValue().isPresent();
            if (hasError) {
                if (status.isPresent()) {
                    logger.warn(String.format("Error download %s.", targetFile), statusOrException.getValue().orElse(null));
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
            statusOrException = newTupleOptional(null, null);
        }

        return newTuple(Optional.ofNullable(fromUrl), statusOrException);
    }

    public static String settingsSecurityXml(final String homeDir) {
        return homeDir + "/.m2/settings-security.xml";
    }

    public void downloadMavenToolchainFile(final String homeDir) {
        // toolchains.xml
        logger.info(">>>>>>>>>> ---------- run_mvn toolchains.xml ---------- >>>>>>>>>>");
        final String os = os();
        final String toolchainsSource = "generic".equals(os) ? "src/main/maven/toolchains.xml" : "src/main/maven/toolchains-" + os + ".xml";
        final String toolchainsTarget = homeDir + "/.m2/toolchains.xml";
        this.download(toolchainsSource, toolchainsTarget, true);
        logger.info("<<<<<<<<<< ---------- run_mvn toolchains.xml ---------- <<<<<<<<<<");
    }
}
