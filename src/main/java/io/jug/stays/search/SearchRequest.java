package io.jug.stays.search;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What the user asked for: free text plus structured filters.
 * Every version receives exactly the same request, which is what makes the comparison fair.
 */
public record SearchRequest(
        String q,
        String city,
        String type,
        Integer minPrice,
        Integer maxPrice,
        Integer minStars,
        List<String> amenities,
        String sort,       // "" (relevance) | price_asc | price_desc | rating
        int page,
        int size,
        boolean hybrid) {

    public static final int MAX_PRICE = 1250;

    public static SearchRequest of(String q) {
        return new SearchRequest(q, null, null, null, null, null, List.of(), "", 0, 24, false);
    }

    public SearchRequest withSize(int newSize) {
        return new SearchRequest(q, city, type, minPrice, maxPrice, minStars, amenities, sort, page, newSize, hybrid);
    }

    public SearchRequest withoutCity() {
        return new SearchRequest(q, null, type, minPrice, maxPrice, minStars, amenities, sort, page, size, hybrid);
    }

    public SearchRequest withoutType() {
        return new SearchRequest(q, city, null, minPrice, maxPrice, minStars, amenities, sort, page, size, hybrid);
    }

    public SearchRequest withoutStars() {
        return new SearchRequest(q, city, type, minPrice, maxPrice, null, amenities, sort, page, size, hybrid);
    }

    public SearchRequest withoutPrice() {
        return new SearchRequest(q, city, type, null, null, minStars, amenities, sort, page, size, hybrid);
    }

    public boolean hasText() {
        return q != null && !q.isBlank();
    }

    public boolean hasFilters() {
        return city != null || type != null || minPrice != null || maxPrice != null || minStars != null || !amenities.isEmpty();
    }

    /** Builds a request from URL query parameters. */
    public static SearchRequest from(Map<String, String> p) {
        String q = trim(p.get("q"), 120);
        List<String> amenities = p.get("amenity") == null || p.get("amenity").isBlank()
                ? List.of()
                : Arrays.stream(p.get("amenity").split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(8).toList();
        Integer minPrice = intOrNull(p.get("pmin"));
        Integer maxPrice = intOrNull(p.get("pmax"));
        if (minPrice != null && minPrice <= 0) minPrice = null;
        if (maxPrice != null && maxPrice >= MAX_PRICE) maxPrice = null;
        Integer stars = intOrNull(p.get("stars"));
        if (stars != null && stars <= 1) stars = null;
        int page = Math.max(0, intOr(p.get("page"), 0));
        int size = Math.min(48, Math.max(1, intOr(p.get("size"), 24)));
        return new SearchRequest(q, blankToNull(p.get("city")), blankToNull(p.get("type")), minPrice, maxPrice, stars,
                amenities, p.getOrDefault("sort", ""), page, size, "true".equals(p.get("hybrid")));
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOr(String s, int d) {
        Integer i = intOrNull(s);
        return i == null ? d : i;
    }
}
