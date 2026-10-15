package io.jug.stays;

import io.jug.stays.rag.Concierge;
import io.jug.stays.rag.Llm;
import io.jug.stays.search.AtlasTextSearch;
import io.jug.stays.search.RegexSearch;
import io.jug.stays.search.SearchRequest;
import io.jug.stays.search.SemanticSearch;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast checks for each exercise. No database needed.
 *
 *     mvn test                         # which labs are done?
 *     mvn test -Dtest='LabTest#lab2*'  # just lab 2
 *
 * A test that fails with "LAB x.y is not implemented yet" is simply still to do.
 */
class LabTest {

    static final SearchRequest SAMPLE = new SearchRequest("pool", "Istanbul", "Boutique Hotel", 50, 300, 4,
            List.of("Pool", "Spa"), "", 0, 24, false);
    static final SearchRequest EMPTY = SearchRequest.of("pool");

    // ---------------------------------------------------------------- v1

    @Test
    @DisplayName("Lab 1.1: regex over every field, case-insensitive, input escaped")
    void lab1_1() {
        String json = Json.toDocument(new RegexSearch(null).textFilter("bosphorus (view")).toJson();
        assertTrue(json.contains("$or"), "use $or over the fields: " + json);
        for (String f : List.of("name", "type", "district", "city", "description", "amenities")) {
            assertTrue(json.contains("\"" + f + "\""), "missing field " + f + ": " + json);
        }
        assertTrue(json.contains("\"options\": \"i\""), "the match should ignore case: " + json);
        assertTrue(json.contains("bosphorus \\\\(view"), "escape regex characters such as '(': " + json);
    }

    @Test
    @DisplayName("Lab 1.2: structured filters as MQL")
    void lab1_2() {
        RegexSearch v1 = new RegexSearch(null);
        String json = Json.toDocument(v1.structuredFilter(SAMPLE)).toJson();
        assertTrue(json.contains("\"city\": \"Istanbul\""), json);
        assertTrue(json.contains("\"type\": \"Boutique Hotel\""), json);
        assertTrue(json.contains("\"$gte\": 50") && json.contains("\"$lte\": 300"), "price range: " + json);
        assertTrue(json.contains("\"stars\": {\"$gte\": 4}"), json);
        assertTrue(json.contains("\"$all\""), "every selected amenity must be present: " + json);
        assertEquals(null, v1.structuredFilter(EMPTY), "no filters: return null");
    }

    // ---------------------------------------------------------------- v2

    @Test
    @DisplayName("Lab 2.2: compound.should with boosts, autocomplete and fuzzy")
    void lab2_2() {
        Document op = new AtlasTextSearch(null, null, null).textOperator("bosphorus vew");
        Document compound = op.get("compound", Document.class);
        assertNotNull(compound, "return { compound: { should: [...] } }");
        List<Document> should = compound.getList("should", Document.class);
        assertTrue(should.size() >= 4, "several should clauses");
        assertEquals(1, compound.getInteger("minimumShouldMatch"));
        String json = op.toJson();
        assertTrue(json.contains("\"autocomplete\""), "add an autocomplete clause on name");
        assertTrue(json.contains("\"fuzzy\""), "add fuzzy clauses for typos");
        assertTrue(json.contains("\"boost\""), "boost the important fields");
        assertTrue(json.contains("\"district\""), "search the district too");
        assertTrue(json.contains("\"multi\": \"tr\""), "search the Turkish analyzer of description");
    }

    @Test
    @DisplayName("Lab 2.3: filters as equals and range clauses")
    void lab2_3() {
        List<Document> clauses = new AtlasTextSearch(null, null, null).filterClauses(SAMPLE);
        String json = new Document("c", clauses).toJson();
        assertTrue(json.contains("\"equals\": {\"path\": \"city\", \"value\": \"Istanbul\"}"), json);
        assertTrue(json.contains("\"path\": \"pricePerNight\", \"gte\": 50, \"lte\": 300"), json);
        assertTrue(json.contains("\"path\": \"stars\", \"gte\": 4"), json);
        assertTrue(json.contains("\"value\": \"Pool\"") && json.contains("\"value\": \"Spa\""), "one equals per amenity: " + json);
        assertTrue(new AtlasTextSearch(null, null, null).filterClauses(EMPTY).isEmpty());
    }

    @Test
    @DisplayName("Lab 2.4: facet definitions")
    void lab2_4() {
        Document f = new AtlasTextSearch(null, null, null).facetDefinitions();
        for (String k : List.of("city", "type", "amenities", "stars", "price")) assertTrue(f.containsKey(k), "missing facet " + k);
        assertEquals("string", f.get("city", Document.class).getString("type"));
        assertEquals("number", f.get("price", Document.class).getString("type"));
        assertEquals("pricePerNight", f.get("price", Document.class).getString("path"));
        assertNotNull(f.get("price", Document.class).get("boundaries"), "number facets need boundaries");
    }

    @Test
    @DisplayName("Lab 2.5: autocomplete pipeline")
    void lab2_5() {
        List<Document> p = new AtlasTextSearch(null, null, null).autocompletePipeline("kad");
        Document search = p.get(0).get("$search", Document.class);
        assertNotNull(search, "first stage must be $search");
        assertEquals("hotels_search", search.getString("index"));
        assertEquals("name", search.get("autocomplete", Document.class).getString("path"));
    }

    // ---------------------------------------------------------------- v3

    @Test
    @DisplayName("Lab 3.2: $vectorSearch with automated embedding")
    void lab3_2() {
        Document filter = new Document("city", "Istanbul");
        Document vs = new SemanticSearch(null, null, null).vectorSearchStage("quiet hotel", filter, 10).get("$vectorSearch", Document.class);
        assertNotNull(vs, "return { $vectorSearch: {...} }");
        assertEquals("hotels_vector", vs.getString("index"));
        assertEquals("profile", vs.getString("path"));
        Object q = vs.get("query");
        assertTrue(q instanceof Document d ? "quiet hotel".equals(d.getString("text")) : "quiet hotel".equals(q), "query: { text: ... }");
        assertFalse(vs.containsKey("queryVector"), "no queryVector: Atlas embeds the text for you");
        assertEquals(10, vs.getInteger("limit"));
        assertTrue(vs.getInteger("numCandidates") >= 100, "numCandidates well above limit");
        assertEquals(filter, vs.get("filter"));
    }

    @Test
    @DisplayName("Lab 3.3: pre-filter in MQL")
    void lab3_3() {
        SemanticSearch v3 = new SemanticSearch(null, null, null);
        String json = v3.vectorFilter(SAMPLE).toJson();
        assertTrue(json.contains("\"city\": \"Istanbul\""), json);
        assertTrue(json.contains("\"$gte\": 50") && json.contains("\"$lte\": 300"), json);
        assertTrue(json.contains("\"stars\": {\"$gte\": 4}"), json);
        assertTrue(json.contains("\"amenities\": \"Pool\"") && json.contains("\"amenities\": \"Spa\""), json);
        assertTrue(v3.vectorFilter(EMPTY).isEmpty(), "no filters: return an empty document");
    }

    @Test
    @DisplayName("Lab 3.4: $rankFusion of vector and text")
    void lab3_4() {
        List<Document> p = new SemanticSearch(null, null, new AtlasTextSearch(null, null, null)).hybridPipeline(SAMPLE, 5);
        Document rf = p.get(0).get("$rankFusion", Document.class);
        assertNotNull(rf, "first stage must be $rankFusion");
        Document pipelines = rf.get("input", Document.class).get("pipelines", Document.class);
        assertEquals(2, pipelines.size(), "two input pipelines");
        String json = pipelines.toJson();
        assertTrue(json.contains("$vectorSearch") && json.contains("$search"), json);
    }

    // ---------------------------------------------------------------- RAG

    @Test
    @DisplayName("Lab 4.2: the prompt has history, context and question")
    void lab4_2() {
        Document hotel = new Document("name", "Moda Lantern Hotel").append("type", "Boutique Hotel").append("district", "Kadıköy")
                .append("city", "Istanbul").append("stars", 4).append("rating", 9.1).append("pricePerNight", 140)
                .append("amenities", List.of("Free WiFi")).append("tags", List.of("quiet")).append("description", "Rooms face a courtyard.")
                .append("reviews", List.of(new Document("text", "So peaceful").append("rating", 10)));
        List<Document> history = List.of(new Document("role", "user").append("content", "Hi"),
                new Document("role", "assistant").append("content", "Hello! Where to?"));
        List<Llm.Message> msgs = new Concierge(null, null, null, null).buildMessages("Something quiet in Kadıköy?", List.of(hotel), history);
        assertEquals(3, msgs.size(), "history (2) + the new question (1)");
        assertEquals("user", msgs.get(0).role());
        assertEquals("assistant", msgs.get(1).role());
        Llm.Message last = msgs.get(2);
        assertEquals("user", last.role());
        assertTrue(last.content().contains("Moda Lantern Hotel"), "the context lists the retrieved hotels");
        assertTrue(last.content().contains("Something quiet in Kadıköy?"), "the question is in the last message");
    }

    // ---------------------------------------------------------------- data

    @Test
    @DisplayName("Data: hotels.json loads and every hotel has a profile to embed")
    void data() {
        List<Document> hotels = Bootstrap.loadHotels();
        assertTrue(hotels.size() > 200);
        for (Document h : hotels) {
            assertNotNull(h.getString("profile"), h.getString("_id"));
            assertTrue(h.getInteger("pricePerNight") > 0);
        }
    }
}
