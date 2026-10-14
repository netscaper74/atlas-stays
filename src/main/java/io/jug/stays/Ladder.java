package io.jug.stays;

import io.jug.stays.search.HotelSearch;
import io.jug.stays.search.SearchRequest;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * The query ladder: the same eight questions asked of all three versions.
 * Each rung checks the top 5 results against what a person would expect,
 * so the scorecard is computed from real results, not hand-filled.
 */
public final class Ladder {

    public record Rung(int n, String q, String lesson, String expect, Predicate<Document> check) {}

    static final int K = 5;

    public static final List<Rung> RUNGS = List.of(
            new Rung(1, "pool", "The baseline: a single English word.",
                    "has a pool", h -> hasAny(h, "amenities", "Pool", "Indoor pool")),
            new Rung(2, "kadikoy", "Diacritics: people type without Turkish characters.",
                    "is in Kadıköy", h -> "Kadıköy".equals(h.getString("district"))),
            new Rung(3, "bosphorus vew", "A typo.",
                    "has a Bosphorus view", h -> hasAny(h, "amenities", "Bosphorus view")),
            new Rung(4, "pet", "Substrings: a regex also finds \"rose petals\".",
                    "is pet friendly", h -> hasAny(h, "amenities", "Pet friendly")),
            new Rung(5, "quiet place to work remotely with fast wifi", "Intent, not keywords.",
                    "suits remote work and isn't a party spot", h -> hasAny(h, "tags", "remote-work") && !hasAny(h, "tags", "nightlife")),
            new Rung(6, "denize sıfır romantik bir otel", "Another language: Turkish question, mostly English descriptions.",
                    "is on the beach and romantic", h -> hasAny(h, "tags", "beach") && hasAny(h, "tags", "romantic")),
            new Rung(7, "travelling with kids and a dog", "Several needs at once, none of them named exactly.",
                    "welcomes pets and families", h -> hasAny(h, "amenities", "Pet friendly") && (hasAny(h, "tags", "family") || hasAny(h, "amenities", "Family rooms"))),
            new Rung(8, "cosy mountain hideaway with a fireplace", "A mood.",
                    "has a fireplace in the mountains or by a lake", h -> hasAny(h, "amenities", "Fireplace")
                    && List.of("mountain", "lake").contains(h.getString("setting"))));

    private static boolean hasAny(Document h, String field, String... values) {
        List<String> list = h.getList(field, String.class, List.of());
        for (String v : values) if (list.contains(v)) return true;
        return false;
    }

    /** Runs every rung against every version, in parallel. */
    public static List<Document> run(Map<String, HotelSearch> versions, ExecutorService pool) {
        List<Document> out = new ArrayList<>();
        for (Rung rung : RUNGS) {
            Document row = new Document("n", rung.n()).append("q", rung.q()).append("lesson", rung.lesson()).append("expect", rung.expect());
            Map<String, CompletableFuture<Document>> cells = new java.util.LinkedHashMap<>();
            for (var v : versions.entrySet()) {
                cells.put(v.getKey(), CompletableFuture.supplyAsync(() -> cell(v.getValue(), rung), pool));
            }
            Document results = new Document();
            cells.forEach((k, f) -> results.append(k, f.join()));
            out.add(row.append("results", results));
        }
        return out;
    }

    static Document cell(HotelSearch search, Rung rung) {
        try {
            List<Document> top = search.search(SearchRequest.of(rung.q()).withSize(K)).items;
            long hits = top.stream().filter(rung.check()).count();
            String status = top.isEmpty() ? "fail" : hits == K ? "pass" : hits > 0 ? "partial" : "fail";
            return new Document("status", status).append("hits", hits).append("of", K)
                    .append("top", top.stream().map(h -> new Document("name", h.getString("name"))
                            .append("ok", rung.check().test(h))).toList());
        } catch (Lab.NotImplemented e) {
            return new Document("status", "todo").append("lab", e.exercise.id());
        } catch (RuntimeException e) {
            return new Document("status", "error").append("error", String.valueOf(e.getMessage()));
        }
    }

    private Ladder() {}
}
