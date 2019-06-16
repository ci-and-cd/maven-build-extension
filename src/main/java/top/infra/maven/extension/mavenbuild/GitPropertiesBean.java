package top.infra.maven.extension.mavenbuild;

import java.util.HashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Named
@Singleton
public class GitPropertiesBean extends GitProperties {

    @Inject
    public GitPropertiesBean(final org.codehaus.plexus.logging.Logger logger) {
        this(new LoggerPlexusImpl(logger));
    }

    private GitPropertiesBean(final Logger logger) {
        super(logger, gitPropertiesMap(logger).orElse(new HashMap<>()));
    }
}
