# Instructor guide

## The shape of the session (3 hours)

| Time | Block | Slides from the existing deck |
|---|---|---|
| 0:00–0:20 | Why search is hard. Show the query ladder, ask the room to predict v1/v2/v3 | Agenda, "What is RAG?" moved later; new ladder slide |
| 0:20–0:30 | **Lab 0** setup: `.env`, run, create both indexes | Step 1 slide (passkey becomes the connection string + LLM key) |
| 0:30–0:55 | **Lab 1** regex, then break it live with the ladder | New |
| 0:55–1:45 | **Lab 2** Atlas Search: analyzers, compound, fuzzy, facets, autocomplete | New |
| 1:45–1:50 | Break | |
| 1:50–2:20 | **Lab 3** Vector Search with automated embedding, hybrid `$rankFusion` | Steps 5–6, "Three ideas", `$vectorSearch` anatomy (now `query.text`, no `queryVector`) |
| 2:20–2:50 | **Lab 4** RAG concierge and memory | Steps 7–8, "the memory moment" |
| 2:50–3:00 | Scorecard on screen, "layers not replacements", Q&A | Q&A |

The deck's chunking/embedding steps (2–4) mostly disappear: automated embedding does them inside Atlas. Keep one slide that says so; it's the "aha" for people who've written embedding pipelines before.

For a 2-hour slot: every exercise is on the path, so instead of dropping any, use **Solve it for me** live for 2.4, 2.5 and 3.4 (narrate the code), and before Lab 4 have the room use **Catch up to here** on 4.2 (it solves everything up to and including 4.2), so they only write the memory (4.3).

## Atlas setup (a week before)

1. **Project and cluster.** One dedicated cluster for everyone, **M10 or larger**. Free and Flex tiers cap search indexes per cluster (3 and 10), and each attendee creates 2. With 40 attendees that's 80 small indexes. We suggest **M30**, or M20 plus Search Nodes, and a load test during the dry run. Pick a MongoDB **8.1+** release if you want `$rankFusion` (Lab 3.4). On 8.0 the app fuses the two lists in Java and says so.
2. **Storage auto-scaling ON.** Automated embedding requires it on dedicated clusters: if the disk fills, embedding pauses and the index goes stale.
3. **Automated Embedding** is a Preview feature. Confirm the project can create an `autoEmbed` index: run the app once with `AUTO_CREATE_INDEXES=true` and wait for `hotels_vector` to be READY. Check **Search & Vector Search → Automated Embedding → Rate Limits**. Each attendee's index embeds 255 profiles of about 300 tokens (≈ 75k tokens, a few cents with `voyage-4`). The risk is not cost but tokens per minute when 40 indexes start at once: Atlas queues and retries, so a slow start is normal. Ask MongoDB support for a temporary limit increase if needed, or pre-create the vector indexes the evening before (see below).
4. **Network access.** Add the venue's egress IP, or `0.0.0.0/0` with an expiry for the day.
5. **Users and databases.** One database user per attendee, with `readWrite` on their own database only (`stays_<slug>`). `readWrite` granted on a whole database includes the search-index actions (`createSearchIndexes`, `listSearchIndexes`, …). Grant it on the database, not on single collections.

```bash
# attendees.txt: one name per line ("Ayşe Yılmaz" becomes stays_ayse_yilmaz)
export PROJECT_ID=<atlas project id> CLUSTER_HOST=<cluster>.xxxxx.mongodb.net
# optional: put the LLM settings into every attendee's .env
export LLM_BASE_URL=https://<workshop-proxy>/v1 LLM_API_KEY=<key> LLM_MODEL=gpt-4.1-mini
./scripts/provision.sh attendees.txt          # writes attendees.csv and envs/<slug>.env, users expire in 3 days
```

Hand each attendee their `envs/<slug>.env` (it becomes their `.env`).

**Optional: pre-build everything the night before.** For each attendee, run the solution once with their env file and `AUTO_CREATE_INDEXES=true`, for example `cp envs/<slug>.env .env && echo AUTO_CREATE_INDEXES=true >> .env && docker compose up --build`. That seeds the data and creates both indexes. Attendees then still read and explain the definitions in Labs 2.1 and 3.1, but nobody waits on index builds.

## Dry run checklist

- [ ] `docker compose up --build` on the **solution** branch with one attendee's `.env`: status card clears, Setup shows both indexes READY.
- [ ] `docker compose run --rm ladder` (or `mvn test -Dtest=AtlasIntegrationTest`) prints the real ladder scorecard. Put that table on your wrap-up slide; it's computed from real results, so check it before claiming any column wins.
- [ ] Concierge: the three AI chips in order; the third ("What did I just ask you?") must answer correctly with memory on.
- [ ] Hybrid toggle on v3 works (or shows the 8.0 fallback note).
- [ ] On the **main** (starter) branch: the app starts, v1 shows the Lab 1.1 card, and `mvn test` fails only with "LAB x.y is not implemented yet".
- [ ] Wi-Fi plan: the first build pulls two images (`maven:3.9.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`) and the Maven dependencies. Ask attendees to clone the repo and run `docker compose build` (or `mvn -q dependency:go-offline`) **before** the event.
- [ ] With `docker compose watch`, saving a Java file rebuilds the image. Time it on a typical laptop; if it's slow, Maven users iterate faster.

## LLM

The concierge speaks to any OpenAI-compatible `/chat/completions` endpoint (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`), or to Anthropic with `ANTHROPIC_API_KEY`. If the deck's passkey proxy is OpenAI-compatible, point `LLM_BASE_URL` at it. With no key the concierge still retrieves and lists hotels, which is enough to teach retrieval and memory.

## Guided lab steps and Solve-it

The **Setup** panel opens on **Lab steps**. Step 0 (create both search indexes) is always listed first: **Start here** with a create button while an index is missing, **Building…** until both are queryable, then **Done**. Nothing creates them automatically unless `AUTO_CREATE_INDEXES=true`, so if you pre-built them, attendees simply see step 0 as Done. Then comes one **Next step** card at a time, in lab order, with the file, method and hint. Later exercises are locked until every earlier one has code: all 12 exercises are on the path, including 2.5 (autocomplete) and 3.4 (hybrid). The server enforces the same order.

* **Solve it for me** (two clicks) writes the reference solution between the exercise's `// ▶ LAB` markers in the attendee's file; `docker compose watch` rebuilds the app.
* **Before / after** on every step opens a wide code viewer: the starter method (the `throw Lab.todo` in red) next to the reference solution (in green), or the attendee's own file next to the solution. Below that, **Generated query** shows the MongoDB query the method builds for a fixed sample input: the reference (from `src/main/resources/labs/samples.json`) next to what the running app generates (computed live by `Samples.java`; 4.1 and 4.3 run against a recording database, so nothing is sent to Atlas). Use ← → to walk the room through the labs from the projector, without touching anyone's code.
* After changing a solution, regenerate the reference samples on the `solution` branch: `mvn -q compile exec:java -Dexec.mainClass=io.jug.stays.Samples` (or run `io.jug.stays.Samples` from your IDE), then regenerate the starter.
* **Catch up to here** on a locked exercise solves every step before it, in order. Use it for latecomers, or for the whole room before Lab 4 in the 2-hour format.
* **Undo** works newest first. **Reset all labs** puts every TODO back: handy for your demo machine.
* Running the session as a live demo: solve step by step and narrate the diff in your editor.

The reference code lives in `src/main/resources/labs/solutions.json`, generated by `tools/make_starter.py`. The server only writes inside `SOURCE_DIR` (default `src/main/java`; compose mounts `./src` for it). On Linux, run compose with your user id so the container can write your files: `UID=$(id -u) docker compose watch`. **Turn it off for any public deployment** with `LAB_SOLVER=false`.

## Repository layout

* `solution` branch: complete app.
* `main` branch: starter, generated with `python3 tools/make_starter.py` (the code between every `// ▶ LAB` … `// ◀ LAB` pair becomes `throw Lab.todo(...)` and is saved to `labs/solutions.json` for the Solve-it buttons). After changing the solution, regenerate the starter rather than editing it by hand.

## After the workshop

```bash
ADMIN_URI='mongodb+srv://admin:...@<cluster>/' ./scripts/cleanup.sh attendees.csv   # deletes users and drops stays_* databases
```
