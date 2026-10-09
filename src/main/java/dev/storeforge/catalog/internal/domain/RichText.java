package dev.storeforge.catalog.internal.domain;

import org.jspecify.annotations.Nullable;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Merchant-written HTML (product descriptions), reduced to an ALLOW list before it is stored.
 * Storefronts render descriptions as HTML; without this, one malicious or compromised merchant
 * account (or an app with {@code write_products}) could run script in every shopper's browser.
 *
 * <p>Allowed: text formatting, headings, lists, quotes and links to http/https/mailto, which get
 * {@code rel="nofollow noopener noreferrer"}. Everything else (scripts, event handlers, styles,
 * iframes, images, forms, {@code javascript:} URLs) is removed, keeping the text inside. Text is
 * HTML-escaped: a description is HTML, never plain text.
 */
public final class RichText {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "b", "i", "u", "s", "h2", "h3", "h4", "ul", "ol", "li", "blockquote", "a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelsOnLinks("nofollow", "noopener", "noreferrer")
            .toFactory();

    private RichText() {
    }

    public static String sanitize(@Nullable String html) {
        return html == null ? "" : POLICY.sanitize(html);
    }
}
