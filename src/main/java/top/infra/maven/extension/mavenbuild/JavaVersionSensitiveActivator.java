package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.SupportFunction.parseJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileId;
import static top.infra.maven.extension.mavenbuild.SupportFunction.profileJavaVersion;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectContext;
import static top.infra.maven.extension.mavenbuild.SupportFunction.projectName;
import static top.infra.maven.extension.mavenbuild.SupportFunction.propertiesToMap;
import static top.infra.maven.extension.mavenbuild.SupportFunction.reportProblem;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;

@Component(role = CustomActivator.class, hint = "JavaVersionSensitiveActivator")
public class JavaVersionSensitiveActivator implements ProfileActivator, CustomActivator {

    /**
     * Logger provided by Maven runtime.
     */
    @Requirement
    protected Logger logger;

    /**
     * Builder provided by Maven runtime.
     */
    @Requirement
    protected ModelBuilder builder;

    /**
     * Remember processed profile pom.xml file to control recursion.
     */
    protected Map<String, Model> profileMemento = new LinkedHashMap<>();

    protected static String mementoKey(final Profile profile, final File pomFile) {
        final String pom = pomFile != null ? pomFile.getAbsolutePath() : "";
        return "" + profile + "@" + pom;
    }

    /**
     * Check if project was already processed.
     */
    protected boolean hasMemento(final Profile profile, final File pomFile) {
        final String key = mementoKey(profile, pomFile);
        final boolean result = pomFile != null && this.profileMemento.containsKey(key);
        // logger.debug(String.format("hasMemento(%s, %s) key %s, result %s", profile, pomFile, key, result));
        return result;
    }

    protected Model getMemento(final Profile profile, final File pomFile) {
        // logger.debug(String.format("getMemento(%s, %s)", profile, pomFile));
        final String key = mementoKey(profile, pomFile);
        return hasMemento(profile, pomFile) ? this.profileMemento.get(key) : null;
    }

    protected Model registerMemento(final Profile profile, final File pomFile, final Model model) {
        if (pomFile != null) {
            // if (logger.isDebugEnabled()) {
            //     logger.debug(String.format("registerMemento(%s, %s)", profile, pomFile));
            // }
            final String key = mementoKey(profile, pomFile);
            this.profileMemento.put(key, model);
        }
        return model;
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
            final Model project = resolveModel(profile, context);
            if (project == null) {
                final Exception error = new Exception("Invalid Project");
                reportProblem(error.getMessage(), error, profile, context, problems);
                result = false;
            } else {
                final Map<String, Object> projectContext = projectContext(project, context);
                result = this.isActiveByProfileName(profile, projectContext);
            }
        }

        if (result && logger.isInfoEnabled()) {
            this.logger.info(String.format("JavaVersionSensitiveActivator project='%s' profile='%s' result='true'",
                projectName(context), profileId(profile)));
        } else if (!result && logger.isDebugEnabled()) {
            this.logger.debug(String.format("JavaVersionSensitiveActivator project='%s' profile='%s' result='false'",
                projectName(context), profileId(profile)));
        }

        return result;
    }

    @Override
    public boolean presentInConfig(final Profile profile, final ProfileActivationContext context, final ModelProblemCollector problems) {
        final Model model = resolveModel(profile, context);
        return model != null;
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

    /**
     * <p>
     * Resolve project pom.xml model: interpolate properties and fields.
     * </p>
     * Note: invokes recursive call back to this activator, control activator
     * recursion by processing projects only once via {@link #profileMemento}.
     */
    protected Model resolveModel(final Profile profile, final ProfileActivationContext context) {
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
                final ModelBuildingRequest buildingRequest = this.modelBuildingRequest(context);
                final ModelBuildingResult buildingResult = this.builder.build(buildingRequest);
                return this.registerMemento(profile, pomFile, buildingResult.getEffectiveModel());
            } catch (final Exception error) {
                logger.error(String.format("model build error: %s", error.getMessage()), error);
                return null;
            }
        } else {
            return modelFound;
        }
    }

    /**
     * Extract optional project pom.xml file from context.
     */
    File projectPOM(final ProfileActivationContext context) {
        final File basedir = context.getProjectDirectory();
        if (basedir == null) {
            // logger.debug("context.projectDirectory is null");
            return null;
        }

        final File pomFile = new File(basedir, "pom.xml");
        if (pomFile.exists()) {
            return pomFile.getAbsoluteFile();
        } else {
            logger.warn(String.format("pomFile %s not exists", pomFile.getPath()));
            return null;
        }
    }

    /**
     * Default model resolution request.
     */
    ModelBuildingRequest modelBuildingRequest(final ProfileActivationContext context) {
        final ModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(projectPOM(context));
        request.setSystemProperties(propertiesToMap(context.getSystemProperties()));
        request.setUserProperties(propertiesToMap(context.getUserProperties()));
        request.setLocationTracking(false);
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        return request;
    }
}
