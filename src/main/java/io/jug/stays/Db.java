package io.jug.stays;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

/**
 * One MongoClient for the whole app (it is thread-safe and pools connections).
 * Every attendee works in their own database on the shared Atlas cluster.
 */
public final class Db {

    public final MongoClient client;
    public final MongoDatabase database;

    public Db(Config config) {
        if (config.mongoUri.isBlank()) {
            throw new IllegalStateException("MONGODB_URI is not set. Copy .env.example to .env and paste the connection string your instructor gave you.");
        }
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.mongoUri))
                .applicationName("jug-istanbul-stays-" + config.attendee)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(config.database);
    }

    public MongoCollection<Document> hotels() {
        return database.getCollection(Config.HOTELS);
    }

    public MongoCollection<Document> chat() {
        return database.getCollection(Config.CHAT);
    }

    public Document ping() {
        return database.runCommand(new Document("ping", 1));
    }
}
