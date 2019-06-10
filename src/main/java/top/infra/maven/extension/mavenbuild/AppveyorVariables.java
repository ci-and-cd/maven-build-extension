package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;

import java.util.Optional;
import java.util.Properties;

public class AppveyorVariables {

    private static final String APPVEYOR_REPO_BRANCH = "env.APPVEYOR_REPO_BRANCH";
    private static final String APPVEYOR_REPO_NAME = "env.APPVEYOR_REPO_NAME";
    private static final String APPVEYOR_REPO_TAG = "env.APPVEYOR_REPO_TAG";
    private static final String APPVEYOR_REPO_TAG_NAME = "env.APPVEYOR_REPO_TAG_NAME";
    public static final String APPVEYOR_PULL_REQUEST_HEAD_REPO_NAME = "env.APPVEYOR_PULL_REQUEST_HEAD_REPO_NAME";

    private final Properties systemProperties;

    public AppveyorVariables(final Properties systemProperties) {
        // if variable APPVEYOR present
        this.systemProperties = systemProperties;
    }

    public boolean isPullRequest() {
        return this.pullRequestHeadRepoName().map(SupportFunction::notEmpty).orElse(FALSE);
    }

    public Optional<String> pullRequestHeadRepoName() {
        return this.getEnvironmentVariable(APPVEYOR_PULL_REQUEST_HEAD_REPO_NAME);
    }

    public Optional<String> refName() {
        return this.repoTagName() ? this.repoTag() : this.repoBranch();
    }

    private Optional<String> repoBranch() {
        return this.getEnvironmentVariable(APPVEYOR_REPO_BRANCH);
    }

    public Optional<String> repoSlug() {
        return this.repoName();
    }

    private Optional<String> repoName() {
        return this.getEnvironmentVariable(APPVEYOR_REPO_NAME);
    }

    private Optional<String> repoTag() {
        return this.getEnvironmentVariable(APPVEYOR_REPO_TAG);
    }

    public boolean repoTagName() {
        return this.getEnvironmentVariable(APPVEYOR_REPO_TAG_NAME).map(Boolean::parseBoolean).orElse(FALSE);
    }

    private Optional<String> getEnvironmentVariable(final String name) {
        return Optional.ofNullable(this.systemProperties.getProperty(name, null));
    }
}
