package dev.storeforge.stores;

import dev.storeforge.shared.error.StoreForgeException;
import org.springframework.http.HttpStatus;

public class StoreNotFoundException extends StoreForgeException {

    public StoreNotFoundException() {
        super(HttpStatus.NOT_FOUND, "store_not_found", "Store not found.");
    }
}
