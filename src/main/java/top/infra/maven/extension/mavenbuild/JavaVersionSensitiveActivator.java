package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.SupportFunction.parseJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileId;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectContext;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectName;

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
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.codehaus.plexus.component.annotations.Component;

import top.infra.maven.extension.mavenbuild.model.ActivatorModelResolver;
import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;

@Component(role = CustomActivator.class, hint = "JavaVersionSensitiveActivator")
public class JavaVersionSensitiveActivator implements ProfileActivator, CustomActivator {

    private static final Pattern PATTERN_JAVA_PROFILE = Pattern.compile(".*java[-]?(\\d+)[-]?.*");

    /**
     * Logger provided by Maven runtime.
     */
    // @org.codehaus.plexus.component.annotations.Requirement
    protected final Logger logger;

    private final Map<String, Boolean> profileMemento;

    private final ActivatorModelResolver resolver;

    @Inject
    public JavaVersionSensitiveActivator(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.profileMemento = new LinkedHashMap<>();
        this.resolver = resolver;
    }

    @Override
    public boolean isActive(
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final Boolean result;

        final Boolean found = this.profileMemento.get(profile.toString());

        if (found == null) {
            if (!this.presentInConfig(profile, context, problems)) {
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("JavaVersionSensitiveActivator profile '%s' not presentInConfig", profileId(profile)));
                }

                result = false;
            } else {
                // Required project.
                final Optional<Model> project = this.resolver.resolveModel(profile, context);
                if (project.isPresent()) {
                    final Map<String, Object> projectContext = projectContext(project.get(), context);
                    result = this.isActiveByProfileName(profile, projectContext);
                } else {
                    // reportProblem("Failed to resolve model", new Exception("Invalid Project"), profile, context, problems);
                    result = false;
                }
            }

            if (result && logger.isInfoEnabled()) {
                logger.info(String.format("JavaVersionSensitiveActivator project='%s' profile='%s' result='true'",
                    projectName(context), profileId(profile)));
            } else if (!result && logger.isDebugEnabled()) {
                logger.debug(String.format("JavaVersionSensitiveActivator project='%s' profile='%s' result='false'",
                    projectName(context), profileId(profile)));
            }

            this.profileMemento.put(profile.toString(), result);
        } else {
            result = found;
        }

        return result;
    }

    @Override
    public boolean presentInConfig(
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final Optional<Model> model = this.resolver.resolveModel(profile, context);
        return model.isPresent();
    }

    protected boolean isActiveByProfileName(
        final Profile profile,
        final Map<String, Object> projectContext
    ) {
        final boolean result;
        if (this.supported(profile)) {
            final Optional<Integer> profileJavaVersion = profileJavaVersion(profile.getId());
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("found java %s related profile", profileJavaVersion.orElse(null)));
            }

            Optional<Integer> javaVersionForce;
            try {
                javaVersionForce = Optional.of(Integer.parseInt("" + projectContext.get("javaVersionForce")));
            } catch (final Exception ex) {
                javaVersionForce = Optional.empty();
            }

            final boolean javaVersionActive;
            if (javaVersionForce.isPresent()) {
                javaVersionActive = javaVersionForce.get().equals(profileJavaVersion.orElse(null));
            } else {
                final Optional<Integer> projectJavaVersion = parseJavaVersion("" + projectContext.get("java.version"));
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

    static boolean isJavaVersionRelatedProfile(final String id) {
        return PATTERN_JAVA_PROFILE.matcher(id).matches();
    }

    static Optional<Integer> profileJavaVersion(final String id) {
        final Optional<Integer> result;

        final Matcher matcher = PATTERN_JAVA_PROFILE.matcher(id);
        if (matcher.matches()) {
            result = Optional.of(Integer.parseInt(matcher.group(1)));
        } else {
            result = Optional.empty();
        }

        return result;
    }
}
