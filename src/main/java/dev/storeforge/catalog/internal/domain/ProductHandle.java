package dev.storeforge.catalog.internal.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** URL slug of a product within its store ({@code /products/blue-t-shirt}). */
public final class ProductHandle {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private ProductHandle() {
    }

    /** "Blue T-Shirt (Ünïcode)!" → "blue-t-shirt-unicode". */
    public static String fromTitle(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 100) {
            slug = slug.substring(0, 100).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "product" : slug;
    }

    public static String validate(String handle) {
        String normalized = handle.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100 || !FORMAT.matcher(normalized).matches()) {
            throw new CatalogExceptions.InvalidProduct("Handles are lowercase letters and digits separated by single hyphens (max 100).");
        }
        return normalized;
    }
}
