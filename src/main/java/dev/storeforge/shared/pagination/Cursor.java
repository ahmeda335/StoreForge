package dev.storeforge.shared.pagination;

import dev.storeforge.shared.error.StoreForgeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Keyset-pagination cursor: the (timestamp, id) of the last row of the previous page, opaque to
 * clients. The next page is {@code WHERE (t, id) < (:t, :id) ORDER BY t DESC, id DESC LIMIT n},
 * which uses the index directly. OFFSET pagination would scan and discard all skipped rows, and
 * would skip or repeat rows when new ones are inserted between page requests.
 */
public record Cursor(Instant timestamp, UUID id) {

    public String encode() {
        String raw = timestamp.getEpochSecond() + ":" + timestamp.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":");
            return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])), UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw new InvalidCursor();
        }
    }

    static class InvalidCursor extends StoreForgeException {
        InvalidCursor() {
            super(HttpStatus.BAD_REQUEST, "invalid_cursor", "The pagination cursor is malformed.");
        }
    }
}
