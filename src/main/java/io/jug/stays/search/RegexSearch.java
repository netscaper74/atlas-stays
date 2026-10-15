package io.jug.stays.search;

import com.mongodb.client.model.Filters;
import io.jug.stays.Config;
import io.jug.stays.Db;
import io.jug.stays.Json;
import io.jug.stays.Lab;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * v1 · Regex only.
 *
 * What most apps start with: a find() with a case-insensitive $regex.
 * It works for "pool". It breaks on "kadikoy" (diacritics), "bosphorus vew"
 * (typo), "pet" (substring: it also finds "rose petals"), and it has no idea
 * which result is the most relevant. It also reads every document (COLLSCAN).
 */
public final class RegexSearch implements HotelSearch {

    /** Fields we look in. Note that a regex can't use a normal index unless it's an anchored prefix. */
    static final List<String> FIELDS = List.of("name", "type", "district", "city", "description", "amenities");

    private final Db db;

    public RegexSearch(Db db) {
        this.db = db;
    }

    @Override
    public String version() {
        return "v1";
    }

    // LAB 1.1 ─────────────────────────────────────────────────────────────────────
    /**
     * Turn the user's text into a filter: the text must appear, ignoring case,
     * in at least one of {@link #FIELDS}.
     *
     * Escape the input first. Otherwise a user typing "(" breaks the query, and a
     * user typing "(a+)+$" can make the server work very hard (ReDoS).
     */
    public Bson textFilter(String q) {
        // ▶ LAB 1.1
        Pattern pattern = Pattern.compile(escapeRegex(q.trim()), Pattern.CASE_INSENSITIVE);
        List<Bson> perField = new ArrayList<>();
        for (String field : FIELDS) {
            perField.add(Filters.regex(field, pattern));
        }
        return Filters.or(perField);
        // ◀ LAB 1.1
    }

    // LAB 1.2 ─────────────────────────────────────────────────────────────────────
    /** The filters from the side panel, as plain MQL. Returns null when there are none. */
    public Bson structuredFilter(SearchRequest r) {
        // ▶ LAB 1.2
        List<Bson> and = new ArrayList<>();
        if (r.city() != null) and.add(Filters.eq("city", r.city()));
        if (r.type() != null) and.add(Filters.eq("type", r.type()));
        if (r.minPrice() != null) and.add(Filters.gte("pricePerNight", r.minPrice()));
        if (r.maxPrice() != null) and.add(Filters.lte("pricePerNight", r.maxPrice()));
        if (r.minStars() != null) and.add(Filters.gte("stars", r.minStars()));
        if (!r.amenities().isEmpty()) and.add(Filters.all("amenities", r.amenities()));
        return and.isEmpty() ? null : Filters.and(and);
        // ◀ LAB 1.2
    }

    /** Escapes the characters that mean something in a regular expression. */
    public static String escapeRegex(String s) {
        return s.replaceAll("[\\\\.*+?^${}()|\\[\\]/-]", "\\\\$0");
    }

    @Override
    public SearchResult search(SearchRequest r) {
        long t0 = System.currentTimeMillis();
        SearchResult out = new SearchResult(version());

        List<Bson> parts = new ArrayList<>();
        if (r.hasText()) parts.add(textFilter(r.q()));
        Bson structured = structuredFilter(r);
        if (structured != null) parts.add(structured);
        Bson filter = parts.isEmpty() ? new Document() : parts.size() == 1 ? parts.get(0) : Filters.and(parts);

        // No relevance score exists, so the only "ranking" is natural order or an explicit sort.
        Document sort = switch (r.sort()) {
            case "price_asc" -> new Document("pricePerNight", 1);
            case "price_desc" -> new Document("pricePerNight", -1);
            case "rating" -> new Document("rating", -1);
            default -> null;
        };

        var find = db.hotels().find(filter).projection(CARD_PROJECTION).skip(r.page() * r.size()).limit(r.size());
        if (sort != null) find = find.sort(sort);
        find.into(out.items);
        out.total = db.hotels().countDocuments(filter);

        Document filterDoc = Json.toDocument(filter);
        out.debug.add(Json.debug("find() with $regex", new Document("filter", filterDoc)
                .append("sort", sort).append("skip", r.page() * r.size()).append("limit", r.size())));

        // LAB 1.3: look at how the server ran it.
        Document explain = explain(filterDoc);
        out.debug.add(Json.debug("explain(\"executionStats\")", explain));
        if (r.hasText()) {
            out.notes.add("Regex has no relevance score: results are in insertion order. The server " + explain.getString("plan")
                    + " and examined " + explain.get("docsExamined") + " documents to return " + explain.get("returned") + ".");
        }
        out.ms = System.currentTimeMillis() - t0;
        return out;
    }

    /** A short summary of explain("executionStats"): plan type, documents examined and returned. */
    Document explain(Document filter) {
        try {
            Document res = db.database.runCommand(new Document("explain",
                    new Document("find", Config.HOTELS).append("filter", filter))
                    .append("verbosity", "executionStats"));
            Document stats = res.get("executionStats", Document.class);
            String plan = res.get("queryPlanner", Document.class).toJson();
            return new Document("plan", plan.contains("COLLSCAN") ? "did a COLLSCAN" : plan.contains("IXSCAN") ? "used an index (IXSCAN)" : "ran")
                    .append("docsExamined", stats.get("totalDocsExamined"))
                    .append("returned", stats.get("nReturned"))
                    .append("executionTimeMillis", stats.get("executionTimeMillis"));
        } catch (RuntimeException e) {
            return new Document("plan", "could not be explained").append("error", e.getMessage());
        }
    }
}
