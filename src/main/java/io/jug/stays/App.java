package io.jug.stays;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jug.stays.rag.ChatMemory;
import io.jug.stays.rag.Concierge;
import io.jug.stays.rag.Llm;
import io.jug.stays.search.AtlasTextSearch;
import io.jug.stays.search.HotelSearch;
import io.jug.stays.search.RegexSearch;
import io.jug.stays.search.SearchRequest;
import io.jug.stays.search.SemanticSearch;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atlas Stays: one hotel app, three generations of search.
 *
 * A small HTTP server from the JDK (no framework), so everything you see in the
 * browser maps to a few lines of Java and one MongoDB query.
 */
public final class App {

    interface Handler {
        Object handle(HttpExchange ex, Matcher path, Map<String, String> query) throws Exception;
    }

    record Route(String method, Pattern path, Handler handler, boolean needsReady) {}

    private final Config config;
    private final Db db;
    private final SearchIndexes indexes;
    private final Bootstrap bootstrap;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, HotelSearch> versions = new LinkedHashMap<>();
    private final AtlasTextSearch text;
    private final SemanticSearch semantic;
    private final Concierge concierge;
    private final ChatMemory memory;
    private final Llm llm;
    private final LabSolver solver;
    private final List<Route> routes = new ArrayList<>();

    App(Config config) {
        this.config = config;
        this.db = new Db(config);
        this.indexes = new SearchIndexes(db, config);
        this.bootstrap = new Bootstrap(config, db, indexes);
        RegexSearch regex = new RegexSearch(db);
        this.text = new AtlasTextSearch(db, indexes, pool);
        this.semantic = new SemanticSearch(db, indexes, text);
        versions.put("v1", regex);
        versions.put("v2", text);
        versions.put("v3", semantic);
        this.llm = new Llm(config);
        this.solver = new LabSolver(config);
        this.memory = new ChatMemory(db);
        this.concierge = new Concierge(db, semantic, memory, llm);
        defineRoutes();
    }

    public static void main(String[] args) throws IOException {
        Config config = new Config();
        App app;
        try {
            app = new App(config);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("\n  Can't start: " + e.getMessage() + "\n");
            System.exit(1);
            return;
        }
        app.start();
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port), 0);
        server.setExecutor(pool);
        server.createContext("/", this::serve);
        server.start();
        System.out.printf("%nAtlas Stays → http://localhost:%d   (attendee %s, database %s, LLM %s)%n%n",
                config.port, config.attendee, config.database, llm.enabled() ? llm.label() : "off");
        pool.submit(bootstrap::run);
    }

    // ───────────────────────────────────────────────────────────────────── routes

    private void defineRoutes() {
        get("/api/status", false, (ex, m, q) -> status());
        get("/api/config", true, (ex, m, q) -> new Document("brand", "Atlas Stays")
                .append("attendee", config.attendee)
                .append("database", config.database)
                .append("cluster", config.clusterHost())
                .append("embedModel", config.embedModel)
                .append("llm", llm.label())
                .append("catalog", bootstrap.catalog)
                .append("maxPrice", SearchRequest.MAX_PRICE)
                .append("ladder", Ladder.RUNGS.stream().map(r -> new Document("n", r.n()).append("q", r.q())
                        .append("lesson", r.lesson()).append("expect", r.expect())).toList()));

        get("/api/search", true, (ex, m, q) -> {
            HotelSearch s = versions.get(q.getOrDefault("v", "v2"));
            if (s == null) throw new IllegalArgumentException("v must be v1, v2 or v3");
            return s.search(SearchRequest.from(q)).toDocument();
        });

        get("/api/compare", true, (ex, m, q) -> {
            SearchRequest r = SearchRequest.from(q).withSize(Math.min(12, SearchRequest.from(q).size()));
            Map<String, CompletableFuture<Document>> all = new LinkedHashMap<>();
            for (var v : versions.entrySet()) all.put(v.getKey(), CompletableFuture.supplyAsync(() -> runSafely(v.getValue(), r), pool));
            Document out = new Document();
            all.forEach((k, f) -> out.append(k, f.join()));
            return out;
        });

        get("/api/autocomplete", true, (ex, m, q) -> text.autocomplete(q.getOrDefault("q", "").trim()));

        get("/api/hotels/([\\w-]+)", true, (ex, m, q) -> {
            Document hotel = db.hotels().find(new Document("_id", m.group(1))).projection(new Document("profile", 0)).first();
            if (hotel == null) return new Status(404, new Document("error", "Hotel not found"));
            Document out = new Document("hotel", hotel);
            try {
                out.append("similar", indexes.queryable(Config.VECTOR_INDEX) ? semantic.similar(m.group(1), 6) : null);
            } catch (Lab.NotImplemented e) {
                out.append("similar", null).append("similarLab", e.exercise.id());
            } catch (RuntimeException e) {
                out.append("similar", null).append("similarError", e.getMessage());
            }
            return out;
        });

        route("POST", "/api/indexes/(\\w+)", true, (ex, m, q) -> indexes.create(m.group(1)));
        route("DELETE", "/api/indexes/(\\w+)", true, (ex, m, q) -> indexes.drop(m.group(1)));
        get("/api/indexes/(\\w+)/definition", false, (ex, m, q) -> indexes.createCommand(SearchIndexes.spec(m.group(1))));

        route("POST", "/api/chat", true, (ex, m, q) -> {
            Document body = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sid = body.getString("sessionId");
            String message = body.getString("message");
            if (sid == null || !sid.matches("[\\w-]{6,64}")) return new Status(400, new Document("error", "sessionId required"));
            if (message == null || message.isBlank()) return new Status(400, new Document("error", "message required"));
            Map<String, String> f = new HashMap<>();
            Document filters = body.get("filters", Document.class);
            if (filters != null) filters.forEach((k, v) -> f.put(k, String.valueOf(v)));
            return concierge.ask(sid, message.trim().substring(0, Math.min(500, message.trim().length())), SearchRequest.from(f));
        });
        get("/api/chat/([\\w-]+)", true, (ex, m, q) -> new Document("messages", concierge.history(m.group(1))));
        route("DELETE", "/api/chat/([\\w-]+)", true, (ex, m, q) -> new Document("deleted", memory.forget(m.group(1))));

        get("/api/ladder", true, (ex, m, q) -> new Document("rungs", Ladder.run(versions, pool)));

        // "Solve it for me": write the reference solution into the source file (or put the TODO back).
        // Labs go in order: ?through=true first solves the earlier required exercises ("catch me up").
        route("POST", "/api/labs/([\\d.]+)/solve", false, (ex, m, q) -> solver.solve(m.group(1), "true".equals(q.get("through"))));
        route("POST", "/api/labs/([\\d.]+)/reset", false, (ex, m, q) -> solver.reset(m.group(1)));
        route("POST", "/api/labs/reset-all", false, (ex, m, q) -> solver.resetAll());
        get("/api/labs/([\\d.]+)/code", false, (ex, m, q) -> solver.code(m.group(1)));
        get("/api/labs/([\\d.]+)/sample", false, (ex, m, q) -> Samples.forViewer(m.group(1)));
    }

    private Document runSafely(HotelSearch s, SearchRequest r) {
        try {
            return s.search(r).toDocument();
        } catch (Lab.NotImplemented e) {
            return new Document("todo", labDoc(e.exercise));
        } catch (RuntimeException e) {
            return new Document("error", String.valueOf(e.getMessage()));
        }
    }

    private Document status() {
        Document st = new Document("ready", bootstrap.ready)
                .append("phase", bootstrap.phase)
                .append("message", bootstrap.message)
                .append("errors", bootstrap.errors)
                .append("attendee", config.attendee)
                .append("database", config.database)
                .append("cluster", config.clusterHost())
                .append("llm", llm.label())
                .append("embedModel", config.embedModel)
                .append("labs", labs())
                .append("solver", solver.available())
                .append("codeViewer", solver.hasSolutions());
        if (bootstrap.ready) {
            Document idx = new Document();
            indexes.status().forEach(idx::append);
            st.append("indexes", idx)
                    .append("counts", new Document("hotels", db.hotels().estimatedDocumentCount())
                            .append("chat_history", db.chat().estimatedDocumentCount()));
        }
        return st;
    }

    /** Which exercises are done. Each probe calls the exercise with dummy input; dependencies are null on purpose. */
    private List<Document> labs() {
        SearchRequest sample = new SearchRequest("x", "Istanbul", "Resort", 50, 300, 3, List.of("Pool"), "", 0, 5, false);
        Map<String, Runnable> probes = new LinkedHashMap<>();
        probes.put("1.1", () -> new RegexSearch(null).textFilter("x"));
        probes.put("1.2", () -> new RegexSearch(null).structuredFilter(sample));
        probes.put("2.2", () -> new AtlasTextSearch(null, null, null).textOperator("x"));
        probes.put("2.3", () -> new AtlasTextSearch(null, null, null).filterClauses(sample));
        probes.put("2.4", () -> new AtlasTextSearch(null, null, null).facetDefinitions());
        probes.put("2.5", () -> new AtlasTextSearch(null, null, null).autocompletePipeline("x"));
        probes.put("3.2", () -> new SemanticSearch(null, null, null).vectorSearchStage("x", new Document(), 1));
        probes.put("3.3", () -> new SemanticSearch(null, null, null).vectorFilter(sample));
        probes.put("3.4", () -> new SemanticSearch(null, null, new AtlasTextSearch(null, null, null)).hybridPipeline(sample, 1));
        probes.put("4.1", () -> new Concierge(null, null, null, null).retrieve("x", List.of(), new Document(), new ArrayList<>()));
        probes.put("4.2", () -> new Concierge(null, null, null, null).buildMessages("x", List.of(), List.of()));
        probes.put("4.3", () -> new ChatMemory(null).recall("x", 1));
        List<Document> out = new ArrayList<>();
        for (Lab.Exercise e : Lab.all()) {
            Runnable p = probes.get(e.id());
            boolean done = p == null ? !Lab.seenTodo(e.id()) : Lab.probe(e.id(), p);
            Document d = labDoc(e).append("done", done);
            // "written" while not done means: the code is in the file, but this app was built before it
            if (solver.available()) d.append("source", solver.sourceState(e.id()));
            out.add(d);
        }
        return out;
    }

    static Document labDoc(Lab.Exercise e) {
        return new Document("id", e.id()).append("version", e.version()).append("title", e.title())
                .append("where", e.where()).append("hint", e.hint());
    }

    // ─────────────────────────────────────────────────────────────── HTTP plumbing

    record Status(int code, Document body) {}

    private void get(String path, boolean needsReady, Handler h) {
        route("GET", path, needsReady, h);
    }

    private void route(String method, String path, boolean needsReady, Handler h) {
        routes.add(new Route(method, Pattern.compile("^" + path + "$"), h, needsReady));
    }

    private void serve(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/")) {
                for (Route r : routes) {
                    Matcher m = r.path().matcher(path);
                    if (!m.matches() || !r.method().equals(ex.getRequestMethod())) continue;
                    if (r.needsReady() && !bootstrap.ready) {
                        send(ex, 503, new Document("error", bootstrap.message).append("phase", bootstrap.phase));
                        return;
                    }
                    Object out = r.handler().handle(ex, m, query(ex));
                    if (out instanceof Status s) send(ex, s.code(), s.body());
                    else send(ex, 200, (Document) out);
                    return;
                }
                send(ex, 404, new Document("error", "Not found: " + path));
                return;
            }
            serveStatic(ex, path);
        } catch (Lab.NotImplemented e) {
            send(ex, 501, new Document("error", e.getMessage()).append("lab", labDoc(e.exercise)));
        } catch (IllegalArgumentException e) {
            send(ex, 400, new Document("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, new Document("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml", "json", "application/json", "ico", "image/x-icon", "png", "image/png");

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) {
            send(ex, 403, new Document("error", "Forbidden"));
            return;
        }
        InputStream found = App.class.getResourceAsStream("/static" + path);
        byte[] bytes;
        try (InputStream in = found != null ? found : App.class.getResourceAsStream("/static/index.html")) {
            bytes = in.readAllBytes();
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        ex.getResponseHeaders().set("content-type", MIME.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("cache-control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void send(HttpExchange ex, int code, Document body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("cache-control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
