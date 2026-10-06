package dev.storeforge.shared.error;

import org.springframework.http.HttpStatus;

/** A query/path parameter has a value outside its allow-list (e.g. an unknown sort field). */
public class InvalidParameterException extends StoreForgeException {

    public InvalidParameterException(String parameter, String allowed) {
        super(HttpStatus.BAD_REQUEST, "invalid_parameter", "Invalid value for '" + parameter + "'. Allowed: " + allowed + ".");
    }
}
