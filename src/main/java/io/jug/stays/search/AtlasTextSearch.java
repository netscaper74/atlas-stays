package io.jug.stays.search;

import io.jug.stays.Config;
import io.jug.stays.Db;
import io.jug.stays.Json;
import io.jug.stays.Lab;
import io.jug.stays.SearchIndexes;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * v2 · Atlas Search ($search).
 *
 * A Lucene index next to your data, kept in sync by Atlas. It gives you
 * relevance scoring, analyzers (diacritic folding, stemming), fuzzy matching,
 * autocomplete, highlighting and facets. Index definition:
 * src/main/resources/indexes/hotels_search.json
 */
public final class AtlasTextSearch implements HotelSearch {

    static final String INDEX = Config.SEARCH_INDEX;
    static final List<String> STRONG = List.of("name", "district", "city", "type");
    static final List<String> MEDIUM = List.of("amenities", "tags");
    static final List<String> FUZZY_PATHS = List.of("name", "district", "city", "type", "amenities", "tags");

    private final Db db;
    private final SearchIndexes indexes;
    private final ExecutorService pool;

    public AtlasTextSearch(Db db, SearchIndexes indexes, ExecutorService pool) {
        this.db = db;
        this.indexes = indexes;
        this.pool = pool;
    }

    @Override
    public String version() {
        return "v2";
    }

    // LAB 2.2 ─────────────────────────────────────────────────────────────────────
    /**
     * The text part of the query, as a compound operator.
     *
     * Several "should" clauses, each with its own boost, so an exact hit in the
     * hotel name beats a hit somewhere in the description, which beats a typo match.
     * At least one clause has to match.
     */
    public Document textOperator(String q) {
        // ▶ LAB 2.2
        throw Lab.todo("2.2");
        // ◀ LAB 2.2
    }

    // LAB 2.3 ─────────────────────────────────────────────────────────────────────
    /**
     * Side-panel filters as search clauses. They go in compound.filter: they
     * include or exclude documents but don't change the score.
     */
    public List<Document> filterClauses(SearchRequest r) {
        // ▶ LAB 2.3
        throw Lab.todo("2.3");
        // ◀ LAB 2.3
    }

    // LAB 2.4 ─────────────────────────────────────────────────────────────────────
    /** Facet definitions for $searchMeta: what to count, and how to bucket numbers. */
    public Document facetDefinitions() {
        // ▶ LAB 2.4
        throw Lab.todo("2.4");
        // ◀ LAB 2.4
    }

    // LAB 2.5 ───────────────────────────────────────────────────────────────────
    /** Search-as-you-type suggestions for the header search box. */
    public List<Document> autocompletePipeline(String q) {
        // ▶ LAB 2.5
        throw Lab.todo("2.5");
        // ◀ LAB 2.5
    }

    // ───────────────────────────────────────────────────────────────── plumbing

    /** The whole compound operator: text (if any) as "must", filters as "filter". */
    public Document operator(SearchRequest r) {
        Document compound = new Document();
        if (r.hasText()) compound.append("must", List.of(textOperator(r.q())));
        List<Document> filter = filterClauses(r);
        if (filter.isEmpty() && !r.hasText()) filter = List.of(new Document("exists", new Document("path", "pricePerNight")));
        if (!filter.isEmpty()) compound.append("filter", filter);
        return new Document("compound", compound);
    }

    @Override
    public SearchResult search(SearchRequest r) {
        long t0 = System.currentTimeMillis();
        SearchResult out = new SearchResult(version());
        String note = indexes.readinessNote(INDEX);
        if (note != null) out.notes.add(note);

        Document search = new Document("index", INDEX).append("compound", operator(r).get("compound"));
        if (r.hasText()) search.append("highlight", new Document("path", List.of("name", "description")));
        Document sort = switch (r.sort()) {
            case "price_asc" -> new Document("pricePerNight", 1);
            case "price_desc" -> new Document("pricePerNight", -1);
            case "rating" -> new Document("rating", -1);
            default -> r.hasText() ? null : new Document("rating", -1);
        };
        if (sort != null) search.append("sort", sort);

        List<Document> pipeline = List.of(
                new Document("$search", search),
                new Document("$skip", r.page() * r.size()),
                new Document("$limit", r.size()),
                new Document("$addFields", new Document("_score", new Document("$meta", "searchScore"))
                        .append("_highlights", new Document("$meta", "searchHighlights"))),
                new Document("$project", CARD_PROJECTION));

        CompletableFuture<List<Document>> items = CompletableFuture.supplyAsync(
                () -> db.hotels().aggregate(pipeline).into(new ArrayList<>()), pool);
        out.debug.add(Json.debug("Results ($search)", pipeline));

        CompletableFuture<Document> facets = facetCounts(r, out.debug, out.notes);
        out.items.addAll(items.join());
        Document counted = facets.join();
        if (counted != null) {
            out.facets = counted.get("facets", Document.class);
            if (counted.get("total") instanceof Number n) out.total = n.longValue();
        } else {
            out.total = out.items.size() + (long) r.page() * r.size();
        }
        out.ms = System.currentTimeMillis() - t0;
        return out;
    }

    /**
     * Facet counts with $searchMeta, one query per group, run in parallel.
     * "Disjunctive" faceting: each group is counted with every filter except its own,
     * so picking Istanbul still shows how many hotels Antalya has.
     * Completes with {facets, total}, or with null when LAB 2.3 / 2.4 isn't done yet
     * (a note says so). v3 uses it too: Atlas Search counts, Vector Search ranks.
     */
    public CompletableFuture<Document> facetCounts(SearchRequest r, List<Document> debug, List<String> notes) {
        Map<String, Function<SearchRequest, SearchRequest>> groups = new LinkedHashMap<>();
        groups.put("city", SearchRequest::withoutCity);
        groups.put("type", SearchRequest::withoutType);
        groups.put("stars", SearchRequest::withoutStars);
        groups.put("price", SearchRequest::withoutPrice);
        groups.put("amenities", Function.identity());
        Map<String, CompletableFuture<Document>> metas = new LinkedHashMap<>();
        try {
            Document defs = facetDefinitions();
            for (var g : groups.entrySet()) {
                List<Document> metaPipeline = List.of(new Document("$searchMeta", new Document("index", INDEX)
                        .append("facet", new Document("operator", operator(g.getValue().apply(r)))
                                .append("facets", new Document(g.getKey(), defs.get(g.getKey()))))
                        .append("count", new Document("type", "total"))));
                metas.put(g.getKey(), CompletableFuture.supplyAsync(() -> db.hotels().aggregate(metaPipeline).first(), pool));
                if (g.getKey().equals("amenities")) debug.add(Json.debug("Facets ($searchMeta), one per group", metaPipeline));
            }
        } catch (Lab.NotImplemented e) {
            notes.add("Facet counts need LAB " + e.exercise.id() + ".");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(metas.values().toArray(CompletableFuture[]::new)).thenApply(done -> {
            Document facets = new Document();
            Long total = null;
            for (var m : metas.entrySet()) {
                Document meta = m.getValue().join();
                if (meta == null) continue;
                Document f = meta.get("facet", Document.class);
                Document group = f == null ? null : f.get(m.getKey(), Document.class);
                facets.append(m.getKey(), group == null ? List.of() : group.get("buckets"));
                Document count = meta.get("count", Document.class);
                if (m.getKey().equals("amenities") && count != null) total = ((Number) count.get("total")).longValue();
            }
            return new Document("facets", facets).append("total", total);
        });
    }

    public Document autocomplete(String q) {
        long t0 = System.currentTimeMillis();
        List<Document> pipeline = autocompletePipeline(q);
        List<Document> items = db.hotels().aggregate(pipeline).into(new ArrayList<>());
        return new Document("items", items).append("ms", System.currentTimeMillis() - t0)
                .append("debug", List.of(Json.debug("Autocomplete ($search)", pipeline)));
    }

    private static Document text(String q, List<String> path, int boost) {
        return new Document("text", new Document("query", q).append("path", path).append("score", boost(boost)));
    }

    private static Document boost(int value) {
        return new Document("boost", new Document("value", value));
    }
}
