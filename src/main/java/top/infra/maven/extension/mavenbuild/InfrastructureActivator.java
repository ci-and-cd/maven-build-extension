package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileId;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectName;
import static top.infra.maven.extension.mavenbuild.SupportFunction.toProperties;

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

@Component(role = CustomActivator.class, hint = "InfrastructureActivator")
public class InfrastructureActivator implements ProfileActivator, CustomActivator {

    private static final Pattern PATTERN_INFRASTRUCTURE_PROFILE = Pattern.compile(".*infrastructure_(\\w+)[-]?.*");

    protected final Logger logger;

    private final GitProperties gitProperties;

    private final Map<String, Boolean> profileMemento;

    private final ActivatorModelResolver resolver;

    private CiOptionAccessor ciOpts;

    @Inject
    public InfrastructureActivator(
        final org.codehaus.plexus.logging.Logger logger,
        final GitPropertiesBean gitProperties,
        final ProjectBuilderActivatorModelResolver resolver
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.gitProperties = gitProperties;
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
                    logger.debug(String.format("InfrastructureActivator profile '%s' not presentInConfig", profileId(profile)));
                }

                result = false;
            } else {
                // Required project.
                final Optional<Model> project = this.resolver.resolveModel(profile, context);
                if (project.isPresent()) {
                    result = this.isActiveByProfileName(profile, context);
                } else {
                    // reportProblem("Failed to resolve model", new Exception("Invalid Project"), profile, context, problems);
                    result = false;
                }

                this.profileMemento.put(profile.toString(), result);
            }

            if (result && logger.isInfoEnabled()) {
                logger.info(String.format("InfrastructureActivator project='%s' profile='%s' result='true'",
                    projectName(context), profileId(profile)));
            } else if (!result && logger.isDebugEnabled()) {
                logger.debug(String.format("InfrastructureActivator project='%s' profile='%s' result='false'",
                    projectName(context), profileId(profile)));
            }
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
        final ProfileActivationContext context
    ) {
        if (this.ciOpts == null) {
            this.ciOpts = new CiOptionAccessor(
                logger,
                this.gitProperties,
                toProperties(context.getSystemProperties()),
                toProperties(context.getUserProperties())
            );
        }

        final boolean result;
        if (this.supported(profile)) {
            final Optional<String> profileInfrastructure = profileInfrastructure(profile.getId());
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("found infrastructure %s related profile", profileInfrastructure.orElse(null)));
            }

            final Optional<String> infrastructure = this.ciOpts.getOption(INFRASTRUCTURE);

            result = infrastructure
                .map(value -> value.equals(profileInfrastructure.orElse(null)))
                .orElse(FALSE);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("not infrastructure related profile");
            }

            result = false;
        }

        return result;
    }

    @Override
    public boolean supported(final Profile profile) {
        return profileInfrastructure(profile.getId()).isPresent();
    }

    static Optional<String> profileInfrastructure(final String id) {
        final Optional<String> result;

        final Matcher matcher = PATTERN_INFRASTRUCTURE_PROFILE.matcher(id);
        if (matcher.matches()) {
            result = Optional.of(matcher.group(1));
        } else {
            result = Optional.empty();
        }

        return result;
    }
}
