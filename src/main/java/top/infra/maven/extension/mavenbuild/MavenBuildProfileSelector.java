package top.infra.maven.extension.mavenbuild;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.model.Activation;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

/**
 * Profile selector which combines profiles activated by custom and default
 * activators. Overrides "default" provider.
 */
@Component(
    role = ProfileSelector.class,
    hint = "default"
)
public class MavenBuildProfileSelector extends DefaultProfileSelector {

    @Requirement
    protected Logger logger;

    /**
     * Collect only custom activators.
     * Note: keep field name different from super.
     */
    @Requirement(role = CustomActivator.class)
    protected List<CustomActivator> customActivators = new ArrayList<>();

    /**
     * Profiles activated by custom and default activators.
     */
    @Override
    public List<Profile> getActiveProfiles(
        final Collection<Profile> profiles,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final List<Profile> defaultActiveAll = super.getActiveProfiles(profiles, context, problems);

        final Set<String> defaultActiveSupported = supportedProfiles(defaultActiveAll, this.customActivators).stream()
            .map(Profile::toString)
            .collect(Collectors.toSet());

        final List<Profile> defaultActiveNotSupported = defaultActiveAll.stream()
            .filter(profile -> !defaultActiveSupported.contains(profile.toString()))
            .collect(Collectors.toList());

        final List<Profile> customActive = supportedProfiles(profiles, this.customActivators).stream()
            .filter(profile -> defaultActiveSupported.contains(profile.toString()) || noAnyCondition(profile))
            .filter(profile -> this.customActivators.stream().anyMatch(activator -> activator.isActive(profile, context, problems)))
            .collect(Collectors.toList());

        final ArrayList<Profile> activeProfiles = new ArrayList<>();
        activeProfiles.addAll(defaultActiveNotSupported);
        activeProfiles.addAll(customActive);

        if (!activeProfiles.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("SELECT: %s", Arrays.toString(activeProfiles.toArray())));
            }
        }

        return activeProfiles;
    }

    static boolean noAnyCondition(final Profile profile) {
        final Activation activation = profile.getActivation();
        return activation == null
            || (activation.getFile() == null
            && activation.getJdk() == null
            && activation.getOs() == null
            && activation.getProperty() == null);
    }

    static List<Profile> supportedProfiles(final Collection<Profile> profiles, final Collection<CustomActivator> customActivators) {
        return profiles.stream()
            .filter(profile -> customActivators.stream().anyMatch(activator -> activator.supported(profile)))
            .collect(Collectors.toList());
    }

    protected boolean superIsActive(
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        try {
            final Method superIsActive = super.getClass().getDeclaredMethod(
                "isActive", Profile.class, ProfileActivationContext.class, ModelProblemCollector.class);
            superIsActive.setAccessible(true);
            return (boolean) superIsActive.invoke(this, profile, context, problems);
        } catch (final NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            return false;
        }
    }
}
