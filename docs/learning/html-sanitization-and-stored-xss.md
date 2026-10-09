# HTML Sanitization and Stored XSS

**Problem.** Merchants write rich product descriptions; storefronts render them as HTML. A `<script>`, an `onerror=` or a `javascript:` link in one description runs in every shopper's browser on that store: stored XSS.

**Why we need it.** Product descriptions (P4 stored them as sent; P14).

**Alternatives.**
- **Plain text only:** safe, but no formatting.
- **Escape on output:** the frontend must never forget, and every consumer (apps, emails) must do it.
- **Markdown:** still needs sanitizing of the generated HTML.
- **Sanitize on input with an ALLOW list:** what's stored is safe for every consumer.

**Decision.** OWASP Java HTML Sanitizer with an allow list, in the `Product` aggregate, so every write path is covered.

**Mental model.**
- **Allow lists, never block lists.** There are endless ways to run script (`<svg onload>`, `<img onerror>`, `javascript:`, entity tricks, mutation XSS). An allow list names what's safe: `p`, `strong`, lists, headings, links to http/https/mailto.
- Parse, then rebuild: a real sanitizer parses HTML the way a browser does and emits only allowed nodes, rather than regex-replacing text.
- Links get `rel="nofollow noopener noreferrer"`; `target` is dropped.
- The output is HTML: text is escaped (`&` becomes `&amp;`). Clients must treat descriptions as HTML, consistently.
- Defence in depth: CSP on the storefront frontend (when it exists).

**Project usage.** `RichText.sanitize` in `Product.setDetails`.

**Failure cases.**
- Sanitizing in one controller but not another write path;
- regex "sanitizers";
- allowing `style` or `class` (CSS injection, UI redressing);
- rows written before sanitization existed (here: unsanitized until edited; no storefront rendered them).

**Testing.** `RichTextTest` (scripts, handlers, `javascript:` links, SVG, iframes, forms; allowed formatting survives) and `PropertyLevelAuthorizationIT` (end to end, create and edit).

**Observability.** None needed per request. Removed content isn't reported to merchants (a "content was cleaned" warning would be a UX addition).

**When NOT to use it.** Fields that are plain text (titles, names): escape them on output, don't allow HTML at all.
