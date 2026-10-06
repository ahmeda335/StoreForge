package dev.storeforge.shared.pagination;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** API list response: {@code {"data": [...], "page": {"nextCursor": "...", "hasMore": true}}}. */
public record Page<T>(List<T> data, PageInfo page) {

    public record PageInfo(@Nullable String nextCursor, boolean hasMore) {
    }

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    /** Clamp the client's limit: server-enforced maximum (OWASP API4: unrestricted resource consumption). */
    public static int limit(@Nullable Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }

    /**
     * Builds a page from {@code limit + 1} fetched rows: the extra row only tells us whether there is
     * a next page, without a COUNT(*) query.
     */
    public static <T> Page<T> of(List<T> rowsPlusOne, int limit, Function<T, Cursor> cursorOf) {
        boolean hasMore = rowsPlusOne.size() > limit;
        List<T> data = hasMore ? rowsPlusOne.subList(0, limit) : rowsPlusOne;
        String next = hasMore ? cursorOf.apply(data.getLast()).encode() : null;
        return new Page<>(List.copyOf(data), new PageInfo(next, hasMore));
    }
}
