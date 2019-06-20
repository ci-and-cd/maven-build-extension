package top.infra.maven.extension.mavenbuild;

import static top.infra.maven.extension.mavenbuild.CiOption.PATTERN_CI_ENV_VARS;
import static top.infra.maven.extension.mavenbuild.utils.SystemUtils.systemUserHome;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy.Context;
import org.apache.maven.rtinfo.RuntimeInformation;

import top.infra.maven.extension.mavenbuild.utils.MavenUtils;
import top.infra.maven.extension.mavenbuild.utils.PropertiesUtils;
import top.infra.maven.logging.Logger;
import top.infra.maven.logging.LoggerPlexusImpl;

@Named
@Singleton
public class PrintInfoEventAware implements MavenEventAware {

    public static final int ORDER_PRINT_INFO = Integer.MIN_VALUE;

    private Logger logger;

    // @org.codehaus.plexus.component.annotations.Requirement
    private final RuntimeInformation runtime;

    private String rootProjectPathname;

    @Inject
    public PrintInfoEventAware(
        final org.codehaus.plexus.logging.Logger logger,
        final RuntimeInformation runtime
    ) {
        this.logger = new LoggerPlexusImpl(logger);

        this.runtime = runtime;

        this.rootProjectPathname = null;
    }

    @Override
    public int getOrder() {
        return ORDER_PRINT_INFO;
    }

    @Override
    public void onInit(final Context context) {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("init with context [%s]", context));
        }

        final Map<String, Object> contextData = context.getData();
        final Properties systemProperties = MavenUtils.systemProperties(context);
        final Properties userProperties = MavenUtils.userProperties(context);

        if (logger.isInfoEnabled()) {
            contextData.keySet().stream().sorted().forEach(k -> {
                final Object v = contextData.get(k);
                if (v instanceof Properties) {
                    logger.info(String.format("contextData found properties %s => ", k));
                    logger.info(PropertiesUtils.toString((Properties) v, null));
                } else {
                    logger.info(String.format("contextData found property   %s => %s", k, v));
                }
            });
        }

        this.printClassPath(context);

        logger.info(">>>>>>>>>> ---------- init systemProperties ---------- >>>>>>>>>>");
        logger.info(PropertiesUtils.toString(systemProperties, PATTERN_CI_ENV_VARS));
        logger.info("<<<<<<<<<< ---------- init systemProperties ---------- <<<<<<<<<<");
        logger.info(">>>>>>>>>> ---------- init userProperties ---------- >>>>>>>>>>");
        logger.info(PropertiesUtils.toString(userProperties, null));
        logger.info("<<<<<<<<<< ---------- init userProperties ---------- <<<<<<<<<<");

        this.rootProjectPathname = CiOption.rootProjectPathname(systemProperties);
        final String artifactId = new File(this.rootProjectPathname).getName();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("artifactId: [%s]", artifactId));
        }

        if (logger.isInfoEnabled()) {
            logger.info(">>>>>>>>>> ---------- build context info ---------- >>>>>>>>>>");
            logger.info(String.format("user.language [%s], user.region [%s], user.timezone [%s]",
                System.getProperty("user.language"), System.getProperty("user.region"), System.getProperty("user.timezone")));
            logger.info(String.format("USER [%s]", System.getProperty("user.name")));
            logger.info(String.format("HOME [%s]", systemUserHome()));
            logger.info(String.format("JAVA_HOME [%s]", System.getProperty("java.home")));
            logger.info(String.format("PWD [%s]", this.rootProjectPathname));

            final String runtimeImplVersion = Runtime.class.getPackage().getImplementationVersion();
            final String javaVersion = runtimeImplVersion != null ? runtimeImplVersion : System.getProperty("java.runtime.version");

            logger.info(String.format("Java version [%s]", javaVersion));
            logger.info(String.format("Maven version [%s]", this.runtime.getMavenVersion()));

            logger.info(new AppveyorVariables(systemProperties).toString());
            logger.info(new GitlabCiVariables(systemProperties).toString());
            logger.info(new TravisCiVariables(systemProperties).toString());
            logger.info("<<<<<<<<<< ---------- build context info ---------- <<<<<<<<<<");
        }
    }

    private void printClassPath(final Context context) {
        if (logger.isInfoEnabled()) {
            classPathEntries(logger, ClassLoader.getSystemClassLoader()).forEach(entry ->
                logger.info(String.format("                system classpath entry: %s", entry)));
            classPathEntries(logger, Thread.currentThread().getContextClassLoader()).forEach(entry ->
                logger.info(String.format("current thread context classpath entry: %s", entry)));
            classPathEntries(logger, context.getClass().getClassLoader()).forEach(entry ->
                logger.info(String.format("          apache-maven classpath entry: %s", entry)));
            classPathEntries(logger, this.getClass().getClassLoader()).forEach(entry ->
                logger.info(String.format(" maven-build-extension classpath entry: %s", entry)));
        }
    }

    private static List<String> classPathEntries(
        final Logger logger,
        final ClassLoader cl
    ) {
        final List<String> result = new LinkedList<>();
        if (cl instanceof URLClassLoader) {
            final URL[] urls = ((URLClassLoader) cl).getURLs();
            for (final URL url : urls) {
                result.add(url.toString());
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.warn(String.format("Inspecting entries of [%s] is not supported", cl.getClass().getCanonicalName()));
            }
        }
        return result;
    }
}
