package top.infra.maven.extension.mavenbuild.multiinfra;

import static org.eclipse.jgit.lib.Repository.shortenRefName;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevWalkUtils;

import top.infra.maven.core.GitProperties;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class GitPropertiesBean extends GitProperties {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    private static final String GIT_BRANCH = "git.branch";
    private static final String GIT_BRANCH_FULL = "git.branch.full";
    private static final String GIT_REF_NAME_FULL = "git.ref.name.full";
    private static final String GIT_PROPERTIES_LOG_FORMAT = "GitProperties %s='%s'";

    @Inject
    public GitPropertiesBean(final org.codehaus.plexus.logging.Logger logger) {
        this(new LoggerPlexusImpl(logger));
    }

    private GitPropertiesBean(final Logger logger) {
        // newJgitProperties(logger).map(properties -> newGitProperties(properties)).orElseGet(() -> newBlankGitProperties())
        super(newJgitProperties(logger).orElse(new Properties()));
    }

    public static Optional<Properties> newJgitProperties(final Logger logger) {
        Optional<Properties> result;
        try {
            // final Repository repository = new FileRepositoryBuilder()
            //     .setWorkTree(new File("."))
            //     .readEnvironment()
            //     .findGitDir()
            //     .setMustExist(true)
            //     .build();

            final Repository repository = new RepositoryBuilder()
                .setWorkTree(new File("."))
                .readEnvironment()
                .findGitDir()
                .setMustExist(true)
                .build();

            logger.debug("Using git repository: " + repository.getDirectory());

            final ObjectId head = repository.resolve("HEAD");
            if (head == null) {
                logger.warn("No such revision: HEAD");
                // throw new IllegalStateException("No such revision: HEAD");
                result = Optional.empty();
            } else {
                final Map<String, String> map = new LinkedHashMap<>();

                final String branch = repository.getBranch();
                if (logger.isInfoEnabled()) {
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_BRANCH, nullToEmpty(branch)));
                }
                map.put(GIT_BRANCH, nullToEmpty(branch));

                final String fullBranch = repository.getFullBranch();
                if (logger.isInfoEnabled()) {
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_BRANCH_FULL, fullBranch));
                }
                map.put(GIT_BRANCH_FULL, nullToEmpty(fullBranch));

                // `git symbolic-ref -q --short HEAD || git describe --tags --exact-match`
                final String refName;
                final String refNameFull;
                if (fullBranch != null) {
                    final Ref fullBranchRef = repository.exactRef(fullBranch);
                    if (fullBranchRef != null) {
                        refNameFull = fullBranchRef.getName();
                        refName = shortenRefName(refNameFull);
                    } else {
                        refNameFull = "";
                        refName = "";
                    }
                } else {
                    final Optional<String> tag = findTag(repository, head);
                    if (tag.isPresent()) {
                        refNameFull = tag.get();
                        refName = shortenRefName(tag.get());
                    } else {
                        refNameFull = "";
                        refName = "";
                    }
                }
                if (logger.isInfoEnabled()) {
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_REF_NAME, refName));
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_REF_NAME_FULL, refNameFull));
                }
                map.put(GIT_REF_NAME_FULL, refNameFull);
                map.put(GIT_REF_NAME, refName);

                final String commitId = head.name();
                if (logger.isInfoEnabled()) {
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_COMMIT_ID, commitId));
                }
                map.put(GIT_COMMIT_ID, commitId);

                try (final ObjectReader objectReader = repository.newObjectReader()) {
                    final String commitIdAbbrev = objectReader.abbreviate(head).name();
                    map.put("git.commit.id.abbrev", commitIdAbbrev);
                }

                final RevWalk walk = new RevWalk(repository);
                walk.setRetainBody(false);
                final RevCommit headCommit = walk.parseCommit(head);
                final int count = RevWalkUtils.count(walk, headCommit, null);
                map.put("git.count", Integer.toString(count));

                final String color = commitId.substring(0, 6);
                map.put("git.commit.color.value", color);
                map.put("git.build.datetime.simple", getFormattedDate());

                final String remoteOriginUrl = repository.getConfig().getString("remote", "origin", "url");
                if (logger.isInfoEnabled()) {
                    logger.info(String.format(GIT_PROPERTIES_LOG_FORMAT, GIT_REMOTE_ORIGIN_URL, remoteOriginUrl));
                }
                map.put(GIT_REMOTE_ORIGIN_URL, remoteOriginUrl);

                final Properties properties = new Properties();
                properties.putAll(map);
                result = Optional.of(properties);
            }
        } catch (final IOException ex) {
            logger.warn("Exception on newGitProperties.", ex);
            result = Optional.empty();
        }

        return result;
    }

    private static Optional<String> findTag(final Repository repository, final ObjectId head) {
        Optional<String> result;
        try {
            final List<Ref> tagList = Git.wrap(repository).tagList().call();
            final List<String> tagFound = new LinkedList<>();
            for (final Ref tag : tagList) {
                if (tag.getObjectId().equals(head)) {
                    tagFound.add(tag.getName());
                }
            }
            // tagFound.add(Git.wrap(repository).describe().setTags(true).setTarget(head).call());
            result = Optional.ofNullable(!tagFound.isEmpty() ? tagFound.get(0) : null);
        } catch (final GitAPIException ex) {
            result = Optional.empty();
        }
        return result;
    }

    private static String nullToEmpty(final String str) {
        return (str == null ? "" : str);
    }

    private static String getFormattedDate() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }
}
