package io.jug.stays;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings come from environment variables, or from a {@code .env} file in the
 * working directory (environment variables win). See {@code .env.example}.
 */
public final class Config {

    private final Map<String, String> values = new HashMap<>();

    public final String mongoUri;
    public final String attendee;
    public final String database;
    public final int port;
    public final boolean reseed;
    public final boolean autoCreateIndexes;
    public final String embedModel;

    public final String llmProvider;   // auto | openai | anthropic | none
    public final String llmApiKey;
    public final String llmBaseUrl;
    public final String llmModel;
    public final String anthropicKey;

    public static final String HOTELS = "hotels";
    public static final String CHAT = "chat_history";
    public static final String SEARCH_INDEX = "hotels_search";
    public static final String VECTOR_INDEX = "hotels_vector";

    public Config() {
        loadDotEnv(Path.of(".env"));
        values.putAll(System.getenv());

        mongoUri = get("MONGODB_URI", "");
        attendee = slug(get("ATTENDEE", System.getProperty("user.name", "dev")));
        database = get("DB_NAME", "stays_" + attendee);
        port = Integer.parseInt(get("PORT", "8080"));
        reseed = bool(get("RESEED", "false"));
        autoCreateIndexes = bool(get("AUTO_CREATE_INDEXES", "false"));
        embedModel = get("EMBED_MODEL", "voyage-4");

        llmProvider = get("LLM_PROVIDER", "auto").toLowerCase(Locale.ROOT);
        llmApiKey = get("LLM_API_KEY", get("OPENAI_API_KEY", ""));
        llmBaseUrl = get("LLM_BASE_URL", "https://api.openai.com/v1").replaceAll("/+$", "");
        llmModel = get("LLM_MODEL", "");
        anthropicKey = get("ANTHROPIC_API_KEY", "");
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** "Ayşe Yılmaz" becomes "ayse_yilmaz": a safe database name suffix. */
    public static String slug(String s) {
        String folded = s.replace('ı', 'i').replace('İ', 'I');
        folded = Normalizer.normalize(folded, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String out = folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return out.isEmpty() ? "dev" : out.substring(0, Math.min(out.length(), 40));
    }

    /** The cluster host for display, without credentials. */
    public String clusterHost() {
        return mongoUri.replaceAll("^mongodb(\\+srv)?://([^@/]*@)?", "").replaceAll("[/?].*$", "");
    }

    private static boolean bool(String v) {
        return v.matches("(?i)1|true|yes|on");
    }

    private void loadDotEnv(Path path) {
        if (!Files.isRegularFile(path)) return;
        try {
            for (String line : Files.readAllLines(path)) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
                int i = l.indexOf('=');
                String key = l.substring(0, i).trim().replaceFirst("^export\\s+", "");
                String val = l.substring(i + 1).trim();
                if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"") || val.startsWith("'") && val.endsWith("'"))) {
                    val = val.substring(1, val.length() - 1);
                }
                values.put(key, val);
            }
        } catch (IOException e) {
            System.err.println("Could not read .env: " + e.getMessage());
        }
    }
}
