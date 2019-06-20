package top.infra.maven.extension.mavenbuild;

import static java.lang.Boolean.FALSE;
import static top.infra.maven.extension.mavenbuild.CiOption.INFRASTRUCTURE;
import static top.infra.maven.extension.mavenbuild.utils.PropertiesUtils.toProperties;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.Logger;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;

@Component(role = CustomActivator.class, hint = "InfrastructureActivator")
public class InfrastructureActivator extends AbstractCustomActivator {

    private static final Pattern PATTERN_INFRASTRUCTURE_PROFILE = Pattern.compile(".*infrastructure_(\\w+)[-]?.*");

    private final GitProperties gitProperties;

    private CiOptionAccessor ciOpts;

    @Inject
    public InfrastructureActivator(
        final Logger logger,
        final ProjectBuilderActivatorModelResolver resolver,
        final GitPropertiesBean gitProperties
    ) {
        super(logger, resolver);
        this.gitProperties = gitProperties;
    }

    @Override
    protected boolean cacheResult() {
        return true;
    }

    @Override
    protected String getName() {
        return "InfrastructureActivator";
    }

    @Override
    protected boolean isActive(
        final Model model,
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
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
                logger.debug(String.format("check profile [%s] against infrastructure [%s]",
                    profile.getId(), profileInfrastructure.orElse(null)));
            }

            final Optional<String> infrastructure = this.ciOpts.getOption(INFRASTRUCTURE);

            result = infrastructure
                .map(value -> value.equals(profileInfrastructure.orElse(null)))
                .orElse(FALSE);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("profile [%s] is not infrastructure related", profile.getId()));
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
