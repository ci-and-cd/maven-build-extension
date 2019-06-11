package top.infra.maven.extension.mavenbuild;

import static java.util.Collections.singletonList;
import static top.infra.maven.extension.mavenbuild.SupportFunction.isEmpty;
import static top.infra.maven.extension.mavenbuild.SupportFunction.pathname;
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
        final Logger logger,
        final File pomFile
    ) {
        final MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
        try {
            final Model model = xpp3Reader.read(new FileReader(pomFile));
            return Optional.of(new MavenProjectInfo(model.getArtifactId(), getGroupId(model), getVersion(model)));
        } catch (final IllegalArgumentException | IOException | XmlPullParserException ex) {
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("Failed to read project info from pomFile [%s] (by MavenXpp3Reader)", pathname(pomFile)), ex);
            }
            return Optional.empty();
        }
    }

    private static String getGroupId(final Model model) {
        final String result;
        if (model.getGroupId() != null) {
            result = model.getGroupId();
        } else {
            result = model.getParent() != null ? model.getParent().getGroupId() : null;
        }
        return result;
    }

    private static String getVersion(final Model model) {
        final String result;
        if (model.getVersion() != null) {
            result = model.getVersion();
        } else {
            result = model.getParent() != null ? model.getParent().getVersion() : null;
        }
        return result;
    }

    public static MavenProjectInfo newProjectInfoByBuildProject(
        final Logger logger,
        final ProjectBuilder projectBuilder,
        final File pomFile,
        final ProjectBuildingRequest projectBuildingRequest
    ) {
        // TODO FIXME set goals
        final Optional<MavenProject> projectOptional = buildProject(logger, pomFile, projectBuilder, projectBuildingRequest);
        final String artifactId = projectOptional.map(MavenProject::getArtifactId).orElse(null);
        final String groupId = projectOptional.map(MavenProject::getGroupId).orElse(null);
        final String version = projectOptional.map(MavenProject::getVersion).orElse(null);
        return new MavenProjectInfo(artifactId, groupId, version);
    }

    public static Optional<MavenProject> buildProject(
        final Logger logger,
        final File pomFile,
        final ProjectBuilder projectBuilder,
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
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("Error get project from pom %s. message: %s, stackTrace: %s",
                    pomFile.getPath(), ex.getMessage(), stackTrace(ex)));
            }
            result = Optional.empty();
        }
        return result;
    }
}
