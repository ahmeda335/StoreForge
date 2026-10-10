package dev.storeforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code citext_column = :param} binds the parameter as text and compares as TEXT: case-sensitive
 * (EXP-017) and unable to use the citext index. Comparisons must be explicit, either:
 * <ul>
 *   <li>{@code = CAST(:param AS citext)}: case-insensitive, index usable WITHOUT RLS (users,
 *       store_domains); under RLS citext "=" is not leakproof and never uses an index (EXP-023);</li>
 *   <li>{@code col::text = :lowercased}: for values lowercase by constraint, with an expression index
 *       on {@code col::text}: index usable under RLS (products.handle, V20).</li>
 * </ul>
 * Assignments (SET col = :param) are fine and not matched.
 */
class CitextComparisonTest {

    /** Every citext column in the schema (migrations). */
    private static final String COLUMNS = "email|handle|hostname|code|recipient|customer_key";
    private static final Pattern UNCAST = Pattern.compile(
            "(?i)\\b(?:WHERE|AND|OR|ON)\\s+(?:[a-z_]+\\.)?(" + COLUMNS + ")\\s*=\\s*:[A-Za-z]+");
    private static final Pattern DERIVED_QUERY = Pattern.compile("\\bfind\\w*By\\w*(Email|Handle|Hostname|Code)\\w*\\(");

    @Test
    void citextColumnsAreComparedWithACastParameter() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher uncast = UNCAST.matcher(source);
                while (uncast.find()) {
                    violations.add(file + ": " + uncast.group());
                }
                Matcher derived = DERIVED_QUERY.matcher(source.contains("extends JpaRepository") ? source : "");   // declarations only
                while (derived.find()) {
                    int start = Math.max(0, derived.start() - 300);
                    if (!source.substring(start, derived.start()).contains("@Query")) {
                        violations.add(file + ": derived query " + derived.group() + " (binds varchar: add a native @Query with CAST)");
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
