package io.jug.stays.rag;

import io.jug.stays.Db;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Conversation memory in MongoDB: one document per message in the chat_history
 * collection, read back by session. The model itself remembers nothing; the
 * app replays the history on every call.
 *
 * Indexes (created at startup): { sessionId: 1, createdAt: 1 } for reading a
 * session in order, and a TTL index on createdAt so old chats expire.
 */
public final class ChatMemory {

    private final Db db;

    public ChatMemory(Db db) {
        this.db = db;
    }

    // LAB 4.3 ─────────────────────────────────────────────────────────────────────
    /** Store one message. {@code hotelIds} are the hotels an answer was based on (empty for user messages). */
    public void remember(String sessionId, String role, String content, List<String> hotelIds) {
        // ▶ LAB 4.3
        db.chat().insertOne(new Document("sessionId", sessionId)
                .append("role", role)
                .append("content", content)
                .append("hotelIds", hotelIds)
                .append("createdAt", new Date()));
        // ◀ LAB 4.3
    }

    /** The last {@code lastN} messages of a session, oldest first. */
    public List<Document> recall(String sessionId, int lastN) {
        // ▶ LAB 4.3
        List<Document> latest = db.chat().find(new Document("sessionId", sessionId))
                .sort(new Document("createdAt", -1))
                .limit(lastN)
                .projection(new Document("_id", 0))
                .into(new ArrayList<>());
        Collections.reverse(latest);
        return latest;
        // ◀ LAB 4.3
    }

    public long forget(String sessionId) {
        return db.chat().deleteMany(new Document("sessionId", sessionId)).getDeletedCount();
    }
}
