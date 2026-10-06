package dev.storeforge.stores;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public class PermissionDeniedException extends StoreForgeException {

    public PermissionDeniedException(String detail) {
        super(HttpStatus.FORBIDDEN, "permission_denied", detail);
    }
}
