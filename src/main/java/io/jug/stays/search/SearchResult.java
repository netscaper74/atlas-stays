package io.jug.stays.search;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/** What every version returns. The UI renders all three the same way. */
public final class SearchResult {
    public final String version;
    public final List<Document> items = new ArrayList<>();
    public long total;
    /** Facet buckets per group, or null when the version can't count (v1, v3). */
    public Document facets;
    /** Exact queries and pipelines that ran, for the Atlas Inspector. */
    public final List<Document> debug = new ArrayList<>();
    /** Short remarks for the user: "index still building", "regex scanned 255 documents"... */
    public final List<String> notes = new ArrayList<>();
    public long ms;

    public SearchResult(String version) {
        this.version = version;
    }

    public Document toDocument() {
        return new Document("version", version)
                .append("items", items)
                .append("total", total)
                .append("facets", facets)
                .append("notes", notes)
                .append("ms", ms)
                .append("debug", debug);
    }
}
