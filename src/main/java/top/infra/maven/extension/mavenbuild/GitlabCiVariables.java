package top.infra.maven.extension.mavenbuild;

import java.util.Optional;
import java.util.Properties;

public class GitlabCiVariables {

    public static final String CI_PROJECT_URL = "env.CI_PROJECT_URL";

    private final Properties systemProperties;

    public GitlabCiVariables(final Properties systemProperties) {
        this.systemProperties = systemProperties;
    }

    public Optional<String> ciProjectUrl() {
        return this.getEnvironmentVariable(CI_PROJECT_URL);
    }

    private Optional<String> commitRefName() {
        return this.getEnvironmentVariable("env.CI_COMMIT_REF_NAME");
    }

    public Optional<String> repoSlug() {
        return this.projectPath();
    }

    private Optional<String> projectPath() {
        return this.getEnvironmentVariable("env.CI_PROJECT_PATH");
    }

    public Optional<String> projectUrl() {
        return this.getEnvironmentVariable("env.CI_PROJECT_URL");
    }

    /**
     * Gitlab-ci.
     * <br/>
     * ${CI_REF_NAME} show branch or tag since GitLab-CI 5.2<br/>
     * CI_REF_NAME for gitlab 8.x, see: https://gitlab.com/help/ci/variables/README.md<br/>
     * CI_COMMIT_REF_NAME for gitlab 9.x, see: https://gitlab.com/help/ci/variables/README.md<br/>
     *
     * @return ref name
     */
    public Optional<String> refName() {
        final Optional<String> result;

        final Optional<String> ciRefName = this.getEnvironmentVariable("env.CI_REF_NAME");
        if (ciRefName.isPresent()) {
            result = ciRefName;
        } else {
            result = this.commitRefName();
        }

        return result;
    }

    private Optional<String> getEnvironmentVariable(final String name) {
        return Optional.ofNullable(this.systemProperties.getProperty(name, null));
    }
}
