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
./mvnw clean install verify
```
