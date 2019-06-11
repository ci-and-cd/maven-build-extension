package top.infra.maven.extension.mavenbuild.model;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.profile.ProfileActivationContext;

public interface ActivatorModelResolver {

    Model resolveModel(Profile profile, ProfileActivationContext context);
}
