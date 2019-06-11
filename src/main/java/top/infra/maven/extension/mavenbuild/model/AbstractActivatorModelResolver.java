package top.infra.maven.extension.mavenbuild.model;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.profile.ProfileActivationContext;

public abstract class AbstractActivatorModelResolver implements ActivatorModelResolver {

    protected final org.codehaus.plexus.logging.Logger logger;

    /**
     * Builder provided by Maven runtime.
     */
    // @org.codehaus.plexus.component.annotations.Requirement
    private final ModelBuilder modelBuilder;

    /**
     * Remember processed profile pom.xml file to control recursion.
     */
    private final Map<String, Model> profileMemento;

    protected AbstractActivatorModelResolver(
        final org.codehaus.plexus.logging.Logger logger,
        final ModelBuilder modelBuilder
    ) {
        this.logger = logger;
        this.modelBuilder = modelBuilder;
        this.profileMemento = new LinkedHashMap<>();
    }

    /**
     * <p>
     * Resolve project pom.xml model: interpolate properties and fields.
     * </p>
     * Note: invokes recursive call back to this instance.
     * Control recursion by processing projects only once via {@link #profileMemento}.
     */
    @Override
    public Model resolveModel(final Profile profile, final ProfileActivationContext context) {
        final File pomFile = this.projectPOM(context);

        if ("source".equals(profile.getSource())) {
            // logger.debug(String.format("profile %s is from settings not pom", profile));
            this.registerMemento(profile, pomFile, null);
            return null;
        }

        if (profile.getBuild() == null) {
            // logger.debug(String.format("profile %s has a null build", profile));
            this.registerMemento(profile, pomFile, null);
            return null;
        }

        if (pomFile == null) {
            // logger.debug(String.format("profile %s pomFile not found", profile));
            this.registerMemento(profile, null, null);
            return null;
        }

        final Model modelFound;
        final boolean doResolve;
        if (this.hasMemento(profile, pomFile)) {
            modelFound = this.getMemento(profile, pomFile);
            doResolve = false;
            // if (logger.isDebugEnabled()) {
            //     logger.debug(String.format("resolveModel %s for profile %s false", pomFile.getPath(), profile));
            // }
        } else {
            modelFound = null;
            doResolve = true;
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("resolveModel %s for profile %s true", pomFile.getPath(), profile));
            }
        }

        if (!this.hasMemento(profile, pomFile)) {
            this.registerMemento(profile, pomFile, null);
        }

        if (doResolve) {
            // if (logger.isDebugEnabled()) {
            //     context.getProjectProperties().forEach((k, v) -> logger.debug(String.format("projectProperty %s => %s", k, v)));
            //     context.getUserProperties().forEach((k, v) -> logger.debug(String.format("%s => %s", k, v)));
            //     context.getSystemProperties().forEach((k, v) -> logger.debug(String.format("%s => %s", k, v)));
            // }

            try {
                final ModelBuildingRequest buildingRequest = this.modelBuildingRequest(context, pomFile);
                final ModelBuildingResult buildingResult = this.modelBuilder.build(buildingRequest);
                return this.registerMemento(profile, pomFile, buildingResult.getEffectiveModel());
            } catch (final Exception error) {
                logger.error(String.format("model build error: %s", error.getMessage()), error);
                return null;
            }
        } else {
            return modelFound;
        }
    }

    protected abstract ModelBuildingRequest modelBuildingRequest(ProfileActivationContext context, File pomFile);

    /**
     * Extract optional project pom.xml file from context.
     */
    private File projectPOM(final ProfileActivationContext context) {
        final File basedir = context.getProjectDirectory();
        if (basedir == null) {
            // logger.debug("context.projectDirectory is null");
            return null;
        }

        final File pomFile = new File(basedir, "pom.xml");
        if (pomFile.exists()) {
            return pomFile.getAbsoluteFile();
        } else {
            logger.warn(String.format("pomFile not exists [%s]", pomFile.getPath()));
            return null;
        }
    }

    /**
     * Check if project was already processed.
     */
    private boolean hasMemento(final Profile profile, final File pomFile) {
        final String key = mementoKey(profile, pomFile);
        final boolean result = pomFile != null && this.profileMemento.containsKey(key);
        // if (logger.isDebugEnabled()) {
        //     logger.debug(String.format("hasMemento(%s, %s) key %s, result %s", profile, pomFile, key, result));
        // }
        return result;
    }

    private Model getMemento(final Profile profile, final File pomFile) {
        // logger.debug(String.format("getMemento(%s, %s)", profile, pomFile));
        final String key = mementoKey(profile, pomFile);
        return hasMemento(profile, pomFile) ? this.profileMemento.get(key) : null;
    }

    private Model registerMemento(final Profile profile, final File pomFile, final Model model) {
        if (pomFile != null) {
            // if (logger.isDebugEnabled()) {
            //     logger.debug(String.format("registerMemento(%s, %s)", profile, pomFile));
            // }
            final String key = mementoKey(profile, pomFile);
            this.profileMemento.put(key, model);
        }
        return model;
    }

    private static String mementoKey(final Profile profile, final File pomFile) {
        final String pom = pomFile != null ? pomFile.getAbsolutePath() : "";
        return "" + profile + "@" + pom;
    }
}
