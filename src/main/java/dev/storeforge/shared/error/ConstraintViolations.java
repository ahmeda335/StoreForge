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
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private ConstraintViolations() {
    }

    public static boolean isUniqueViolation(DataIntegrityViolationException exception, String constraintName) {
        return matches(exception, UNIQUE_VIOLATION, constraintName);
    }

    public static boolean isForeignKeyViolation(DataIntegrityViolationException exception, String constraintName) {
        return matches(exception, FOREIGN_KEY_VIOLATION, constraintName);
    }

    private static boolean matches(DataIntegrityViolationException exception, String sqlState, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return sql.getMessage() != null && sql.getMessage().contains(constraintName);
            }
        }
        return false;
    }
}
