package top.infra.maven.extension.mavenbuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.profile.activation.ProfileActivator;
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
    protected List<ProfileActivator> customActivators = new ArrayList<>();

    /**
     * Profiles activated by custom and default activators.
     */
    @Override
    public List<Profile> getActiveProfiles(
        final Collection<Profile> profiles,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        final List<Profile> customActiveProfiles = new ArrayList<>(profiles.size());
        for (final Profile profile : profiles) {
            if (this.isActive(profile, context, problems)) {
                customActiveProfiles.add(profile);
            }
        }

        final List<Profile> defaultActiveProfiles = super.getActiveProfiles(profiles, context, problems);

        final ArrayList<Profile> activeProfiles = new ArrayList<>();
        activeProfiles.addAll(customActiveProfiles);
        activeProfiles.addAll(defaultActiveProfiles);

        if (logger.isDebugEnabled() && !activeProfiles.isEmpty()) {
            logger.debug("SELECT: " + Arrays.toString(activeProfiles.toArray()));
        }

        return activeProfiles;
    }

    /**
     * Note: "AND" for custom activators. See super.
     */
    protected boolean isActive(
        final Profile profile,
        final ProfileActivationContext context,
        final ModelProblemCollector problems
    ) {
        boolean isActive = false;

        for (final ProfileActivator customActivator : this.customActivators) {
            if (customActivator.presentInConfig(profile, context, problems)) {
                // "OR"
                isActive = true;
            }
        }

        for (final ProfileActivator customActivator : this.customActivators) {
            if (customActivator.presentInConfig(profile, context, problems)) {
                // "AND"
                isActive &= customActivator.isActive(profile, context, problems);
            }
        }
        
        return isActive;
    }
}
