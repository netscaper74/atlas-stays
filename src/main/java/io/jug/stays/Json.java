package io.jug.stays;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JSON in and out, using the BSON library that ships with the driver. No extra dependency. */
public final class Json {

    /** Relaxed extended JSON, with dates as ISO strings so the browser can read them directly. */
    public static final JsonWriterSettings WEB = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .dateTimeConverter((value, writer) -> writer.writeString(Instant.ofEpochMilli(value).toString()))
            .objectIdConverter((value, writer) -> writer.writeString(value.toHexString()))
            .build();

    /** Pretty JSON for the Atlas Inspector panel. */
    public static final JsonWriterSettings PRETTY = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .build();

    public static String write(Document d) {
        return d.toJson(WEB);
    }

    public static Document parse(String json) {
        return json == null || json.isBlank() ? new Document() : Document.parse(json);
    }

    /** Converts filters built with the driver's Filters helpers into a Document, so they can be shown in the Inspector. */
    public static Document toDocument(Bson bson) {
        if (bson instanceof Document d) return d;
        return Document.parse(bson.toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson());
    }

    public static List<Document> toDocuments(List<? extends Bson> pipeline) {
        List<Document> out = new ArrayList<>();
        for (Bson b : pipeline) out.add(toDocument(b));
        return out;
    }

    /** One entry for the Atlas Inspector: a label and the exact query or pipeline that ran. */
    public static Document debug(String label, Object query) {
        return new Document("label", label).append("query", query);
    }

    private Json() {}
}
