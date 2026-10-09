# Browser Security Headers and CORS

**Problem.** Browsers render, frame and share responses by default. A JSON API that a browser is tricked into rendering, or that any website can read with the user's cookies, becomes an attack surface even if the API itself is correct.

**Why we need it.** All four APIs are reachable from browsers (P14 review).

**Alternatives.** Rely on the frontend; set headers at the reverse proxy; **set them in the app, per API**.

**Decision.** Strict headers on every response, a narrower policy for the OAuth HTML pages, and CORS per API ([ADR-028](../decisions/ADR-028-http-edge-hardening.md)).

**Mental model.**
- **CSP** says what a page may load and run. For JSON: `default-src 'none'`, so nothing. For our HTML pages: no scripts at all, and inline styles allowed only by their exact SHA-256 hash.
- `frame-ancestors 'none'` / `X-Frame-Options: DENY`: no clickjacking. `nosniff`: no content-type guessing. `Referrer-Policy: no-referrer`: URLs with ids don't leak to other sites.
- `form-action` also applies to the redirect *after* a form post in some browsers: it would block OAuth's redirect to the app, so the consent page leaves it out.
- **CORS** relaxes the same-origin policy; it doesn't add protection. "Allow any origin" is safe only **without credentials**: the browser then sends no cookies, so a foreign page gets only what any anonymous client gets. With credentials, list exact origins.
- Preflight (`OPTIONS`) must be answered before authentication, which is why CORS is configured in the security chains.

**Project usage.** `SecurityHeadersFilter`, `OAuthPagesController.PAGE_POLICY`, `CorsConfiguration` (`corsFilter` with Problem Detail rejections).

**Failure cases.**
- `Access-Control-Allow-Origin: *` with credentials (browsers refuse it), or reflecting any origin with credentials;
- `'unsafe-inline'` scripts;
- CSP that breaks OAuth redirects;
- headers only on success responses (we set them in a filter, so errors get them too).

**Testing.** `HttpEdgeIT`:
- header values;
- the page's style hash computed from the served HTML;
- preflights from allowed and foreign origins, storefront without credentials, the developer API without CORS.

**Observability.** Rejections are 403 `cross_site_request_rejected`; browsers report CSP violations in the console (a `report-to` endpoint would come with a frontend).

**When NOT to use it.** Server-to-server APIs don't need CORS at all. Don't add it "to make it work".
