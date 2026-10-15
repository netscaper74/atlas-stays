package io.jug.stays;

import com.mongodb.client.MongoDatabase;
import io.jug.stays.rag.ChatMemory;
import io.jug.stays.rag.Concierge;
import io.jug.stays.rag.Llm;
import io.jug.stays.search.AtlasTextSearch;
import io.jug.stays.search.RegexSearch;
import io.jug.stays.search.SearchRequest;
import io.jug.stays.search.SemanticSearch;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * "What does my code generate?" for every exercise: the method is called with a
 * fixed sample input and the MongoDB query it returns is shown in the code viewer.
 *
 * Exercises that talk to the database (4.1, 4.3) get a recording database: every
 * find, aggregate and insertOne is written down instead of being sent to Atlas.
 *
 * The reference output lives in src/main/resources/labs/samples.json. It's generated
 * from the solution branch with:  java ... io.jug.stays.Samples src/main/resources/labs/samples.json
 */
public final class Samples {

    /** The side-panel filters every sample uses: Istanbul, up to €300, 4★ or more, with a pool. */
    public static final SearchRequest FILTERS =
            new SearchRequest("bosphorus vew", "Istanbul", null, null, 300, 4, List.of("Pool"), "", 0, 10, false);

    record Sample(String input, Function<Recorder, Object> body) {}

    static final Map<String, Sample> SAMPLES = new LinkedHashMap<>();

    static {
        add("1.1", "textFilter(\"pet friendly\")",
                rec -> find(new RegexSearch(null).textFilter("pet friendly")));
        add("1.2", "structuredFilter(city: Istanbul, maxPrice: 300, minStars: 4, amenities: [Pool])",
                rec -> find(new RegexSearch(null).structuredFilter(FILTERS)));
        add("2.2", "textOperator(\"bosphorus vew\")",
                rec -> new AtlasTextSearch(null, null, null).textOperator("bosphorus vew"));
        add("2.3", "filterClauses(city: Istanbul, maxPrice: 300, minStars: 4, amenities: [Pool])",
                rec -> new AtlasTextSearch(null, null, null).filterClauses(FILTERS));
        add("2.4", "facetDefinitions()",
                rec -> new AtlasTextSearch(null, null, null).facetDefinitions());
        add("2.5", "autocompletePipeline(\"kadik\")",
                rec -> new AtlasTextSearch(null, null, null).autocompletePipeline("kadik"));
        add("3.2", "vectorSearchStage(\"quiet place to work with a sea view\", { city: \"Istanbul\" }, 10)",
                rec -> new SemanticSearch(null, null, null)
                        .vectorSearchStage("quiet place to work with a sea view", new Document("city", "Istanbul"), 10));
        add("3.3", "vectorFilter(city: Istanbul, maxPrice: 300, minStars: 4, amenities: [Pool])",
                rec -> new SemanticSearch(null, null, null).vectorFilter(FILTERS));
        add("3.4", "hybridPipeline(q: \"bosphorus vew\" + the same filters, 10)",
                rec -> new SemanticSearch(null, null, new AtlasTextSearch(null, null, null)).hybridPipeline(FILTERS, 10));
        add("4.1", "retrieve(\"which of those has a pool?\", history: one question and one answer)", rec -> {
            Db db = rec.db();
            List<Document> history = List.of(
                    new Document("role", "user").append("content", "a quiet boutique hotel in Kadıköy"),
                    new Document("role", "assistant").append("content", "Two good options: …")
                            .append("hotelIds", List.of("kadikoy-yeldegirmeni-boutique-hotel", "kadikoy-caferaga-anchor-suites")));
            new Concierge(db, new SemanticSearch(db, null, null), null, null)
                    .retrieve("which of those has a pool?", history, new Document(), new ArrayList<>());
            return rec.ops;
        });
        add("4.2", "buildMessages(\"is breakfast included?\", two hotels, history: one question and one answer)", rec -> {
            List<Document> hotels = Bootstrap.loadHotels().stream()
                    .filter(h -> "Istanbul".equals(h.getString("city"))).limit(2).toList();
            List<Document> history = List.of(
                    new Document("role", "user").append("content", "somewhere calm near the water in Istanbul"),
                    new Document("role", "assistant").append("content", "Try these two: …"));
            List<Document> out = new ArrayList<>();
            out.add(new Document("role", "system").append("content", Concierge.SYSTEM.strip()));
            for (Llm.Message m : new Concierge(null, null, null, null).buildMessages("is breakfast included?", hotels, history)) {
                out.add(new Document("role", m.role()).append("content", m.content()));
            }
            return out;
        });
        add("4.3", "remember(\"s-42\", \"user\", \"any hotels with a hammam?\", []) then recall(\"s-42\", 10)", rec -> {
            ChatMemory memory = new ChatMemory(rec.db());
            memory.remember("s-42", "user", "any hotels with a hammam?", List.of());
            memory.recall("s-42", 10);
            return rec.ops;
        });
    }

    private static void add(String id, String input, Function<Recorder, Object> run) {
        SAMPLES.put(id, new Sample(input, run));
    }

    private static Document find(Bson filter) {
        return new Document("find", "hotels").append("filter", filter == null ? null : Json.toDocument(filter));
    }

    /**
     * Runs the sample against the code in this app. Returns {input, output} when it works,
     * {input, todo: id} when an exercise (this one or one it uses) is still a to-do,
     * or {input, error} when the code throws.
     */
    public static Document run(String id) {
        Sample s = SAMPLES.get(id);
        if (s == null) return new Document("ok", false).append("error", "No sample for lab " + id);
        Document out = new Document("ok", true).append("id", id).append("input", s.input());
        try {
            out.append("output", pretty(s.body().apply(new Recorder())));
        } catch (Lab.NotImplemented e) {
            out.append("todo", e.exercise.id());
        } catch (RuntimeException e) {
            out.append("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return out;
    }

    /** The reference output, from labs/samples.json (null if the file or the entry is missing). */
    public static Document reference(String id) {
        return REFERENCE.get(id, Document.class);
    }

    /** For the code viewer: the sample input, the reference output and what this app's code generates. */
    public static Document forViewer(String id) {
        Document live = run(id);
        if (!live.getBoolean("ok")) return live;
        Document ref = reference(id);
        Document yours = new Document();
        for (String k : List.of("output", "todo", "error")) if (live.containsKey(k)) yours.append(k, live.get(k));
        return new Document("ok", true).append("id", id).append("input", live.getString("input"))
                .append("reference", ref == null ? null : ref.getString("output"))
                .append("yours", yours);
    }

    private static final Document REFERENCE = loadReference();

    private static Document loadReference() {
        try (InputStream in = Samples.class.getResourceAsStream("/labs/samples.json")) {
            return in == null ? new Document() : Document.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Document();
        }
    }

    /** JSON for the viewer: fixed dates so the reference doesn't change on every run. */
    static String pretty(Object value) {
        Document wrap = new Document("v", normalise(value));
        String json = wrap.toJson(PRETTY);
        // Unwrap { "v": ... } and dedent one level.
        String body = json.substring(json.indexOf(':') + 1, json.lastIndexOf('}')).strip();
        return body.replace("\n  ", "\n");
    }

    private static final org.bson.json.JsonWriterSettings PRETTY = org.bson.json.JsonWriterSettings.builder()
            .outputMode(org.bson.json.JsonMode.RELAXED)
            .indent(true)
            .dateTimeConverter((v, w) -> w.writeString("<now>"))
            .regularExpressionConverter((v, w) -> {
                w.writeStartObject();
                w.writeString("$regex", v.getPattern());
                w.writeString("$options", v.getOptions());
                w.writeEndObject();
            })
            .build();

    private static Object normalise(Object v) {
        if (v instanceof Bson b && !(v instanceof Document)) return Json.toDocument(b);
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) out.add(normalise(o));
            return out;
        }
        return v;
    }

    /**
     * A fake database that writes down what the code asks for. find() and aggregate()
     * return nothing; insertOne() stores nothing.
     */
    static final class Recorder {
        final List<Document> ops = new ArrayList<>();

        Db db() {
            return new Db(proxy(MongoDatabase.class, (p, m, a) -> switch (m.getName()) {
                case "getName" -> "stays_you";
                case "getCollection" -> collection((String) a[0]);
                default -> defaultValue(m.getReturnType());
            }));
        }

        private Object collection(String name) {
            return proxy(com.mongodb.client.MongoCollection.class, (p, m, a) -> switch (m.getName()) {
                case "find" -> {
                    Document op = new Document("find", name)
                            .append("filter", a != null && a.length > 0 && a[0] instanceof Bson b ? Json.toDocument(b) : new Document());
                    ops.add(op);
                    yield cursor(com.mongodb.client.FindIterable.class, op);
                }
                case "aggregate" -> {
                    List<Document> pipeline = new ArrayList<>();
                    for (Object stage : (List<?>) a[0]) pipeline.add(Json.toDocument((Bson) stage));
                    Document op = new Document("aggregate", name).append("pipeline", pipeline);
                    ops.add(op);
                    yield cursor(com.mongodb.client.AggregateIterable.class, op);
                }
                case "insertOne" -> {
                    ops.add(new Document("insertOne", name).append("document", Json.toDocument((Bson) a[0])));
                    yield null;
                }
                case "getNamespace", "toString" -> name;
                default -> defaultValue(m.getReturnType());
            });
        }

        /** find() / aggregate() results: options are recorded, and every way to read them yields nothing. */
        private <T> T cursor(Class<T> type, Document op) {
            return proxy(type, (p, m, a) -> {
                String n = m.getName();
                switch (n) {
                    case "sort", "projection", "limit", "skip" -> {
                        op.append(n, a[0] instanceof Bson b ? Json.toDocument(b) : a[0]);
                        return p;
                    }
                    case "into" -> {
                        return a[0];
                    }
                    case "forEach", "close" -> {
                        return null;
                    }
                    case "first" -> {
                        return null;
                    }
                    case "iterator", "cursor" -> {
                        return proxy(com.mongodb.client.MongoCursor.class, (p2, m2, a2) ->
                                m2.getName().equals("hasNext") ? false : defaultValue(m2.getReturnType()));
                    }
                    default -> {
                        if (m.getReturnType().isInstance(p)) return p; // any other builder option
                        return defaultValue(m.getReturnType());
                    }
                }
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler h) {
            return (T) Proxy.newProxyInstance(Samples.class.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        default -> type.getSimpleName() + " (recorder)";
                    };
                }
                return h.invoke(p, m, a);
            });
        }

        private static Object defaultValue(Class<?> t) {
            if (t == boolean.class) return false;
            if (t == long.class) return 0L;
            if (t == int.class) return 0;
            if (t == double.class) return 0d;
            return null;
        }
    }

    /** Writes the reference file. Run it on the solution branch. */
    public static void main(String[] args) throws IOException {
        Document all = new Document();
        for (String id : SAMPLES.keySet()) {
            Document r = run(id);
            if (!r.containsKey("output")) throw new IllegalStateException("Lab " + id + " did not produce a sample: " + r.toJson());
            all.append(id, new Document("input", r.getString("input")).append("output", r.getString("output")));
        }
        Path file = Path.of(args.length > 0 ? args[0] : "src/main/resources/labs/samples.json");
        Files.writeString(file, all.toJson(org.bson.json.JsonWriterSettings.builder().indent(true).build()) + "\n");
        System.out.println("Wrote " + all.size() + " samples to " + file);
    }

    private Samples() {}
}
