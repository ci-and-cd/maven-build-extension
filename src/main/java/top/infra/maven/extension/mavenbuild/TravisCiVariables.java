package top.infra.maven.extension.mavenbuild;

import java.util.Optional;
import java.util.Properties;

public class TravisCiVariables {

    private final Properties systemProperties;

    public TravisCiVariables(final Properties systemProperties) {
        // if variable TRAVIS present
        this.systemProperties = systemProperties;
    }

    public Optional<String> branch() {
        return this.getEnvironmentVariable("env.TRAVIS_BRANCH");
    }

    public Optional<String> eventType() {
        return this.getEnvironmentVariable("env.TRAVIS_EVENT_TYPE");
    }

    public boolean isPullRequestEvent() {
        return "pull_request".equals(this.eventType().orElse(""));
    }

    public Optional<String> pullRequest() {
        return this.getEnvironmentVariable("env.TRAVIS_PULL_REQUEST");
    }

    public Optional<String> repoSlug() {
        return this.getEnvironmentVariable("env.TRAVIS_REPO_SLUG");
    }

    private Optional<String> getEnvironmentVariable(final String name) {
        return Optional.ofNullable(this.systemProperties.getProperty(name, null));
    }
}
