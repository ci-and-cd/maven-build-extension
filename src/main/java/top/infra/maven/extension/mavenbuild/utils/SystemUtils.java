package top.infra.maven.extension.mavenbuild.utils;

import static top.infra.maven.extension.mavenbuild.utils.SupportFunction.newTuple;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public abstract class SystemUtils {

    private SystemUtils() {
    }

    public static Map.Entry<Integer, String> exec(final String command) {
        try {
            final Process proc = Runtime.getRuntime().exec(command);
            return execResult(proc);
        } catch (final IOException ex) {
            return newTuple(-1, "");
        }
    }

    private static Map.Entry<Integer, String> execResult(final Process proc) {
        try {
            final String result = new BufferedReader(new InputStreamReader(proc.getInputStream()))
                .lines()
                .collect(Collectors.joining("\n"));
            return newTuple(proc.waitFor(), result);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return newTuple(-1, "");
        }
    }

    public static Map.Entry<Integer, String> exec(
        final Map<String, String> environment,
        final String stdIn,
        final List<String> command
    ) {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (environment != null) {
            processBuilder.environment().putAll(environment);
        }
        try {
            final Process proc = processBuilder.start();
            if (stdIn != null) {
                try (final PrintWriter writer = new PrintWriter(proc.getOutputStream())) {
                    writer.println(stdIn);
                    writer.flush();
                }
            }
            return execResult(proc);
        } catch (final IOException ex) {
            return newTuple(-1, "");
        }
    }

    public static String os() {
        final String osName = System.getProperty("os.name", "generic").toLowerCase();
        final String result;
        if (osName.contains("mac")) {
            result = "darwin";
        } else if (osName.contains("win")) {
            result = "windows";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            result = "unix";
        } else {
            result = "generic";
        }
        return result;
    }

    public static Optional<Integer> parseJavaVersion(final String javaVersion) {
        final Optional<Integer> result;

        if (javaVersion.matches("1.\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[1]));
        } else if (javaVersion.matches("\\d+\\.?.*")) {
            result = Optional.of(Integer.parseInt(javaVersion.split("\\.")[0]));
        } else {
            result = Optional.empty();
        }

        return result;
    }

    public static String systemJavaIoTmp() {
        return System.getProperty("java.io.tmpdir");
    }

    public static Optional<Integer> systemJavaVersion() {
        final String systemJavaVersion = System.getProperty("java.version");
        return parseJavaVersion(systemJavaVersion);
    }

    public static String systemUserDir() {
        return System.getProperty("user.dir", FileUtils.pathname(new File(".")));
    }

    public static String systemUserHome() {
        return System.getProperty("user.home", systemUserDir());
    }
}
