package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.SupportFunction.parseJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileId;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectContext;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectName;

import java.util.Map;
import java.util.Optional;

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

    /**
     * Logger provided by Maven runtime.
     */
    // @org.codehaus.plexus.component.annotations.Requirement
    protected final Logger logger;

    private final ActivatorModelResolver resolver;

    @Inject
    public JavaVersionSensitiveActivator(
        final org.codehaus.plexus.logging.Logger logger,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.resolver = resolver;
    }

    @Override
    public boolean isActive(final Profile profile, final ProfileActivationContext context, final ModelProblemCollector problems) {
        boolean result;
        if (!this.presentInConfig(profile, context, problems)) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("JavaVersionSensitiveActivator profile '%s' not presentInConfig", profileId(profile)));
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

        return result;
    }

    @Override
    public boolean presentInConfig(final Profile profile, final ProfileActivationContext context, final ModelProblemCollector problems) {
        final Optional<Model> model = this.resolver.resolveModel(profile, context);
        return model.isPresent();
    }

    public boolean isJavaVersionSensitive(final Profile profile) {
        return profileJavaVersion(profile.getId()).isPresent();
    }

    protected boolean isActiveByProfileName(
        final Profile profile,
        final Map<String, Object> projectContext
    ) {
        final boolean result;
        if (this.isJavaVersionSensitive(profile)) {
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
}
