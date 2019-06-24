package top.infra.maven.extension.mavenbuild.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class PropertiesUtils {

    public static Properties toProperties(final Map<String, String> map) {
        final Properties result;
        if (map != null) {
            result = new Properties();
            result.putAll(map);
        } else {
            result = null;
        }
        return result;
    }

    public static Properties merge(final Properties properties, final Properties intoProperties) {
        properties.stringPropertyNames().forEach(name -> intoProperties.setProperty(name, properties.getProperty(name)));
        return intoProperties;
    }

    /**
     * Provide script execution context variables.
     */
    public static Map<String, Object> mapFromProperties(final Properties properties) {
        final Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }

    public static String toString(final Properties properties, final Pattern pattern) {
        final StringBuilder sb = new StringBuilder(pattern != null ? pattern.pattern() : "").append(System.lineSeparator());

        final String[] names = properties.stringPropertyNames()
            .stream()
            .filter(name -> pattern == null || pattern.matcher(name).matches())
            .sorted()
            .toArray(String[]::new);

        IntStream
            .range(0, names.length)
            .forEach(idx -> {
                if (idx > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(String.format("%03d ", idx));
                final String name = names[idx];
                sb.append(name).append("=").append(properties.getProperty(name));
            });

        return maskSecrets(sb.toString());
    }

    public static String maskSecrets(final String text) {
        return "" + text
            .replaceAll("KEYNAME=.+", "KEYNAME=<secret>")
            .replaceAll("keyname=.+", "keyname=<secret>")
            .replaceAll("LOGIN=.+", "LOGIN=<secret>")
            .replaceAll("login=.+", "login=<secret>")
            .replaceAll("ORGANIZATION=.+", "ORGANIZATION=<secret>")
            .replaceAll("organization=.+", "organization=<secret>")
            .replaceAll("PASS=.+", "PASS=<secret>")
            .replaceAll("pass=.+", "pass=<secret>")
            .replaceAll("PASSWORD=.+", "PASSWORD=<secret>")
            .replaceAll("password=.+", "password=<secret>")
            .replaceAll("PASSPHRASE=.+", "PASSPHRASE=<secret>")
            .replaceAll("passphrase=.+", "passphrase=<secret>")
            .replaceAll("TOKEN=.+", "TOKEN=<secret>")
            .replaceAll("token=.+", "token=<secret>")
            .replaceAll("USER=.+", "USER=<secret>")
            .replaceAll("user=.+", "user=<secret>")
            .replaceAll("USERNAME=.+", "USERNAME=<secret>")
            .replaceAll("username=.+", "username=<secret>");
    }

    public static String toString(final Map<String, String> properties, final Pattern pattern) {
        final StringBuilder sb = new StringBuilder(pattern != null ? pattern.pattern() : "").append(System.lineSeparator());

        final String[] names = properties.keySet()
            .stream()
            .filter(name -> pattern == null || pattern.matcher(name).matches())
            .sorted()
            .toArray(String[]::new);

        IntStream
            .range(0, names.length)
            .forEach(idx -> {
                if (idx > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(String.format("%03d ", idx));
                final String name = names[idx];
                sb.append(name).append("=").append(properties.get(name));
            });

        return maskSecrets(sb.toString());
    }
}
