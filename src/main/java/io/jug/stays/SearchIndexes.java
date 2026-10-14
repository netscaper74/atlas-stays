package io.jug.stays;

import com.mongodb.MongoCommandException;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atlas Search and Vector Search indexes.
 *
 * The definitions live in src/main/resources/indexes/*.json: the same JSON you
 * would paste into the Atlas UI. We create them with the createSearchIndexes
 * command and watch their status with the $listSearchIndexes stage, so this
 * works with any recent Java driver.
 */
public final class SearchIndexes {

    public record Spec(String name, String type, String file) {}

    public static final List<Spec> SPECS = List.of(
            new Spec(Config.SEARCH_INDEX, "search", "/indexes/hotels_search.json"),
            new Spec(Config.VECTOR_INDEX, "vectorSearch", "/indexes/hotels_vector.json"));

    private final Db db;
    private final Config config;
    private final Map<String, Document> cache = new ConcurrentHashMap<>();
    private volatile long cachedAt = 0;

    public SearchIndexes(Db db, Config config) {
        this.db = db;
        this.config = config;
    }

    /** The index definition as stored on disk, with ${EMBED_MODEL} filled in. */
    public Document definition(Spec spec) {
        try (InputStream in = SearchIndexes.class.getResourceAsStream(spec.file())) {
            if (in == null) throw new IllegalStateException("Missing " + spec.file());
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${EMBED_MODEL}", config.embedModel);
            return Document.parse(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Spec spec(String name) {
        return SPECS.stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown index " + name));
    }

    /** The command we send. Shown in the Inspector so attendees can see there's no magic. */
    public Document createCommand(Spec spec) {
        return new Document("createSearchIndexes", Config.HOTELS)
                .append("indexes", List.of(new Document("name", spec.name())
                        .append("type", spec.type())
                        .append("definition", definition(spec))));
    }

    public Document create(String name) {
        Spec spec = spec(name);
        Document cmd = createCommand(spec);
        try {
            Document res = db.database.runCommand(cmd);
            cachedAt = 0;
            return new Document("ok", true).append("command", cmd).append("result", res);
        } catch (MongoCommandException e) {
            if (e.getErrorMessage().toLowerCase().contains("already exists")) {
                return new Document("ok", true).append("command", cmd).append("note", "Index already exists");
            }
            return new Document("ok", false).append("command", cmd).append("error", e.getErrorMessage());
        }
    }

    public Document drop(String name) {
        spec(name);
        Document cmd = new Document("dropSearchIndex", Config.HOTELS).append("name", name);
        try {
            db.database.runCommand(cmd);
            cachedAt = 0;
            return new Document("ok", true).append("command", cmd);
        } catch (MongoCommandException e) {
            return new Document("ok", false).append("command", cmd).append("error", e.getErrorMessage());
        }
    }

    /**
     * Status per index: { exists, status, queryable, error }. Cached for a few seconds,
     * because every search asks whether its index is ready.
     */
    public Map<String, Document> status() {
        if (System.currentTimeMillis() - cachedAt < 4000 && !cache.isEmpty()) return cache;
        Map<String, Document> fresh = new ConcurrentHashMap<>();
        for (Spec s : SPECS) fresh.put(s.name(), new Document("exists", false).append("status", "MISSING").append("queryable", false));
        try {
            List<Document> listed = db.hotels().aggregate(List.of(new Document("$listSearchIndexes", new Document())))
                    .into(new ArrayList<>());
            for (Document d : listed) {
                String name = d.getString("name");
                if (!fresh.containsKey(name)) continue;
                Document st = new Document("exists", true)
                        .append("status", d.getString("status"))
                        .append("queryable", Boolean.TRUE.equals(d.getBoolean("queryable")));
                if (d.get("latestDefinition") != null) st.append("definition", d.get("latestDefinition"));
                fresh.put(name, st);
            }
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            for (Document d : fresh.values()) d.append("status", "UNAVAILABLE").append("error", msg);
        }
        cache.clear();
        cache.putAll(fresh);
        cachedAt = System.currentTimeMillis();
        return cache;
    }

    public boolean queryable(String name) {
        Document d = status().get(name);
        return d != null && Boolean.TRUE.equals(d.getBoolean("queryable"));
    }

    /** A note for the result list when the index a version needs is not ready. */
    public String readinessNote(String name) {
        Document d = status().get(name);
        if (d == null || Boolean.TRUE.equals(d.getBoolean("queryable"))) return null;
        String st = d.getString("status");
        if ("MISSING".equals(st)) return "Index " + name + " doesn't exist yet. Create it from the Setup panel.";
        return "Index " + name + " is " + st + ". Queries return nothing until it is READY.";
    }

    public void createMissing() {
        for (Spec s : SPECS) {
            if (!Boolean.TRUE.equals(status().get(s.name()).getBoolean("exists"))) {
                Document r = create(s.name());
                System.out.println("[indexes] " + s.name() + ": " + (r.getBoolean("ok") ? "requested" : r.getString("error")));
            }
        }
    }
}
