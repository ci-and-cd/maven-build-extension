package top.infra.maven.extension.mavenbuild;

public final class Constants {

    public static final String BOOL_STRING_FALSE = "false";
    public static final String BOOL_STRING_TRUE = "true";

    static final String BRANCH_PREFIX_FEATURE = "feature/";
    static final String BRANCH_PREFIX_HOTFIX = "hotfix/";
    static final String BRANCH_PREFIX_RELEASE = "release/";
    static final String BRANCH_PREFIX_SUPPORT = "support/";

    static final String GIT_REF_NAME_DEVELOP = "develop";
    static final String GIT_REF_NAME_MASTER = "master";

    static final String INFRASTRUCTURE_OPENSOURCE = "opensource";
    static final String INFRASTRUCTURE_PRIVATE = "private";

    static final String SRC_CI_OPTS_PROPERTIES = "src/main/ci-script/ci_opts.properties";
    static final String SRC_MAVEN_SETTINGS_XML = "src/main/maven/settings.xml";
    static final String SRC_MAVEN_SETTINGS_SECURITY_XML = "src/main/maven/settings-security.xml";

    static final String PUBLISH_CHANNEL_RELEASE = "release";
    static final String PUBLISH_CHANNEL_SNAPSHOT = "snapshot";

    static final String USER_PROPERTY_SETTINGS_LOCALREPOSITORY = "settings.localRepository";

    private Constants() {
    }
}
