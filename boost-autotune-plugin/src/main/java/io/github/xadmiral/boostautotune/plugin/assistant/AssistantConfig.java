package io.github.xadmiral.boostautotune.plugin.assistant;

import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Settings of the Assistant tab. The API key lives in the user's {@link Preferences} (outside the
 * settings file that people share); everything else round-trips through the plugin's properties.
 */
public final class AssistantConfig {
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_MODEL = "claude-opus-5";
    public static final String[] MODEL_CHOICES = {"claude-opus-5", "claude-fable-5-1", "claude-sonnet-5"};
    public static final String[] EFFORT_CHOICES = {"", "low", "medium", "high", "xhigh", "max"};
    private static final String PREF_NODE = "io/github/xadmiral/boostautotune";
    private static final String PREF_KEY = "assistant.apiKey";

    public String apiKey = "";
    public String model = DEFAULT_MODEL;
    public String baseUrl = DEFAULT_BASE_URL;
    /** Empty = the API default. */
    public String effort = "";
    public boolean fallbacks = true;
    /** Proposed changes go to the ECU without the Apply click. */
    public boolean autoApply = false;
    public int maxTokens = 8192;
    public int maxToolRounds = 25;
    /** Free text about the car and the goals, prepended to the system prompt. */
    public String profile = defaultProfile();

    public static String defaultProfile() {
        return "Car: (make, model, engine, cams, compression, turbo, wastegate, injectors, fuel, gearbox, throttle)\n"
                + "Goals: (e.g. consistent 100-200 km/h runs, no stall when shifting to neutral)\n"
                + "Known issues / notes: (what the last logs showed, what was changed recently)";
    }

    public AssistantConfig copy() {
        AssistantConfig c = new AssistantConfig();
        c.apiKey = apiKey;
        c.model = model;
        c.baseUrl = baseUrl;
        c.effort = effort;
        c.fallbacks = fallbacks;
        c.autoApply = autoApply;
        c.maxTokens = maxTokens;
        c.maxToolRounds = maxToolRounds;
        c.profile = profile;
        return c;
    }

    public void store(Properties p, String prefix) {
        p.setProperty(prefix + "model", model);
        p.setProperty(prefix + "baseUrl", baseUrl);
        p.setProperty(prefix + "effort", effort == null ? "" : effort);
        p.setProperty(prefix + "fallbacks", Boolean.toString(fallbacks));
        p.setProperty(prefix + "autoApply", Boolean.toString(autoApply));
        p.setProperty(prefix + "maxTokens", Integer.toString(maxTokens));
        p.setProperty(prefix + "profile", profile == null ? "" : profile);
    }

    public void load(Properties p, String prefix) {
        model = p.getProperty(prefix + "model", model);
        baseUrl = p.getProperty(prefix + "baseUrl", baseUrl);
        effort = p.getProperty(prefix + "effort", effort);
        fallbacks = Boolean.parseBoolean(p.getProperty(prefix + "fallbacks", Boolean.toString(fallbacks)));
        autoApply = Boolean.parseBoolean(p.getProperty(prefix + "autoApply", Boolean.toString(autoApply)));
        try {
            maxTokens = Integer.parseInt(p.getProperty(prefix + "maxTokens", Integer.toString(maxTokens)).trim());
        } catch (NumberFormatException e) {
            // keep the default
        }
        profile = p.getProperty(prefix + "profile", profile);
    }

    /** Reads the API key from the user's preferences (empty when none). */
    public void loadKey() {
        try {
            apiKey = Preferences.userRoot().node(PREF_NODE).get(PREF_KEY, "");
        } catch (RuntimeException e) {
            apiKey = "";
        }
    }

    public void saveKey() {
        try {
            Preferences node = Preferences.userRoot().node(PREF_NODE);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                node.remove(PREF_KEY);
            } else {
                node.put(PREF_KEY, apiKey.trim());
            }
            node.flush();
        } catch (Exception e) {
            // preferences unavailable (locked-down account): the key lives for this session only
        }
    }
}
