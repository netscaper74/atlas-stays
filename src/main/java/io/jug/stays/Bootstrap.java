package io.jug.stays;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Startup: connect to Atlas, load the hotels into this attendee's database
 * (only if it's empty), and create the regular indexes the chat memory needs.
 *
 * Search indexes are NOT created here: you create them yourself in labs 2 and 3
 * (Setup panel, or AUTO_CREATE_INDEXES=true for the instructor's demo).
 */
public final class Bootstrap {

    public volatile String phase = "starting";
    public volatile String message = "Starting…";
    public volatile boolean ready = false;
    public final List<String> errors = new ArrayList<>();

    /** Distinct values for the filter panel, read once after seeding. */
    public volatile Document catalog = new Document();

    private final Config config;
    private final Db db;
    private final SearchIndexes indexes;

    public Bootstrap(Config config, Db db, SearchIndexes indexes) {
        this.config = config;
        this.db = db;
        this.indexes = indexes;
    }

    public void run() {
        try {
            set("connecting", "Connecting to Atlas (" + config.clusterHost() + ")…");
            db.ping();

            long count = db.hotels().countDocuments();
            if (config.reseed || count == 0) {
                set("seeding", "Loading hotels into " + config.database + "…");
                seed();
            }

            set("indexes", "Creating regular indexes…");
            // Chat memory: read a session's messages in order, and expire old conversations after 3 days.
            db.chat().createIndex(Indexes.ascending("sessionId", "createdAt"));
            db.chat().createIndex(Indexes.ascending("createdAt"), new IndexOptions().expireAfter(3L, TimeUnit.DAYS));

            if (config.autoCreateIndexes) indexes.createMissing();

            catalog = new Document()
                    .append("cities", distinct("city"))
                    .append("types", distinct("type"))
                    .append("amenities", distinct("amenities"))
                    .append("count", db.hotels().countDocuments());
            ready = true;
            set("ready", "Ready");
        } catch (RuntimeException e) {
            e.printStackTrace();
            errors.add(e.getMessage());
            set("error", e.getMessage());
        }
    }

    private void seed() {
        db.hotels().drop();
        List<Document> hotels = loadHotels();
        db.hotels().insertMany(hotels);
        System.out.printf("[bootstrap] inserted %d hotels into %s.%s%n", hotels.size(), config.database, Config.HOTELS);
    }

    @SuppressWarnings("unchecked")
    static List<Document> loadHotels() {
        try (InputStream in = Bootstrap.class.getResourceAsStream("/data/hotels.json")) {
            if (in == null) throw new IllegalStateException("hotels.json not found on the classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Document.parse needs an object at the top level, so wrap the array.
            return (List<Document>) Document.parse("{\"hotels\":" + json + "}").get("hotels");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> distinct(String field) {
        java.text.Collator turkish = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));
        return db.hotels().distinct(field, String.class).into(new ArrayList<>()).stream().sorted(turkish).toList();
    }

    private void set(String phase, String message) {
        this.phase = phase;
        this.message = message;
        System.out.println("[bootstrap] " + phase + " - " + message);
    }
}
