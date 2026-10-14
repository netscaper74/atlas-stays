package io.jug.stays.rag;

import io.jug.stays.Config;
import org.bson.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal LLM client with no SDK: one HTTP POST.
 *
 *  - openai:    any OpenAI-compatible /chat/completions endpoint (OpenAI, Azure OpenAI,
 *               a workshop proxy, Ollama, vLLM, LiteLLM...). LLM_BASE_URL + LLM_API_KEY.
 *  - anthropic: the Anthropic Messages API. ANTHROPIC_API_KEY.
 *
 * With no key at all the concierge still retrieves and shows hotels, without generating prose.
 */
public final class Llm {

    public record Message(String role, String content) {}

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Llm(Config config) {
        this.config = config;
    }

    public String provider() {
        String p = config.llmProvider;
        if (p.equals("none")) return null;
        if (p.equals("openai")) return config.llmApiKey.isBlank() ? null : "openai";
        if (p.equals("anthropic")) return config.anthropicKey.isBlank() ? null : "anthropic";
        if (!config.llmApiKey.isBlank()) return "openai";
        if (!config.anthropicKey.isBlank()) return "anthropic";
        return null;
    }

    public String model() {
        String p = provider();
        if (p == null) return null;
        if (!config.llmModel.isBlank()) return config.llmModel;
        return p.equals("openai") ? "gpt-4.1-mini" : "claude-haiku-5-5";
    }

    public boolean enabled() {
        return provider() != null;
    }

    public String label() {
        return enabled() ? provider() + ":" + model() : null;
    }

    /** One completion. {@code messages} alternate user/assistant and end with a user message. */
    public String chat(String system, List<Message> messages, int maxTokens) {
        String p = provider();
        if (p == null) throw new IllegalStateException("No LLM configured");
        try {
            return p.equals("openai") ? openai(system, messages, maxTokens) : anthropic(system, messages, maxTokens);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String openai(String system, List<Message> messages, int maxTokens) throws Exception {
        List<Document> msgs = new ArrayList<>();
        msgs.add(new Document("role", "system").append("content", system));
        for (Message m : messages) msgs.add(new Document("role", m.role()).append("content", m.content()));
        Document body = new Document("model", model()).append("messages", msgs);
        // OpenAI's newer models want max_completion_tokens; most compatible servers still read max_tokens.
        body.append(config.llmBaseUrl.contains("api.openai.com") ? "max_completion_tokens" : "max_tokens", maxTokens);

        Document res = post(config.llmBaseUrl + "/chat/completions", body,
                "authorization", "Bearer " + config.llmApiKey);
        List<Document> choices = res.getList("choices", Document.class);
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("LLM returned no choices: " + res.toJson());
        return choices.get(0).get("message", Document.class).getString("content").trim();
    }

    private String anthropic(String system, List<Message> messages, int maxTokens) throws Exception {
        // The Messages API needs strictly alternating roles, starting with "user".
        List<Document> msgs = new ArrayList<>();
        for (Message m : messages) {
            if (!msgs.isEmpty() && msgs.get(msgs.size() - 1).getString("role").equals(m.role())) {
                Document last = msgs.get(msgs.size() - 1);
                last.put("content", last.getString("content") + "\n\n" + m.content());
            } else if (msgs.isEmpty() && m.role().equals("assistant")) {
                continue;
            } else {
                msgs.add(new Document("role", m.role()).append("content", m.content()));
            }
        }
        Document body = new Document("model", model()).append("max_tokens", maxTokens).append("system", system).append("messages", msgs);
        Document res = post("https://api.anthropic.com/v1/messages", body,
                "x-api-key", config.anthropicKey, "anthropic-version", "2023-06-01");
        for (Document c : res.getList("content", Document.class)) {
            if ("text".equals(c.getString("type"))) return c.getString("text").trim();
        }
        throw new IllegalStateException("LLM returned no text: " + res.toJson());
    }

    private Document post(String url, Document body, String... headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJson()));
        for (int i = 0; i < headers.length; i += 2) req.header(headers[i], headers[i + 1]);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String text = res.body();
            throw new IllegalStateException("LLM HTTP " + res.statusCode() + ": " + text.substring(0, Math.min(300, text.length())));
        }
        return Document.parse(res.body());
    }
}
