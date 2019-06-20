package top.infra.maven.extension.mavenbuild;

import static java.lang.Integer.parseInt;
import static top.infra.maven.extension.mavenbuild.utils.PropertiesUtils.mapFromProperties;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.parseJavaVersion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.codehaus.plexus.component.annotations.Component;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;

@Component(role = CustomActivator.class, hint = "JavaVersionSensitiveActivator")
public class JavaVersionSensitiveActivator extends AbstractCustomActivator {

    private static final Pattern PATTERN_JAVA_PROFILE = Pattern.compile(".*java[-]?(\\d+)[-]?.*");

    @Inject
    public JavaVersionSensitiveActivator(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        super(logger, resolver);
    }

    static boolean isJavaVersionRelatedProfile(final String id) {
        return PATTERN_JAVA_PROFILE.matcher(id).matches();
    }

    @Override
    protected boolean cacheResult() {
        return true;
    }

    @Override
    protected String getName() {
        return "JavaVersionSensitiveActivator";
    }

    @Override
    protected boolean isActive(
        final Model model,
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final boolean result;
        if (this.supported(profile)) {
            final Optional<Integer> profileJavaVersion = profileJavaVersion(profile.getId());
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("found java %s related profile", profileJavaVersion.orElse(null)));
            }

            final Map<String, Object> projectContext = projectContext(model, context);

            Optional<Integer> javaVersionForce;
            try {
                javaVersionForce = Optional.of(parseInt(String.format("%s", projectContext.get("javaVersionForce"))));
            } catch (final Exception ex) {
                javaVersionForce = Optional.empty();
            }

            final boolean javaVersionActive;
            if (javaVersionForce.isPresent()) {
                javaVersionActive = javaVersionForce.get().equals(profileJavaVersion.orElse(null));
            } else {
                final Optional<Integer> projectJavaVersion = parseJavaVersion(String.format("%s", projectContext.get("java.version")));
                javaVersionActive = projectJavaVersion
                    .map(integer -> integer.equals(profileJavaVersion.orElse(null))).orElse(false);
            }

            result = javaVersionActive;
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("not java related profile");
            }

            result = false;
        }

        return result;
    }

    @Override
    public boolean supported(final Profile profile) {
        return profileJavaVersion(profile.getId()).isPresent();
    }

    static Optional<Integer> profileJavaVersion(final String id) {
        final Optional<Integer> result;

        final Matcher matcher = PATTERN_JAVA_PROFILE.matcher(id);
        if (matcher.matches()) {
            result = Optional.of(parseInt(matcher.group(1)));
        } else {
            result = Optional.empty();
        }

        return result;
    }

    /**
     * Provide script execution context variables.
     */
    private static Map<String, Object> projectContext(
        final Model project,
        final ProfileActivationContext context
    ) {
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
}
