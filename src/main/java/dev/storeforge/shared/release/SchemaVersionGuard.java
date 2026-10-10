package dev.storeforge.shared.release;

import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * When this instance doesn't migrate (roles api/worker), it refuses to start on a database whose
 * schema is OLDER than its code: new code on an old schema fails in ways that are hard to see (a
 * missing column in one rare query). Newer schemas are fine: migrations are written to be backward
 * compatible with the previous release (expand/contract), which is what makes rolling deploys work.
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
@Slf4j
public class SchemaVersionGuard implements SmartInitializingSingleton {

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    private final JdbcClient jdbc;

    public SchemaVersionGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int expected = latestMigrationInCode();
        int applied;
        try {
            applied = jdbc.sql("SELECT coalesce(max(CAST(version AS integer)), 0) FROM flyway_schema_history WHERE success")
                    .query(Integer.class).single();
        } catch (DataAccessException e) {
            applied = 0;   // history not readable by the app role: older than V22, which grants it
        }
        if (applied < expected) {
            throw new IllegalStateException("Database schema is at V" + applied + " but this release needs V" + expected
                    + ": run the migrate job first (docs/operations/runbooks/deploy.md)");
        }
        log.atInfo().addKeyValue("schema", applied).addKeyValue("code", expected).log("Schema version check passed");
    }

    static int latestMigrationInCode() {
        try {
            Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
            return Arrays.stream(migrations).map(Resource::getFilename).map(name -> VERSION.matcher(name == null ? "" : name))
                    .filter(Matcher::matches).mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0);
        } catch (IOException e) {
            throw new IllegalStateException("Can't list migrations", e);
        }
    }
}
