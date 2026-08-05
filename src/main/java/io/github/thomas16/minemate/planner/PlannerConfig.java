package io.github.thomas16.minemate.planner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import io.github.thomas16.minemate.MineMate;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Planner settings, read from {@code config/minemate.json} and generated on first launch.
 *
 * <p>The API key is deliberately awkward to store here. It is read from the environment first,
 * then a system property, and only then from the file, whose generated {@code apiKey} field is
 * empty and documented as the last resort. It is never written back, never logged, and never put in
 * {@link #describe()} or {@code toString}: the config file lives beside worlds people zip up and
 * send to each other.
 */
public final class PlannerConfig {

    private static final String FILE_NAME = "minemate.json";

    public static final String DEFAULT_PROVIDER = "openai";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-5.6-terra";
    public static final String DEFAULT_EFFORT = "medium";
    public static final String DEFAULT_VERBOSITY = "low";
    public static final int DEFAULT_TIMEOUT_SECONDS = 45;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;
    public static final int DEFAULT_MAX_REPLANS = 3;

    private static final String ENV_SCOPED_KEY = "MINEMATE_OPENAI_API_KEY";
    private static final String ENV_KEY = "OPENAI_API_KEY";
    private static final String PROPERTY_KEY = "minemate.planner.apiKey";
    private static final String PROPERTY_MODEL = "minemate.planner.model";
    private static final String PROPERTY_ENABLED = "minemate.planner.enabled";

    private final boolean enabled;
    private final String provider;
    private final String baseUrl;
    private final String model;
    private final String reasoningEffort;
    private final String verbosity;
    private final int timeoutSeconds;
    private final int maxOutputTokens;
    private final int maxReplansPerOrder;
    private final String apiKey;
    private final String apiKeySource;

    private PlannerConfig(boolean enabled, String provider, String baseUrl, String model,
            String reasoningEffort, String verbosity, int timeoutSeconds, int maxOutputTokens,
            int maxReplansPerOrder, String apiKey) {
        this(enabled, provider, baseUrl, model, reasoningEffort, verbosity, timeoutSeconds,
                maxOutputTokens, maxReplansPerOrder, apiKey, "explicit");
    }

    private PlannerConfig(boolean enabled, String provider, String baseUrl, String model,
            String reasoningEffort, String verbosity, int timeoutSeconds, int maxOutputTokens,
            int maxReplansPerOrder, String apiKey, String apiKeySource) {
        this.enabled = enabled;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.verbosity = verbosity;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputTokens = maxOutputTokens;
        this.maxReplansPerOrder = maxReplansPerOrder;
        this.apiKey = apiKey;
        this.apiKeySource = apiKeySource;
    }

    /** All defaults and no key: the state a headless run and a keyless install both want. */
    public static PlannerConfig defaults() {
        return new PlannerConfig(true, DEFAULT_PROVIDER, DEFAULT_BASE_URL, DEFAULT_MODEL,
                DEFAULT_EFFORT, DEFAULT_VERBOSITY, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_REPLANS, "");
    }

    /**
     * Explicit settings, for callers that must not read a file: the headless suite drives a hosted
     * planner over a fixture transport and needs a short timeout and a small replan budget.
     */
    public static PlannerConfig of(String baseUrl, String model, String apiKey, int timeoutSeconds,
            int maxReplansPerOrder) {
        PlannerConfig defaults = defaults();
        return new PlannerConfig(true, DEFAULT_PROVIDER, baseUrl, model, defaults.reasoningEffort,
                defaults.verbosity, timeoutSeconds, defaults.maxOutputTokens, maxReplansPerOrder,
                apiKey == null ? "" : apiKey);
    }

    public static PlannerConfig loadOrCreate() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        JsonObject json = read(path);
        if (json == null) {
            json = write(path);
        }
        return from(json);
    }

    private static JsonObject read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (root != null && root.isJsonObject()) {
                return root.getAsJsonObject();
            }
            MineMate.LOGGER.warn("{} is not a JSON object; using planner defaults", FILE_NAME);
        } catch (IOException | JsonSyntaxException error) {
            // Never fail startup over a config file. Defaults leave the companion on the scripted
            // planner, which is inert rather than dangerous.
            MineMate.LOGGER.warn("Could not read {}: {}", FILE_NAME, error.getMessage());
        }
        return null;
    }

    private static JsonObject write(Path path) {
        JsonObject json = new JsonObject();
        json.addProperty("_comment", "MineMate planner settings. Leave apiKey empty and set the "
                + ENV_KEY + " environment variable instead; this file is easy to share by accident.");
        json.addProperty("enabled", true);
        json.addProperty("provider", DEFAULT_PROVIDER);
        json.addProperty("baseUrl", DEFAULT_BASE_URL);
        json.addProperty("model", DEFAULT_MODEL);
        json.addProperty("reasoningEffort", DEFAULT_EFFORT);
        json.addProperty("verbosity", DEFAULT_VERBOSITY);
        json.addProperty("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        json.addProperty("maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS);
        json.addProperty("maxReplansPerOrder", DEFAULT_MAX_REPLANS);
        json.addProperty("apiKey", "");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
            MineMate.LOGGER.info("Wrote default planner config to {}", path);
        } catch (IOException error) {
            MineMate.LOGGER.warn("Could not write {}: {}", FILE_NAME, error.getMessage());
        }
        return json;
    }

    private static PlannerConfig from(JsonObject json) {
        PlannerConfig defaults = defaults();
        Key key = resolveKey(json);
        return new PlannerConfig(
                property(PROPERTY_ENABLED).map(Boolean::parseBoolean)
                        .orElseGet(() -> bool(json, "enabled", defaults.enabled)),
                string(json, "provider", defaults.provider),
                string(json, "baseUrl", defaults.baseUrl),
                property(PROPERTY_MODEL).orElseGet(() -> string(json, "model", defaults.model)),
                string(json, "reasoningEffort", defaults.reasoningEffort),
                string(json, "verbosity", defaults.verbosity),
                integer(json, "timeoutSeconds", defaults.timeoutSeconds, 1, 300),
                integer(json, "maxOutputTokens", defaults.maxOutputTokens, 256, 32_000),
                integer(json, "maxReplansPerOrder", defaults.maxReplansPerOrder, 0, 20),
                key.value(), key.source());
    }

    /**
     * Most specific source wins, ambient last.
     *
     * <p>The order matters more than it looks. {@code OPENAI_API_KEY} is shared with every other
     * tool on the machine and is often set once and forgotten; the config file is something someone
     * deliberately typed a key into just now. Letting the ambient variable win means a stale key
     * silently overrides a fresh one, and the only symptom is a 401 naming a key the user does not
     * recognise. So: this invocation, then this mod's file, then this mod's variable, then the
     * shared one.
     */
    private static Key resolveKey(JsonObject json) {
        Optional<String> property = property(PROPERTY_KEY);
        if (property.isPresent()) {
            return new Key(property.get(), "-D" + PROPERTY_KEY);
        }
        String file = string(json, "apiKey", "");
        if (!file.isBlank()) {
            return new Key(file.strip(), FILE_NAME);
        }
        String scoped = System.getenv(ENV_SCOPED_KEY);
        if (scoped != null && !scoped.isBlank()) {
            return new Key(scoped.strip(), ENV_SCOPED_KEY);
        }
        String environment = System.getenv(ENV_KEY);
        if (environment != null && !environment.isBlank()) {
            return new Key(environment.strip(), ENV_KEY);
        }
        return new Key("", "");
    }

    /** The key and where it came from. The source is safe to log; the value never is. */
    private record Key(String value, String source) {
    }

    private static Optional<String> property(String name) {
        String value = System.getProperty(name, "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    // ---- accessors --------------------------------------------------------------------------

    public boolean enabled() {
        return enabled;
    }

    public String provider() {
        return provider;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public String verbosity() {
        return verbosity;
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int maxReplansPerOrder() {
        return maxReplansPerOrder;
    }

    public Optional<String> apiKey() {
        return apiKey.isBlank() ? Optional.empty() : Optional.of(apiKey);
    }

    public boolean usable() {
        return enabled && apiKey().isPresent();
    }

    /**
     * Safe to log and to print in chat: reports whether a key exists and which source supplied it,
     * never what it is. The source is the useful half. A machine-wide {@code OPENAI_API_KEY} left
     * over from something else is easy to forget about and produces a 401 naming a key the owner
     * does not recognise, so naming the winning source turns a confusing failure into an obvious one.
     */
    public String describe() {
        return "%s %s effort=%s replans<=%d timeout=%ds key=%s".formatted(
                provider, model, reasoningEffort, maxReplansPerOrder, timeoutSeconds,
                apiKey().isPresent() ? "present from " + apiKeySource : "missing");
    }

    @Override
    public String toString() {
        return "PlannerConfig[" + describe() + "]";
    }

    // ---- lenient readers --------------------------------------------------------------------

    private static String string(JsonObject json, String member, String fallback) {
        JsonElement value = json.get(member);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        String text = value.getAsString().strip();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean bool(JsonObject json, String member, boolean fallback) {
        JsonElement value = json.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static int integer(JsonObject json, String member, int fallback, int min, int max) {
        JsonElement value = json.get(member);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return Math.clamp(value.getAsInt(), min, max);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
