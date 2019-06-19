package top.infra.maven.extension.mavenbuild;

import javax.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.logging.Logger;

import top.infra.maven.extension.mavenbuild.model.ProjectBuilderActivatorModelResolver;

@Component(role = CustomActivator.class, hint = "MultiModuleRootActivator")
public class MultiModuleRootActivator extends AbstractCustomActivator {

    private final MavenProjectInfoEventAware projectInfoBean;

    @Inject
    public MultiModuleRootActivator(
        final Logger logger,
        final ProjectBuilderActivatorModelResolver resolver,
        final MavenProjectInfoEventAware projectInfoBean
    ) {
        super(logger, resolver);

        this.projectInfoBean = projectInfoBean;
    }

    @Override
    protected String getName() {
        return "MultiModuleRootActivator";
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
            if (logger.isDebugEnabled()) {
                logger.debug("found multi-module-root profile");
            }

            final MavenProjectInfo rootProjectInfo = this.projectInfoBean.getProjectInfo();

            result = rootProjectInfo != null
                && rootProjectInfo.getGroupId() != null
                && rootProjectInfo.getGroupId().equals(model.getGroupId())
                && rootProjectInfo.getArtifactId() != null
                && rootProjectInfo.getArtifactId().equals(model.getArtifactId());
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("not multi-module-root profile");
            }

            result = false;
        }

        return result;
    }

    @Override
    public boolean supported(final Profile profile) {
        return profile.getId().contains("multi_module_root_only");
    }
}
