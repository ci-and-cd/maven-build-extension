package top.infra.maven.extension.mavenbuild.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.apache.maven.model.InputLocation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.profile.ProfileActivationContext;

public abstract class MavenUtils {

    /**
     * Report titled activator problem.
     */
    private static void reportProblem(
        final String title,
        final Exception error,
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final String message = String.format("%s: project='%s' profile='%s'", title, projectName(context), profileId(profile));
        registerProblem(message, error, profile.getLocation(""), problems);
    }

    /**
     * Inject new problem in reporter.
     */
    private static void registerProblem(
        final String message,
        final Exception error,
        final InputLocation location,
        final ModelProblemCollector problems
    ) {
        final ModelProblemCollectorRequest request = problemRequest()
            .setMessage(message)
            .setException(error)
            .setLocation(location);
        problems.add(request);
    }

    /**
     * Produce default problem descriptor.
     */
    private static ModelProblemCollectorRequest problemRequest() {
        return new ModelProblemCollectorRequest(ModelProblem.Severity.ERROR, ModelProblem.Version.BASE);
    }

    /**
     * Extract null-safe profile identity.
     */
    public static String profileId(final Profile profile) {
        return profile == null || profile.getId() == null ? "" : profile.getId();
    }

    /**
     * Extract optional project name from context.
     */
    public static String projectName(final ProfileActivationContext context) {
        final String missing = "<missing>";
        final File basedir = context.getProjectDirectory();
        if (basedir == null) {
            return missing;
        }

        final File pomFile = new File(basedir, "pom.xml");
        if (pomFile.exists()) {
            final Model model = readMavenModel(pomFile);
            final String artifactId = model.getArtifactId();
            if (artifactId != null) {
                return artifactId;
            } else {
                return missing;
            }
        } else {
            return basedir.getName();
        }
    }

    /**
     * Fail-safe pom.xml model reader.
     */
    private static Model readMavenModel(File pomFile) {
        try (final FileInputStream fileInput = new FileInputStream(pomFile)) {
            final InputStreamReader fileReader = new InputStreamReader(fileInput, StandardCharsets.UTF_8);

            final MavenXpp3Reader pomReader = new MavenXpp3Reader();
            return pomReader.read(fileReader);
        } catch (final Exception ex) {
            return new Model();
        }
    }

    /**
     * Extract profile property value.
     */
    private static String propertyValue(final Profile profile) {
        return profile.getActivation().getProperty().getValue();
    }
}
