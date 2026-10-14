package io.jug.stays.search;

/** One interface, three implementations: v1 regex, v2 Atlas Search, v3 Vector Search. */
public interface HotelSearch {

    /** "v1", "v2" or "v3". */
    String version();

    SearchResult search(SearchRequest request);

    /** Fields the result cards don't need. The long text stays in the database. */
    org.bson.Document CARD_PROJECTION = new org.bson.Document("profile", 0).append("reviews", 0);
}
