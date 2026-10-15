package io.jug.stays;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The workshop exercises.
 *
 * Every exercise is the body of one small method, wrapped in markers:
 *
 * <pre>
 *     // ▶ LAB 2.2
 *     ... solution ...
 *     // ◀ LAB 2.2
 * </pre>
 *
 * The starter branch replaces each marked block with {@code throw Lab.todo("2.2");}.
 * The app keeps running: the UI shows which exercises are still open, and any
 * feature that needs an open exercise tells you which one.
 */
public final class Lab {

    public record Exercise(String id, String version, String title, String where, String hint) {}

    public static final Map<String, Exercise> EXERCISES = new LinkedHashMap<>();

    static {
        add("1.1", "v1", "Match the query with a regular expression", "RegexSearch.textFilter",
                "Escape the user's input, then $or a case-insensitive $regex over name, type, district, city, description and amenities.");
        add("1.2", "v1", "Add the structured filters", "RegexSearch.structuredFilter",
                "city / type with $eq, pricePerNight with $gte/$lte, stars with $gte, amenities with $all.");
        add("2.2", "v2", "Write the Atlas Search text operator", "AtlasTextSearch.textOperator",
                "compound.should: boosted text on name/district/city/type, then amenities/tags, then description; autocomplete on name; per-word fuzzy.");
        add("2.3", "v2", "Turn the filters into search clauses", "AtlasTextSearch.filterClauses",
                "equals for city/type/amenities, range for pricePerNight and stars. They go in compound.filter, so they don't change the score.");
        add("2.4", "v2", "Define the facets", "AtlasTextSearch.facetDefinitions",
                "string facets for city, type, amenities; number facets with boundaries for stars and pricePerNight.");
        add("2.5", "v2", "Search-as-you-type", "AtlasTextSearch.autocompletePipeline",
                "$search with the autocomplete operator on name (fuzzy maxEdits 1), $limit 6, $project a few fields.");
        add("3.2", "v3", "Write the $vectorSearch stage", "SemanticSearch.vectorSearchStage",
                "index hotels_vector, path profile, query: { text: q }. Atlas embeds the text with Voyage AI for you.");
        add("3.3", "v3", "Pre-filter the vector search", "SemanticSearch.vectorFilter",
                "An MQL filter on fields indexed as type 'filter': city, type, pricePerNight, stars, amenities.");
        add("3.4", "v3", "Hybrid search with $rankFusion", "SemanticSearch.hybridPipeline",
                "$rankFusion with two input pipelines: your $vectorSearch and your $search. Reciprocal rank fusion merges them.");
        add("4.1", "v3", "Retrieve the context", "Concierge.retrieve",
                "Vector-search the question (plus the previous question for follow-ups) and keep the hotels from the last answer.");
        add("4.2", "v3", "Build the prompt", "Concierge.buildMessages",
                "System rules, then a CONTEXT block with the hotels, then the chat history, then the new question.");
        add("4.3", "v3", "Give the concierge a memory", "ChatMemory.remember / ChatMemory.recall",
                "insertOne each message with sessionId and createdAt; recall the last N for a session in chronological order.");
    }

    private static void add(String id, String version, String title, String where, String hint) {
        EXERCISES.put(id, new Exercise(id, version, title, where, hint));
    }

    /** Thrown by an exercise that hasn't been written yet. */
    public static final class NotImplemented extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final transient Exercise exercise;

        NotImplemented(Exercise e) {
            super("LAB " + e.id() + " is not implemented yet: " + e.title() + " (" + e.where() + ")");
            this.exercise = e;
        }
    }

    /** Exercises that have thrown at least once since the app started. */
    private static final Set<String> SEEN_TODO = ConcurrentHashMap.newKeySet();

    public static NotImplemented todo(String id) {
        Exercise e = EXERCISES.get(id);
        if (e == null) throw new IllegalArgumentException("Unknown lab " + id);
        SEEN_TODO.add(id);
        return new NotImplemented(e);
    }

    /**
     * Calls an exercise with dummy input and reports whether it is done.
     * Only a NotImplemented for this very exercise means "todo": an exercise that
     * depends on an unfinished one, or fails on the dummy input, still counts as written.
     */
    public static boolean probe(String id, Runnable r) {
        try {
            r.run();
            return true;
        } catch (NotImplemented e) {
            return !e.exercise.id().equals(id);
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean seenTodo(String id) {
        return SEEN_TODO.contains(id);
    }

    public static List<Exercise> all() {
        return List.copyOf(EXERCISES.values());
    }

    private Lab() {}
}
