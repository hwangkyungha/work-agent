package local.work;

import java.nio.file.*;
import java.io.*;
import java.util.*;

final class Config {
    private final Properties properties = new Properties();
    private final Map<String, String> environment;
    Config(Path path) throws IOException { this(path, System.getenv()); }
    Config(Path path, Map<String, String> environment) throws IOException {
        this.environment = Map.copyOf(environment);
        if (Files.exists(path)) try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
    }
    String get(String key, String fallback) { return environment.getOrDefault(key, properties.getProperty(key, fallback)).trim(); }
    boolean has(String key) { return !get(key, "").isBlank(); }
    String environment(String key) { return environment.getOrDefault(key, "").trim(); }
    String agentName() { return has("AGENT_NAME") ? get("AGENT_NAME", "") : "work-agent"; }
    String agentCallName() { return has("AGENT_CALL_NAME") ? get("AGENT_CALL_NAME", "") : agentName(); }
    int number(String key, int fallback) { return Integer.parseInt(get(key, "" + fallback)); }
    Path data() { return Path.of(has("WORK_AGENT_DATA") ? get("WORK_AGENT_DATA", "") : "work/data").toAbsolutePath().normalize(); }
}
