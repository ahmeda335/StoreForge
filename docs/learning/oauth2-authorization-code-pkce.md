# OAuth 2.0 Authorization Code + PKCE (for third-party apps)

**Problem.** A merchant wants a third-party app to read their orders and adjust stock. Giving the app their password would let it do *everything*, forever, unrevocably, and teach merchants to type passwords into other sites. They need to grant *specific* rights on *one* store to *one* app, and to be able to take them back.

**Why we need it.** Third-party apps (P12). Every platform with an ecosystem (Shopify, Stripe Connect, GitHub Apps) uses this flow.

**Alternatives.**
- **Password sharing:** never.
- **API keys pasted by the merchant:** fine for their own scripts (we have them), but poor UX and no consent screen for third parties.
- **Implicit flow:** deprecated; tokens in URLs.
- **Hand-rolled OAuth:** easy to get subtly wrong.

**Decision.** Authorization code with PKCE on Spring Authorization Server, customized for store-bound installations ([ADR-022](../decisions/ADR-022-oauth-apps.md)).

**Mental model.**
- **Four parties:** the merchant (resource owner), their browser (user agent), the app (client), StoreForge (authorization and resource server).
- **The browser carries only a *code*; the token travels server to server.** The code is short-lived (5 min), single-use, and useless without the app's secret *and* PKCE verifier.
- **PKCE:** the app invents a random `code_verifier` and sends `S256(verifier)` with the authorize request. At token time it must present the verifier. A code stolen from a log, a browser history or a malicious redirect can't be exchanged. RFC 9700 asks for PKCE even for confidential clients.
- **`state`:** the app's random value, echoed back. It protects the *app's callback* against CSRF (an attacker making a victim's app install the attacker's account).
- **Exact redirect URIs:** the code is delivered there. Prefix or wildcard matching is how codes get stolen (open-redirect chains). We return an error page, not a redirect, for unknown URIs.
- **Consent is a decision, not a page.** Hiding the Install button isn't security: the form can be posted by hand. The rules (merchant holds `apps:manage` plus every scope's permission) are enforced again **when the token is issued**.
- **Scopes are capped twice:** by the app's registration (`invalid_scope`) and by what the approving merchant holds (anti-escalation).
- **Tokens:** opaque, hashed at rest, bound to an installation, revoked on uninstall. Not stored by the authorization server: its pending record is deleted at issuance.

**Project usage.**
- `developer.internal.oauth`:
  - `AppsClientRepository` (client settings: PKCE, consent, TTLs);
  - `OAuthStorage` (delete at issuance, no remembered consent);
  - `InstallationTokenGenerator` (installation + re-check);
  - `OAuthPagesController` (login, consent);
  - `PasswordAuthenticationProvider` (identity's protections);
  - `OAuthConfiguration` (two session chains).
- The sample client: `samples/stocksync`.

**Failure cases.**
- Missing PKCE or `state`;
- loose redirect matching;
- authorization checked only on the consent page;
- tokens stored in plaintext;
- a second login form without rate limits or lockout;
- the authorization server's session cookie authenticating the APIs (CSRF);
- long-lived tokens with no revocation.

**Testing.** `OAuthInstallIT` drives the real redirects with a cookie-keeping, non-following HTTP client and forges each attack: posted consent, scope escalation, code replay, wrong verifier, wrong secret, foreign redirect, missing PKCE, excess scopes, brute force. Mutations prove the re-checks matter ([EXP-016](../experiments/EXP-016-developer-platform-mutations.md)).

**Observability.**
- Audit entries `app.installed` / `app.uninstalled` (USER), and app actions as `APP`;
- login metrics (`storeforge_auth_logins_total`) cover the OAuth login too;
- usage per installation.

**When NOT to use it.** Your own scripts (API keys are simpler). Machine-to-machine within one organisation (client credentials). Signing users in to your site (that's OpenID Connect, on top of OAuth).
