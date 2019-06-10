package top.infra.maven.extension.mavenbuild;

import static java.util.Collections.singletonList;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.stackTrace;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class MavenProjectInfo {

    private final String artifactId;
    private final String groupId;
    private final String version;

    public MavenProjectInfo(
        final String artifactId,
        final String groupId,
        final String version
    ) {
        this.artifactId = artifactId;
        this.groupId = groupId;
        this.version = version;

        if (isEmpty(artifactId) || isEmpty(groupId) || isEmpty(version)) {
            throw new IllegalArgumentException(this.toString());
        }
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return String.format("groupId:artifactId:version %s:%s:%s", this.groupId, this.artifactId, this.version);
    }

    public static Optional<MavenProjectInfo> newProjectInfoByReadPom(
        final File pomFile
    ) {
        final MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
        try {
            final Model model = xpp3Reader.read(new FileReader(pomFile));
            return Optional.of(new MavenProjectInfo(model.getArtifactId(), model.getGroupId(), model.getVersion()));
        } catch (final IllegalArgumentException | IOException | XmlPullParserException ex) {
            return Optional.empty();
        }
    }

    public static MavenProjectInfo newProjectInfoByBuildProject(
        final Logger logger,
        final ProjectBuilder projectBuilder,
        final File pomFile,
        final ProjectBuildingRequest projectBuildingRequest
    ) {
        final Optional<MavenProject> projectOptional = buildProject(logger, projectBuilder, pomFile, projectBuildingRequest);
        final String artifactId = projectOptional.map(MavenProject::getArtifactId).orElse(null);
        final String groupId = projectOptional.map(MavenProject::getGroupId).orElse(null);
        final String version = projectOptional.map(MavenProject::getVersion).orElse(null);
        return new MavenProjectInfo(artifactId, groupId, version);
    }

    public static Optional<MavenProject> buildProject(
        final Logger logger,
        final ProjectBuilder projectBuilder,
        final File pomFile,
        final ProjectBuildingRequest projectBuildingRequest
    ) {
        Optional<MavenProject> result;
        try {
            final ProjectBuildingRequest request = new DefaultProjectBuildingRequest(projectBuildingRequest);
            request.setActiveProfileIds(Collections.emptyList());
            request.setProcessPlugins(false);
            request.setProfiles(Collections.emptyList());
            request.setResolveDependencies(false);
            request.setValidationLevel(0);

            final List<ProjectBuildingResult> buildingResults = projectBuilder.build(
                singletonList(pomFile), false, request);

            result = Optional.of(buildingResults.get(0).getProject());
        } catch (final Exception ex) {
            logger.warn(String.format("Error get project from pom %s. message: %s, stackTrace: %s",
                pomFile.getPath(), ex.getMessage(), stackTrace(ex)));
            result = Optional.empty();
        }
        return result;
    }
}
