package dev.storeforge.storage;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** Object storage can't be reached or answers with a server error: retry later (503, EXP-025: was a 500). */
public class StorageUnavailableException extends StoreForgeException {

    public StorageUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable", "File storage is temporarily unavailable. Retry shortly.", cause);
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        return headers;
    }
}
