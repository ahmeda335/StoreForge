# Spring Security Filter Chain

**Problem.** Authentication must happen before any controller runs, consistently, for every request, without each controller re-implementing token parsing.

**Why we need it.** Four APIs with different security models (blueprint 08). The structure must make the right model the default for each path.

**Alternatives.** Hand-written servlet filters (easy to get subtly wrong), interceptors (run too late, after routing), API gateway auth (later, in addition).

**Decision.** Multiple `SecurityFilterChain` beans, one per API path prefix, ordered.

**Mental model.**
- The servlet container calls a single `FilterChainProxy`. It picks the **first** `SecurityFilterChain` whose `securityMatcher` matches the request, and runs that chain's filters (CSRF, bearer-token, exception translation, authorization...).
- `BearerTokenAuthenticationFilter` extracts `Authorization: Bearer …` → `JwtDecoder` validates → our `Converter<Jwt, Authentication>` builds the principal → stored in `SecurityContextHolder` for this request.
- `ExceptionTranslationFilter` turns `AuthenticationException` into the **entry point** (401) and `AccessDeniedException` into the **access-denied handler** (403). We plug in Problem Details writers so errors look like every other error.
- `authorizeHttpRequests` is **coarse** authorization (authenticated or not). Fine-grained checks ("may this user refund orders in store X?") need the resource and belong in application services (P3).
- `STATELESS`: no `HttpSession`, no `JSESSIONID`. Every request carries its credentials.

**Project usage.** `SecurityConfiguration` (chains `/auth/**`, `/admin/**`, fallback), `ProblemSecurityHandlers`, `UserTokenConverter`, `UserMdcFilter`.

**Failure cases.** A new API prefix not covered by a dedicated chain falls into the permissive fallback. **Every new API must add its chain** (storefront in P4/P7, developer API in P12). An overly broad matcher order shadows later chains. Forgetting `STATELESS` silently creates sessions.

**Testing.** `AccessTokenSecurityIT` (401 format, challenge header), the security tests of each module.

**Observability.** 401/403 counts per URI in HTTP metrics. `userId` in authenticated log lines.

**When NOT to use it.** Don't use URL-pattern rules for business permissions. Keep those in services, where jobs and other entry points also pass through.
