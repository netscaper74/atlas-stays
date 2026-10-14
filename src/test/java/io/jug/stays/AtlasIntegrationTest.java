package io.jug.stays;

import io.jug.stays.rag.ChatMemory;
import io.jug.stays.search.AtlasTextSearch;
import io.jug.stays.search.HotelSearch;
import io.jug.stays.search.RegexSearch;
import io.jug.stays.search.SemanticSearch;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end against your Atlas database. Skipped when MONGODB_URI isn't set.
 * The instructor's dry run:
 *
 *     mvn test -Dtest=AtlasIntegrationTest
 *
 * Prints the query ladder scorecard for all three versions.
 */
class AtlasIntegrationTest {

    @Test
    void ladderAndMemory() {
        Config config = new Config();
        assumeTrue(!config.mongoUri.isBlank(), "MONGODB_URI not set: skipping the Atlas test");

        Db db = new Db(config);
        SearchIndexes indexes = new SearchIndexes(db, config);
        Bootstrap boot = new Bootstrap(config, db, indexes);
        boot.run();
        assumeTrue(boot.ready, "bootstrap failed: " + boot.message);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtlasTextSearch text = new AtlasTextSearch(db, indexes, pool);
        Map<String, HotelSearch> versions = new LinkedHashMap<>();
        versions.put("v1", new RegexSearch(db));
        versions.put("v2", text);
        versions.put("v3", new SemanticSearch(db, indexes, text));
        System.out.println("Indexes: " + indexes.status());

        System.out.printf("%n%-46s %-10s %-10s %-10s%n", "Query ladder", "v1", "v2", "v3");
        for (Document row : Ladder.run(versions, pool)) {
            Document r = row.get("results", Document.class);
            System.out.printf("%-46s %-10s %-10s %-10s%n", row.getInteger("n") + ". " + row.getString("q"),
                    cell(r.get("v1", Document.class)), cell(r.get("v2", Document.class)), cell(r.get("v3", Document.class)));
        }

        // Memory round trip (Lab 4.3)
        ChatMemory memory = new ChatMemory(db);
        String sid = "it-" + UUID.randomUUID();
        try {
            memory.remember(sid, "user", "first", List.of());
            memory.remember(sid, "assistant", "second", List.of("x"));
            List<Document> back = memory.recall(sid, 10);
            assertEquals(List.of("first", "second"), back.stream().map(d -> d.getString("content")).toList());
        } catch (Lab.NotImplemented e) {
            System.out.println("Memory: " + e.getMessage());
        } finally {
            memory.forget(sid);
        }
    }

    private static String cell(Document c) {
        return switch (c.getString("status")) {
            case "todo" -> "lab " + c.getString("lab");
            case "error" -> "error";
            default -> c.getString("status") + " " + c.get("hits") + "/" + c.get("of");
        };
    }
}
