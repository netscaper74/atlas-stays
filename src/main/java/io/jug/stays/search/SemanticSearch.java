package io.jug.stays.search;

import com.mongodb.MongoCommandException;
import io.jug.stays.Config;
import io.jug.stays.Db;
import io.jug.stays.Json;
import io.jug.stays.Lab;
import io.jug.stays.SearchIndexes;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3 · Atlas Vector Search with Automated Embedding (Voyage AI).
 *
 * The vector index embeds the {@code profile} field of every hotel with a
 * Voyage AI model, inside Atlas. At query time you pass plain text and Atlas
 * embeds it with the same model. No embedding code, no vectors in your documents.
 * Index definition: src/main/resources/indexes/hotels_vector.json
 */
public final class SemanticSearch implements HotelSearch {

    static final String INDEX = Config.VECTOR_INDEX;

    private final Db db;
    private final SearchIndexes indexes;
    private final AtlasTextSearch text;

    /**
     * Automated Embedding is a Preview feature: the docs show the query text both as
     * { query: { text: "..." } } and as { query: "..." }. We send the object form and
     * switch once if the cluster wants the other one.
     */
    private static volatile boolean queryAsObject = true;
    private static volatile boolean rankFusionSupported = true;

    public SemanticSearch(Db db, SearchIndexes indexes, AtlasTextSearch text) {
        this.db = db;
        this.indexes = indexes;
        this.text = text;
    }

    @Override
    public String version() {
        return "v3";
    }

    // LAB 3.2 ─────────────────────────────────────────────────────────────────────
    /**
     * The $vectorSearch stage. No queryVector: with Automated Embedding you send
     * the text and Atlas turns it into a vector with the model from the index.
     *
     * numCandidates is how many nearest neighbours the ANN search looks at before
     * keeping the best {@code limit}. Keep it well above limit (10 to 20 times).
     */
    public Document vectorSearchStage(String q, Document filter, int limit) {
        // ▶ LAB 3.2
        Document stage = new Document("index", INDEX)
                .append("path", "profile")
                .append("query", new Document("text", q))
                .append("numCandidates", Math.max(150, limit * 15))
                .append("limit", limit);
        if (filter != null && !filter.isEmpty()) stage.append("filter", filter);
        return new Document("$vectorSearch", stage);
        // ◀ LAB 3.2
    }

    // LAB 3.3 ─────────────────────────────────────────────────────────────────────
    /**
     * Pre-filter: MQL that runs inside the vector search, before the nearest
     * neighbours are picked. Every field used here must be indexed as type "filter".
     * Returns an empty document when there is nothing to filter.
     */
    public Document vectorFilter(SearchRequest r) {
        // ▶ LAB 3.3
        List<Document> and = new ArrayList<>();
        if (r.city() != null) and.add(new Document("city", r.city()));
        if (r.type() != null) and.add(new Document("type", r.type()));
        if (r.minPrice() != null) and.add(new Document("pricePerNight", new Document("$gte", r.minPrice())));
        if (r.maxPrice() != null) and.add(new Document("pricePerNight", new Document("$lte", r.maxPrice())));
        if (r.minStars() != null) and.add(new Document("stars", new Document("$gte", r.minStars())));
        for (String a : r.amenities()) and.add(new Document("amenities", a));
        if (and.isEmpty()) return new Document();
        return and.size() == 1 ? and.get(0) : new Document("$and", and);
        // ◀ LAB 3.3
    }

    // LAB 3.4 (stretch) ───────────────────────────────────────────────────────────
    /**
     * Hybrid search: run the vector search (meaning) and the Atlas Search query
     * (exact words, names, typos) side by side and merge the two ranked lists
     * with reciprocal rank fusion. Needs MongoDB 8.1 or later.
     */
    public List<Document> hybridPipeline(SearchRequest r, int limit) {
        // ▶ LAB 3.4
        List<Document> vector = List.of(vectorSearchStage(r.q(), vectorFilter(r), limit * 2));
        List<Document> keyword = List.of(
                new Document("$search", new Document("index", Config.SEARCH_INDEX).append("compound", text.operator(r).get("compound"))),
                new Document("$limit", limit * 2));
        return List.of(
                new Document("$rankFusion", new Document("input", new Document("pipelines",
                        new Document("vector", vector).append("text", keyword)))
                        .append("combination", new Document("weights", new Document("vector", 1).append("text", 1)))
                        .append("scoreDetails", true)),
                new Document("$limit", limit),
                new Document("$addFields", new Document("_score", new Document("$meta", "score"))
                        .append("_details", new Document("$meta", "scoreDetails"))),
                new Document("$project", CARD_PROJECTION));
        // ◀ LAB 3.4
    }

    // ───────────────────────────────────────────────────────────────── plumbing

    @Override
    public SearchResult search(SearchRequest r) {
        long t0 = System.currentTimeMillis();
        SearchResult out = new SearchResult(version());
        String note = indexes.readinessNote(INDEX);
        if (note != null) out.notes.add(note);

        if (!r.hasText()) {
            // Nothing to embed: a vector search needs a query. Show the filtered list by rating.
            Document filter = vectorFilter(r);
            db.hotels().find(filter).projection(CARD_PROJECTION).sort(new Document("rating", -1))
                    .skip(r.page() * r.size()).limit(r.size()).into(out.items);
            out.total = db.hotels().countDocuments(filter);
            out.debug.add(Json.debug("find() (no text to embed)", new Document("filter", filter)));
            out.ms = System.currentTimeMillis() - t0;
            return out;
        }

        int limit = (r.page() + 1) * r.size();
        if (r.hybrid()) {
            hybrid(r, limit, out);
        } else {
            List<Document> pipeline = new ArrayList<>(List.of(
                    vectorSearchStage(r.q(), vectorFilter(r), limit),
                    new Document("$addFields", new Document("_score", new Document("$meta", "vectorSearchScore"))),
                    new Document("$project", CARD_PROJECTION)));
            Document sort = sortStage(r);
            if (sort != null) pipeline.add(sort);
            pipeline.add(new Document("$skip", r.page() * r.size()));
            out.items.addAll(run(pipeline));
            out.debug.add(Json.debug("Semantic search ($vectorSearch, automated embedding)", pipeline));
        }
        out.total = out.items.size() + (long) r.page() * r.size();
        if (r.sort() != null && !r.sort().isEmpty()) out.notes.add("Sorted after retrieval: the vector search picked the " + limit + " closest hotels first.");
        out.ms = System.currentTimeMillis() - t0;
        return out;
    }

    private void hybrid(SearchRequest r, int limit, SearchResult out) {
        if (rankFusionSupported) {
            List<Document> pipeline = hybridPipeline(r, limit);
            try {
                List<Document> items = run(pipeline);
                for (Document d : items) d.put("_sources", sources(d.remove("_details")));
                out.items.addAll(items.subList(Math.min(items.size(), r.page() * r.size()), items.size()));
                out.debug.add(Json.debug("Hybrid ($rankFusion: $vectorSearch + $search)", pipeline));
                return;
            } catch (MongoCommandException e) {
                if (!e.getErrorMessage().contains("rankFusion")) throw e;
                rankFusionSupported = false;
                out.notes.add("$rankFusion needs MongoDB 8.1+. Fusing the two result lists in the app instead.");
            }
        }
        // Fallback: run both and apply reciprocal rank fusion in Java (score = Σ 1 / (60 + rank)).
        List<Document> vec = run(List.of(vectorSearchStage(r.q(), vectorFilter(r), limit * 2), new Document("$project", CARD_PROJECTION)));
        List<Document> kw = db.hotels().aggregate(List.of(
                new Document("$search", new Document("index", Config.SEARCH_INDEX).append("compound", text.operator(r).get("compound"))),
                new Document("$limit", limit * 2), new Document("$project", CARD_PROJECTION))).into(new ArrayList<>());
        Map<Object, Document> fused = new LinkedHashMap<>();
        addRanks(fused, vec, "vector");
        addRanks(fused, kw, "text");
        List<Document> sorted = fused.values().stream()
                .sorted(Comparator.comparingDouble((Document d) -> d.getDouble("_score")).reversed()).limit(limit).toList();
        out.items.addAll(sorted.subList(Math.min(sorted.size(), r.page() * r.size()), sorted.size()));
        out.debug.add(Json.debug("Vector half", vectorSearchStage(r.q(), vectorFilter(r), limit * 2)));
        out.debug.add(Json.debug("Keyword half", new Document("$search", new Document("index", Config.SEARCH_INDEX).append("compound", text.operator(r).get("compound")))));
    }

    private static void addRanks(Map<Object, Document> fused, List<Document> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            Document d = list.get(i);
            Document cur = fused.computeIfAbsent(d.get("_id"), k -> new Document(d).append("_score", 0.0).append("_sources", new ArrayList<String>()));
            cur.put("_score", cur.getDouble("_score") + 1.0 / (60 + i + 1));
            cur.getList("_sources", String.class).add(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> sources(Object details) {
        List<String> out = new ArrayList<>();
        if (details instanceof Document d && d.get("details") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Document x && x.get("rank") instanceof Number n && n.intValue() > 0) out.add(x.getString("inputPipelineName"));
            }
        }
        return out;
    }

    private static Document sortStage(SearchRequest r) {
        return switch (r.sort() == null ? "" : r.sort()) {
            case "price_asc" -> new Document("$sort", new Document("pricePerNight", 1));
            case "price_desc" -> new Document("$sort", new Document("pricePerNight", -1));
            case "rating" -> new Document("$sort", new Document("rating", -1));
            default -> null;
        };
    }

    /** "More like this": use a hotel's own profile as the query. */
    public Document similar(String id, int limit) {
        Document hotel = db.hotels().find(new Document("_id", id)).projection(new Document("profile", 1)).first();
        if (hotel == null) return new Document("items", List.of());
        List<Document> pipeline = List.of(
                vectorSearchStage(hotel.getString("profile"), new Document("_id", new Document("$ne", id)), limit),
                new Document("$addFields", new Document("_score", new Document("$meta", "vectorSearchScore"))),
                new Document("$project", CARD_PROJECTION));
        return new Document("items", run(pipeline)).append("debug", List.of(Json.debug("Similar hotels ($vectorSearch)", pipeline)));
    }

    /** Full hotel documents (with reviews, without the profile) for the concierge's context. */
    public List<Document> retrieve(String q, Document filter, int limit, List<Document> debug) {
        List<Document> pipeline = List.of(
                vectorSearchStage(q, filter, limit),
                new Document("$addFields", new Document("_score", new Document("$meta", "vectorSearchScore"))),
                new Document("$project", new Document("profile", 0)));
        debug.add(Json.debug("Retrieve ($vectorSearch)", pipeline));
        return run(pipeline);
    }

    /** Runs a pipeline, adapting the automated-embedding query syntax once if the cluster asks for it. */
    List<Document> run(List<Document> pipeline) {
        try {
            return db.hotels().aggregate(adapt(pipeline)).into(new ArrayList<>());
        } catch (MongoCommandException e) {
            String m = e.getErrorMessage();
            if (!m.contains("query") && !m.contains("text")) throw e;
            queryAsObject = !queryAsObject;
            try {
                return db.hotels().aggregate(adapt(pipeline)).into(new ArrayList<>());
            } catch (MongoCommandException again) {
                queryAsObject = !queryAsObject;
                throw e;
            }
        }
    }

    /** Rewrites { query: { text } } to { query: "..." } (or back), including inside $rankFusion. */
    private static List<Document> adapt(List<Document> pipeline) {
        if (queryAsObject) return pipeline;
        List<Document> out = new ArrayList<>();
        for (Document stage : pipeline) out.add(adaptStage(Document.parse(stage.toJson())));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Document adaptStage(Document stage) {
        Document vs = stage.get("$vectorSearch", Document.class);
        if (vs != null && vs.get("query") instanceof Document q) vs.put("query", q.getString("text"));
        Document rf = stage.get("$rankFusion", Document.class);
        if (rf != null) {
            Document pipelines = rf.get("input", Document.class).get("pipelines", Document.class);
            for (String k : pipelines.keySet()) {
                List<Document> p = (List<Document>) pipelines.get(k);
                pipelines.put(k, p.stream().map(SemanticSearch::adaptStage).toList());
            }
        }
        return stage;
    }
}
