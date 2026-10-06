package dev.storeforge.shared.idempotency;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public final class IdempotencyExceptions {

    private IdempotencyExceptions() {
    }

    public static class KeyRequired extends StoreForgeException {
        public KeyRequired() {
            super(HttpStatus.BAD_REQUEST, "idempotency_key_required",
                    "This operation requires an Idempotency-Key header (1-255 printable ASCII characters, e.g. a UUID).");
        }
    }

    /** Same key, different request: almost always a client bug (a key reused for another operation). */
    public static class KeyReused extends StoreForgeException {
        public KeyReused() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This Idempotency-Key was already used with a different request.");
        }
    }
}
