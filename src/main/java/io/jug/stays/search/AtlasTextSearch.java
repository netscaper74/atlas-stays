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
        List<Document> should = new ArrayList<>();
        // 1. Exact words in the short, important fields. The "folding" analyzer makes "kadikoy" match "Kadıköy".
        should.add(text(q, STRONG, 6));
        // 2. Amenities and tags: "pet" matches the word "Pet friendly", but not "petals".
        should.add(text(q, MEDIUM, 3));
        // 3. The description, in English (stemmed) and Turkish (multi-analyzer "tr").
        should.add(new Document("text", new Document("query", q)
                .append("path", List.of("description", new Document("value", "description").append("multi", "tr")))));
        // 4. Prefix matches while the user is still typing.
        should.add(new Document("autocomplete", new Document("query", q).append("path", "name")
                .append("score", boost(2))));
        // 5. Typos, one word at a time. Atlas applies one maxEdits to the whole query,
        //    so per-word clauses stop short words from matching half the catalogue.
        for (String word : new LinkedHashSet<>(Arrays.asList(q.toLowerCase().split("\\s+")))) {
            if (word.length() < 3) continue;
            should.add(new Document("text", new Document("query", word).append("path", FUZZY_PATHS)
                    .append("fuzzy", new Document("maxEdits", word.length() >= 7 ? 2 : 1).append("prefixLength", 1).append("maxExpansions", 50))));
        }
        return new Document("compound", new Document("should", should).append("minimumShouldMatch", 1));
        // ◀ LAB 2.2
    }

    // LAB 2.3 ─────────────────────────────────────────────────────────────────────
    /**
     * Side-panel filters as search clauses. They go in compound.filter: they
     * include or exclude documents but don't change the score.
     */
    public List<Document> filterClauses(SearchRequest r) {
        // ▶ LAB 2.3
        List<Document> filter = new ArrayList<>();
        if (r.city() != null) filter.add(new Document("equals", new Document("path", "city").append("value", r.city())));
        if (r.type() != null) filter.add(new Document("equals", new Document("path", "type").append("value", r.type())));
        if (r.minPrice() != null || r.maxPrice() != null) {
            Document range = new Document("path", "pricePerNight");
            if (r.minPrice() != null) range.append("gte", r.minPrice());
            if (r.maxPrice() != null) range.append("lte", r.maxPrice());
            filter.add(new Document("range", range));
        }
        if (r.minStars() != null) filter.add(new Document("range", new Document("path", "stars").append("gte", r.minStars())));
        for (String a : r.amenities()) filter.add(new Document("equals", new Document("path", "amenities").append("value", a)));
        return filter;
        // ◀ LAB 2.3
    }

    // LAB 2.4 ─────────────────────────────────────────────────────────────────────
    /** Facet definitions for $searchMeta: what to count, and how to bucket numbers. */
    public Document facetDefinitions() {
        // ▶ LAB 2.4
        return new Document()
                .append("city", new Document("type", "string").append("path", "city").append("numBuckets", 30))
                .append("type", new Document("type", "string").append("path", "type").append("numBuckets", 20))
                .append("amenities", new Document("type", "string").append("path", "amenities").append("numBuckets", 40))
                .append("stars", new Document("type", "number").append("path", "stars")
                        .append("boundaries", List.of(1, 2, 3, 4, 5, 6)))
                .append("price", new Document("type", "number").append("path", "pricePerNight")
                        .append("boundaries", List.of(0, 50, 100, 150, 200, 300, 400, 600, 900, 1300))
                        .append("default", "more"));
        // ◀ LAB 2.4
    }

    // LAB 2.5 (stretch) ───────────────────────────────────────────────────────────
    /** Search-as-you-type suggestions for the header search box. */
    public List<Document> autocompletePipeline(String q) {
        // ▶ LAB 2.5
        return List.of(
                new Document("$search", new Document("index", INDEX)
                        .append("autocomplete", new Document("query", q).append("path", "name")
                                .append("fuzzy", new Document("maxEdits", 1).append("prefixLength", 1)))),
                new Document("$limit", 6),
                new Document("$project", new Document("name", 1).append("district", 1).append("city", 1)
                        .append("type", 1).append("pricePerNight", 1).append("art", 1).append("setting", 1)
                        .append("score", new Document("$meta", "searchScore"))));
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

        // Facet counts. "Disjunctive" faceting: each group is counted with every filter
        // except its own, so picking Istanbul still shows how many hotels Antalya has.
        Map<String, Function<SearchRequest, SearchRequest>> groups = new LinkedHashMap<>();
        groups.put("city", SearchRequest::withoutCity);
        groups.put("type", SearchRequest::withoutType);
        groups.put("stars", SearchRequest::withoutStars);
        groups.put("price", SearchRequest::withoutPrice);
        groups.put("amenities", Function.identity());
        Document facets = null;
        Map<String, CompletableFuture<Document>> metas = new LinkedHashMap<>();
        try {
            Document defs = facetDefinitions();
            for (var g : groups.entrySet()) {
                List<Document> metaPipeline = List.of(new Document("$searchMeta", new Document("index", INDEX)
                        .append("facet", new Document("operator", operator(g.getValue().apply(r)))
                                .append("facets", new Document(g.getKey(), defs.get(g.getKey()))))
                        .append("count", new Document("type", "total"))));
                metas.put(g.getKey(), CompletableFuture.supplyAsync(() -> db.hotels().aggregate(metaPipeline).first(), pool));
                if (g.getKey().equals("amenities")) out.debug.add(Json.debug("Facets ($searchMeta), one per group", metaPipeline));
            }
        } catch (Lab.NotImplemented e) {
            out.notes.add("Facet counts need LAB 2.4.");
            metas.clear();
        }

        out.items.addAll(items.join());
        if (!metas.isEmpty()) {
            facets = new Document();
            for (var m : metas.entrySet()) {
                Document meta = m.getValue().join();
                if (meta == null) continue;
                Document f = meta.get("facet", Document.class);
                Document group = f == null ? null : f.get(m.getKey(), Document.class);
                facets.append(m.getKey(), group == null ? List.of() : group.get("buckets"));
                Document count = meta.get("count", Document.class);
                if (m.getKey().equals("amenities") && count != null) out.total = ((Number) count.get("total")).longValue();
            }
        } else {
            out.total = out.items.size() + (long) r.page() * r.size();
        }
        out.facets = facets;
        out.ms = System.currentTimeMillis() - t0;
        return out;
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
