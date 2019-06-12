# maven-build-extension
Maven extension for github.com/ci-and-cd/maven-build

### Usage

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">

    <extension>
        <groupId>top.infra</groupId>
        <artifactId>maven-build-extension</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </extension>
</extensions>
```


### Build this extension

```bash
CI_OPT_NEXUS3="https://nexus3.infra.top" CI_OPT_SONAR="true" CI_OPT_SONAR_ORGANIZATION="home1-oss-github" ./mvnw -Dgpg.executable=gpg2 -Dgpg.loopback=true -s settings.xml clean install verify

#CI_OPT_GITHUB_SITE_PUBLISH="true" CI_OPT_INFRASTRUCTURE=opensource CI_OPT_OPENSOURCE_GIT_AUTH_TOKEN="${CI_OPT_OPENSOURCE_GIT_AUTH_TOKEN}" CI_OPT_SITE="true" CI_OPT_GITHUB_GLOBAL_REPOSITORYOWNER="ci-and-cd" CI_OPT_SITE_PATH_PREFIX="maven-build-extension" ./mvnw -e -U clean install site site-deploy

#CI_OPT_GITHUB_SITE_PUBLISH="false" CI_OPT_INFRASTRUCTURE=opensource CI_OPT_OPENSOURCE_MVNSITE_PASSWORD="${CI_OPT_OPENSOURCE_MVNSITE_PASSWORD}" CI_OPT_OPENSOURCE_MVNSITE_USERNAME="${CI_OPT_OPENSOURCE_MVNSITE_USERNAME}" CI_OPT_NEXUS3="https://nexus3.infra.top" CI_OPT_SITE="true" CI_OPT_SITE_PATH_PREFIX="ci-and-cd/maven-build-extension" ./mvnw -e -U clean install site site:stage site:stage-deploy

./mvnw dependency:tree
```

### References

[pom-manipulation-ext](https://github.com/release-engineering/pom-manipulation-ext/tree/master/ext/src/main/java/org/commonjava/maven/ext/manip)
[maven-help-plugin](https://github.com/apache/maven-help-plugin/blob/maven-help-plugin-3.2.0)
