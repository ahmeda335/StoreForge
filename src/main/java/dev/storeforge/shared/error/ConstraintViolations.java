package dev.storeforge.shared.error;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Database constraints are part of business correctness: when one fires, the application maps the
 * NAMED constraint to a business answer (e.g. {@code uq_users_email} → "email already registered").
 * Constraint names follow the {@code uq_/fk_/ck_} convention so this mapping is explicit.
 */
public final class ConstraintViolations {

    private static final String UNIQUE_VIOLATION = "23505";

    private ConstraintViolations() {
    }

    public static boolean isUniqueViolation(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return sql.getMessage() != null && sql.getMessage().contains(constraintName);
            }
        }
        return false;
    }
}
