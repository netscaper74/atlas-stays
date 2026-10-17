# Lab guide

Four labs and about 2 hours of hands-on time. Each exercise is one small method marked `// LAB x.y` that currently does `throw Lab.todo("x.y")`. The app keeps running while exercises are open: the UI shows which one a feature is waiting for, and **Setup** (top right) lists them all.

**After each change:** with `docker compose watch` running, just save the file: the image rebuilds and the app restarts (usually under a minute); reload the page. With Maven, stop the app (`Ctrl+C`), run `mvn -q compile exec:java` again, and reload. `mvn test` checks every exercise without a database.

**Follow the steps in Setup.** The **Setup** panel (top right, with your progress) opens on **Lab steps**: a **Next step** card with the exercise to do now, where to write it and a hint. Exercises go in order; later ones stay **Locked** until the earlier ones have code. Stuck or short on time? **Solve it for me** (click twice) writes the reference solution between the exercise's markers in your file so you can read it; with `docker compose watch` the app rebuilds by itself, with Maven restart it. Want to see the change first? **Before / after** (on the Next step card and on every step) shows the method as the starter has it next to the reference solution, and next to your own file once you've written something; Under the code, **Generated query** runs the method on a sample input (for example `textOperator("bosphorus vew")`) and shows the MongoDB query it builds: the reference solution's on the right, your running app's on the left, with a chip that says whether they match. For 4.1 and 4.3, which talk to the database, it shows the `aggregate`, `find` and `insertOne` calls your code makes. ← → steps through the labs. Arrived late? Hover a locked exercise and use **Catch up to here**: it solves every required step before it, in order. **Done → Undo** works newest first and puts the `throw Lab.todo(...)` back (it replaces whatever is between the markers). On the command line, `git diff main solution -- <file>` shows the answer for one file.

---

## Lab 0 · Set up (10 min)

1. `cp .env.example .env`, then paste your `MONGODB_URI` and set `ATTENDEE` to the name your instructor used (for example `ayse_yilmaz`).
2. `docker compose watch` (or `mvn -q compile exec:java`), then open http://localhost:8080. The first Docker build downloads Maven and the driver, so give it a couple of minutes.
3. Wait for the status card to disappear. The app has loaded 255 hotels into **your** database, `stays_<ATTENDEE>`.
4. **Create both search indexes now.** Open **Setup**: step 0 is **Create both indexes**. They build in the background: the vector index has to embed every hotel, so starting it now means it is READY by Lab 3. You'll read both definitions later.
5. Open the **Atlas** button (bottom right). Every query the page runs appears there.

Before you go on, look at one hotel document in Atlas Data Explorer or Compass. Note the `profile` field: it's the text the vector index will embed.

---

## Lab 1 · v1, regex only (25 min)

File: `search/RegexSearch.java`

### 1.1 Match the text: `textFilter(String q)`
Return a filter where the text appears, ignoring case, in **any** of `FIELDS`: name, type, district, city, description, amenities.

* `Filters.regex(field, Pattern)` and `Filters.or(...)`.
* **Escape the input first** (`escapeRegex` is provided). Try searching `(` before and after.

### 1.2 Side-panel filters: `structuredFilter(SearchRequest r)`
city and type with `$eq`, pricePerNight between min and max, stars `$gte`, amenities `$all`. Return `null` when there's nothing to filter.

### 1.3 Look at what the server did
Search `pool`, then open the Atlas Inspector and read the `explain` entry: **COLLSCAN**, every document examined. Now search:

| Query | What happens and why |
|---|---|
| `pool` | Works. Results come back in insertion order: there is no score. |
| `kadikoy` | Nothing. The data says *Kadıköy*. |
| `bosphorus vew` | Nothing: one typo. |
| `pet` | Also finds *rose **pet**als*. |
| `quiet place to work remotely` | Nothing: no document contains that exact phrase. |

Scroll down to **The query ladder** and click **Run the ladder**. The v1 column is your baseline.

> Question for the room: a regex index scan works for `^prefix` queries. Why doesn't that help here?

---

## Lab 2 · v2, Atlas Search (50 min)

File: `search/AtlasTextSearch.java`. Index: `src/main/resources/indexes/hotels_search.json`

### 2.1 Read the index definition
Open the JSON (or **Setup → Show definition**). Notice:
* a custom analyzer `folding` with `icuFolding`: "Kadıköy", "KADIKÖY" and "kadikoy" all become `kadikoy`
* `description` analysed in English, with a `multi` sub-field analysed in **Turkish**
* `name` indexed twice: as text and as `autocomplete`
* `token` types on city, type and amenities, for facets and exact filters
* `"dynamic": false`, so only what you list is indexed

Check that `hotels_search` shows **READY** in Setup.

### 2.2 The text query: `textOperator(String q)`
Return a `compound` with several `should` clauses, each boosted differently:

| Clause | Path | Boost |
|---|---|---|
| `text` | name, district, city, type | 6 |
| `text` | amenities, tags | 3 |
| `text` | `description` and `{ value: "description", multi: "tr" }` | 1 |
| `autocomplete` | name | 2 |
| `text` + `fuzzy` (one clause per word of 3+ letters) | name, district, city, type, amenities, tags | 1 |

`minimumShouldMatch: 1`. For fuzzy: `maxEdits` 1, or 2 for words of 7+ letters, and `prefixLength` 1.

> Why one fuzzy clause per word? Atlas applies a single `maxEdits` to every term in the query, so "bir" with 2 edits would match half the catalogue.

### 2.3 Filters: `filterClauses(SearchRequest r)`
`equals` for city, type and each amenity; `range` for pricePerNight and stars. The plumbing puts them in `compound.filter`: they include or exclude documents without changing the score.

### 2.4 Facets: `facetDefinitions()`
`string` facets for city, type and amenities; `number` facets with `boundaries` for stars and pricePerNight. The UI uses them for the counts on the city bar, the type list, the amenity chips and the price histogram.

> The plumbing runs one `$searchMeta` per facet group, each without its own filter (disjunctive faceting), so picking *Istanbul* still shows how many hotels Antalya has. Look at it in the Inspector.

> v3 reuses these facets. A vector search ranks every hotel, so there is no match set to count; instead the counts show how many stays the pre-filter leaves for `$vectorSearch` to choose from (the same filters, without the text). Atlas Search counts, Vector Search ranks.

### 2.5 Search-as-you-type: `autocompletePipeline(String q)`
`$search` with the `autocomplete` operator on `name` (fuzzy `maxEdits` 1), `$limit` 6, `$project` a few fields. Type `kadi` in the header.

**Check:** rerun the ladder. Rungs 2 to 4 should now pass. Then try `quiet place to work remotely with fast wifi`, and look at which words matched.

---

## Lab 3 · v3, Vector Search with automated embedding (30 min)

File: `search/SemanticSearch.java`. Index: `src/main/resources/indexes/hotels_vector.json`

### 3.1 Read the index definition

```json
{ "type": "autoEmbed", "modality": "text", "path": "profile", "model": "voyage-4" }
```

That's the whole embedding pipeline. Atlas sends every hotel's `profile` to Voyage AI when it indexes it, re-embeds when the field changes, and embeds your query text at query time with the same model. No embedding code, no vectors in your documents, no dimensions to match. The other fields are `filter` types for pre-filtering.

### 3.2 `vectorSearchStage(String q, Document filter, int limit)`

```json
{ "$vectorSearch": {
    "index": "hotels_vector", "path": "profile",
    "query": { "text": "<q>" },
    "numCandidates": 150, "limit": 10, "filter": { } } }
```

Keep `numCandidates` 10 to 20 times `limit`. Leave out `filter` when it's empty.

### 3.3 Pre-filter: `vectorFilter(SearchRequest r)`
The same filters as v1, as MQL, but every field must be indexed as `filter`. Combine with `$and`. Return an empty `Document` when there's nothing to filter.

Now try, in v3:
* `quiet place to work remotely with fast wifi`
* `denize sıfır romantik bir otel` (Turkish query, mostly English descriptions)
* `travelling with kids and a dog`
* open any hotel: **Similar stays** uses the hotel's own profile as the query

### 3.4 Hybrid search: `hybridPipeline(SearchRequest r, int limit)`
`$rankFusion` with two input pipelines, `vector` (your `$vectorSearch`) and `text` (`$search` with `text.operator(r)`), merged by reciprocal rank fusion. Turn on **Hybrid** above the results. Each card then shows which pipeline found it.

**Check:** run the ladder again, and compare all three on one question with **Compare all three**.

---

## Lab 4 · The concierge: RAG with memory (30 min)

Files: `rag/Concierge.java`, `rag/ChatMemory.java`

The concierge opens from the **bell button in the bottom-left corner** of the app; it stays there while you scroll. A red dot means an answer arrived while the chat was closed.

```
question ─▶ recall history ─▶ retrieve ($vectorSearch) ─▶ build prompt ─▶ LLM ─▶ remember
```

### 4.1 Retrieve: `Concierge.retrieve(...)`
Vector-search the question with `semantic.retrieve(text, filter, TOP_K, debug)`. For follow-ups ("which of those has a pool?"), search the **previous question + this one**, and keep the hotels from the previous answer (their ids are in the last assistant message's `hotelIds`).

### 4.2 Build the prompt: `Concierge.buildMessages(...)`
The history messages first, then one user message containing a `CONTEXT` block (one line per hotel, `describe(h)` is provided) followed by `QUESTION: ...`. The system prompt `SYSTEM` is provided: read its rules.

Ask: *A romantic hotel on the beach in Kaş or Kalkan*, then *Which of those has the best breakfast?*, then *What did I just ask you?*

### 4.3 Memory: `ChatMemory.remember(...)` and `recall(...)`
* `remember`: `insertOne` with `sessionId`, `role`, `content`, `hotelIds`, `createdAt`.
* `recall`: the last N messages of a session, oldest first (sort by `createdAt` descending, limit N, then reverse).

Ask *What did I just ask you?* again. Then look at `chat_history` in Data Explorer, and at the indexes `Bootstrap` created on it: `{ sessionId: 1, createdAt: 1 }`, plus a TTL index that deletes conversations after 3 days.

---

## Wrap-up: the scorecard

Run the ladder one last time. Typical shape:

| | v1 regex | v2 Atlas Search | v3 vector |
|---|---|---|---|
| exact words | ✓ | ✓ | ✓ |
| diacritics, typos, substrings | ✕ | ✓ | mostly ✓ |
| intent, mood, another language | ✕ | partial | ✓ |
| exact names, codes, filters and counts | some | ✓ | filters only |

They are **layers, not replacements**. Keep v1-style queries for admin lookups, use v2 for exact and faceted browsing, v3 for intent, combine v2 and v3 with `$rankFusion`, and put the LLM on top of retrieval, never instead of it.
