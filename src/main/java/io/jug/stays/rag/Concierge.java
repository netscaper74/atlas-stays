package io.jug.stays.rag;

import io.jug.stays.Db;
import io.jug.stays.Json;
import io.jug.stays.Lab;
import io.jug.stays.search.SearchRequest;
import io.jug.stays.search.SemanticSearch;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The AI concierge: retrieval-augmented generation over the hotel catalogue.
 *
 *   question ─▶ recall history ─▶ retrieve hotels ($vectorSearch) ─▶ build prompt ─▶ LLM ─▶ remember
 *
 * The LLM never picks hotels from its own memory: it can only talk about the
 * hotels we retrieved from Atlas and put in the prompt.
 */
public final class Concierge {

    static final int HISTORY_MESSAGES = 10;
    static final int TOP_K = 5;

    public static final String SYSTEM = """
            You are the concierge of Atlas Stays, a hotel search app for Türkiye.
            Rules:
            - Recommend only hotels listed in the CONTEXT of the latest message or mentioned earlier in this conversation.
              Never invent hotels, prices, amenities or reviews.
            - Mention hotels by their exact name and say briefly why each one fits (location, amenities, what guests say).
            - Prices are per night in EUR. Ratings are out of 10.
            - If nothing in the context fits, say so honestly and suggest how to rephrase.
            - Answer in the language of the question (Turkish or English). Keep it under 120 words.
            """;

    private final Db db;
    private final SemanticSearch semantic;
    private final ChatMemory memory;
    private final Llm llm;

    public Concierge(Db db, SemanticSearch semantic, ChatMemory memory, Llm llm) {
        this.db = db;
        this.semantic = semantic;
        this.memory = memory;
        this.llm = llm;
    }

    // LAB 4.1 ─────────────────────────────────────────────────────────────────────
    /**
     * Find the hotels to put in front of the model.
     *
     * Follow-up questions ("which of those has a pool?") mean little on their own,
     * so the previous question is added to the search text, and the hotels from the
     * previous answer stay in the context.
     */
    public List<Document> retrieve(String question, List<Document> history, Document filter, List<Document> debug) {
        // ▶ LAB 4.1
        throw Lab.todo("4.1");
        // ◀ LAB 4.1
    }

    // LAB 4.2 ─────────────────────────────────────────────────────────────────────
    /**
     * The messages we send: the conversation so far, then the new question with a
     * CONTEXT block listing the retrieved hotels. (The system prompt is {@link #SYSTEM}.)
     */
    public List<Llm.Message> buildMessages(String question, List<Document> hotels, List<Document> history) {
        // ▶ LAB 4.2
        throw Lab.todo("4.2");
        // ◀ LAB 4.2
    }

    /** One hotel as a compact paragraph for the prompt. */
    public static String describe(Document h) {
        String reviews = h.getList("reviews", Document.class, List.of()).stream()
                .map(r -> "\"" + r.getString("text") + "\" (" + r.get("rating") + "/10)")
                .collect(Collectors.joining(" "));
        return "%s — %s in %s, %s. %s★, guest rating %s/10, %s EUR/night. Amenities: %s. Good for: %s. %s Guests say: %s"
                .formatted(h.getString("name"), h.getString("type"), h.getString("district"), h.getString("city"),
                        h.get("stars"), h.get("rating"), h.get("pricePerNight"),
                        String.join(", ", h.getList("amenities", String.class, List.of())),
                        String.join(", ", h.getList("tags", String.class, List.of())),
                        h.getString("description"), reviews);
    }

    // ───────────────────────────────────────────────────────────────── plumbing

    public Document ask(String sessionId, String question, SearchRequest filters) {
        long t0 = System.currentTimeMillis();
        List<Document> trace = new ArrayList<>();
        List<Document> debug = new ArrayList<>();

        // 1. Memory
        List<Document> history;
        boolean memoryOn = true;
        try {
            history = memory.recall(sessionId, HISTORY_MESSAGES);
            trace.add(step("Recall", history.size() + " earlier messages from chat_history", t0));
        } catch (Lab.NotImplemented e) {
            history = List.of();
            memoryOn = false;
            trace.add(step("Recall", "Memory is off (LAB 4.3): every question starts from scratch", t0));
        }

        // 2. Retrieve
        long t1 = System.currentTimeMillis();
        List<Document> hotels = retrieve(question, history, semantic.vectorFilter(filters), debug);
        trace.add(step("Retrieve", hotels.size() + " hotels via $vectorSearch (automated embedding)", t1));

        // 3. Augment
        List<Llm.Message> messages = buildMessages(question, hotels, history);
        int promptChars = SYSTEM.length() + messages.stream().mapToInt(m -> m.content().length()).sum();
        trace.add(step("Augment", messages.size() + " messages, ~" + (promptChars / 4) + " tokens of prompt", System.currentTimeMillis()));
        debug.add(Json.debug("Prompt sent to the LLM", new Document("system", SYSTEM)
                .append("messages", messages.stream().map(m -> new Document("role", m.role()).append("content", m.content())).toList())));

        // 4. Generate
        long t2 = System.currentTimeMillis();
        String answer;
        if (llm.enabled()) {
            try {
                answer = llm.chat(SYSTEM, messages, 400);
                trace.add(step("Generate", llm.label(), t2));
            } catch (RuntimeException e) {
                answer = withoutLlm(hotels) + "\n\n(LLM error: " + e.getMessage() + ")";
                trace.add(step("Generate", "LLM call failed, showing retrieval only", t2));
            }
        } else {
            answer = withoutLlm(hotels);
            trace.add(step("Generate", "No LLM configured: showing what retrieval found", t2));
        }

        // 5. Remember
        List<String> ids = hotels.stream().filter(h -> !Boolean.TRUE.equals(h.getBoolean("_fromPreviousAnswer")))
                .map(h -> String.valueOf(h.get("_id"))).toList();
        if (memoryOn) {
            try {
                memory.remember(sessionId, "user", question, List.of());
                memory.remember(sessionId, "assistant", answer, ids);
                trace.add(step("Remember", "2 messages saved for session " + sessionId.substring(0, Math.min(8, sessionId.length())), System.currentTimeMillis()));
            } catch (Lab.NotImplemented e) {
                trace.add(step("Remember", "Not saved (LAB 4.3)", System.currentTimeMillis()));
            }
        }

        List<Document> cards = hotels.stream().map(h -> {
            Document c = new Document(h);
            c.remove("reviews");
            return c;
        }).toList();
        return new Document("answer", answer)
                .append("hotels", cards)
                .append("trace", trace)
                .append("memory", memoryOn)
                .append("llm", llm.label())
                .append("ms", System.currentTimeMillis() - t0)
                .append("debug", debug);
    }

    private static String withoutLlm(List<Document> hotels) {
        if (hotels.isEmpty()) return "I couldn't find hotels for that. Is the hotels_vector index READY?";
        StringBuilder sb = new StringBuilder("No LLM is configured, so here is what vector search retrieved:\n");
        int i = 1;
        for (Document h : hotels.subList(0, Math.min(3, hotels.size()))) {
            sb.append(i++).append(". ").append(h.getString("name")).append(" — ").append(h.getString("type"))
                    .append(" in ").append(h.getString("district")).append(", ").append(h.get("pricePerNight"))
                    .append(" EUR/night, rated ").append(h.get("rating")).append("/10\n");
        }
        sb.append("Set LLM_API_KEY (or ANTHROPIC_API_KEY) to get a written answer.");
        return sb.toString();
    }

    private static Document step(String name, String detail, long since) {
        return new Document("step", name).append("detail", detail).append("ms", System.currentTimeMillis() - since);
    }

    public List<Document> history(String sessionId) {
        try {
            return memory.recall(sessionId, 50);
        } catch (Lab.NotImplemented e) {
            return List.of();
        }
    }
}
