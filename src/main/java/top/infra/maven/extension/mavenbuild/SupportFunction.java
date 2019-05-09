package top.infra.maven.extension.mavenbuild;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.profile.ProfileActivationContext;

public abstract class SupportFunction {

    private SupportFunction() {

    }

    private static final Pattern PATTERN_JAVA_PROFILE = Pattern.compile(".*java[-]?(\\d+)[-]?.*");

    public static boolean isJavaVersionRelatedProfile(final String id) {
        return PATTERN_JAVA_PROFILE.matcher(id).matches();
    }

    public static Optional<Integer> profileJavaVersion(final String id) {
        final Optional<Integer> result;

        final Matcher matcher = PATTERN_JAVA_PROFILE.matcher(id);
        if (matcher.matches()) {
            result = Optional.of(Integer.parseInt(matcher.group(1)));
        } else {
            result = Optional.empty();
        }

        return result;
    }

    public static Optional<Integer> projectJavaVersion(final String javaVersion) {
        final Optional<Integer> result;

        if (javaVersion.matches("1.\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[1]));
        } else if (javaVersion.matches("\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[0]));
        } else {
            result = Optional.empty();
        }

        return result;
    }

    /**
     * Fail-safe pom.xml model reader.
     */
    static Model readMavenModel(File pomFile) {
        try (final FileInputStream fileInput = new FileInputStream(pomFile)) {
            final InputStreamReader fileReader = new InputStreamReader(fileInput, StandardCharsets.UTF_8);

            final MavenXpp3Reader pomReader = new MavenXpp3Reader();
            return pomReader.read(fileReader);
        } catch (Throwable e) {
            return new Model();
        }
    }

    /**
     * Extract optional project name from context.
     */
    static String projectName(ProfileActivationContext context) {
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
     * Provide script execution context variables.
     */
    static Map<String, Object> projectContext(final Model project, final ProfileActivationContext context) {
        // Note: keep order.
        final Map<String, Object> bindings = new LinkedHashMap<>();
        // Inject project props: override defaults
        bindings.putAll(context.getProjectProperties());
        bindings.putAll(mapFromProperties(project.getProperties()));
        // Inject system props, override previous.
        bindings.putAll(context.getSystemProperties());
        // Inject user props, override previous.
        bindings.putAll(context.getUserProperties());
        // Expose default variable context.
        bindings.put("value", bindings);
        // Expose resolved pom.xml model.
        bindings.put("project", project);
        return bindings;
    }

    /**
     * Change properties format.
     */
    static Properties propertiesToMap(final Map<String, String> map) {
        final Properties props = new Properties();
        props.putAll(map);
        return props;
    }

    /**
     * Provide script execution context variables.
     */
    static Map<String, Object> mapFromProperties(final Properties properties) {
        final Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }

    /**
     * Extract null-safe profile identity.
     */
    static String profileId(final Profile profile) {
        return profile == null || profile.getId() == null ? "" : profile.getId();
    }

    /**
     * Extract profile property value.
     */
    static String propertyValue(final Profile profile) {
        return profile.getActivation().getProperty().getValue();
    }

    /**
     * Produce default problem descriptor.
     */
    static ModelProblemCollectorRequest problemRequest() {
        return new ModelProblemCollectorRequest(ModelProblem.Severity.ERROR, ModelProblem.Version.BASE);
    }

    /**
     * Inject new problem in reporter.
     */
    static void registerProblem(
        final String message,
        final Exception error,
        final Profile profile,
        final ModelProblemCollector problems
    ) {
        final ModelProblemCollectorRequest request = problemRequest()
            .setMessage(message)
            .setException(error)
            .setLocation(profile.getLocation(""));
        problems.add(request);
    }
}
