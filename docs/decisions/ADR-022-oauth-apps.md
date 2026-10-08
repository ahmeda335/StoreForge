# ADR-022: Third-party apps with Spring Authorization Server: installation-bound opaque tokens

- **Status:** Accepted
- **Date:** 2026-10-08
- **Phase:** P12
- **Builds on:** [ADR-021](ADR-021-developer-api-and-credentials.md)

## Context
Third-party developers need merchants to *install* their apps: an explicit, informed, revocable grant of specific scopes on one specific store. That is OAuth 2.0's authorization code flow. Hand-rolling OAuth endpoints is a classic source of vulnerabilities.

## Problem
1. Which OAuth features, and how do they map to stores and installations?
2. How do users sign in on the authorization page, given that our admin auth is a stateless SPA flow?
3. Where do issued tokens live?
4. Who decides whether a merchant may grant what an app asks for?

## Options Considered
1. Hand-rolled endpoints vs. **Spring Authorization Server** (part of Spring Security 7).
2. **Tokens:** JWT access + refresh tokens (SAS default) vs. **opaque, installation-bound "offline" tokens** (Shopify-style) in our credential table.
3. **Login:** Spring form login with a `UserDetailsService` (bypassing P2's protections) vs. **form login through identity's `PasswordAuthenticator`**.

## Decision
- **Spring Authorization Server, customized:**
  - every app is a confidential client;
  - authorization code grant only;
  - **PKCE required**;
  - consent always shown (no remembered consent: store choice and scopes are explicit every time);
  - codes live 5 minutes;
  - exact redirect URIs (https, or http only for loopback).
- **Store choice:** the app passes `store=<store host>` (like Shopify's `shop`). The consent page shows store, app and scopes, and offers *Install* only if the merchant has `apps:manage` plus the permissions behind every scope.
- **Token issuance = installation.** A custom `OAuth2TokenGenerator` **re-checks the permissions** (the consent form can be posted by hand), replaces any previous installation of the app on that store, and creates an `sfat_` credential.
  - The token response carries `store_id`.
  - There is no refresh token: the token lasts until uninstall.
- **SAS's pending authorizations** live in PostgreSQL (shared across instances). They are **deleted the moment a token is issued**, so plaintext tokens are never stored, and codes are single-use.
- **Login:** the authorization pages use a session + CSRF chain, with form login through `PasswordAuthenticator` (same rate limits and lockout as the login API). That session never authenticates the stateless admin or developer APIs.
- **Uninstall:** revokes the credential (next call → 401) and deletes the app's webhook subscriptions in the same transaction.

## Why We Chose It
- **A vetted protocol implementation** where it's subtle: code binding, PKCE, client authentication, error redirects.
- **Our own rules where they're ours:** store binding, anti-escalation, revocation. These live in code we wrote and tested.
- **Defenses tested by forging requests:** the forged consent and the scope escalation are refused at issuance (mutation-checked, EXP-016).

## Trade-offs
- **In-memory session:** the OAuth browser session is per instance. Multi-instance needs sticky sessions or Spring Session (Redis).
- **No `app/uninstalled` webhook yet:** the app learns from 401 / reconciliation.
- **No app review, listing or billing.** Developer-owned apps are installable by anyone who knows the client id: fine for a learning platform, not for a marketplace.
- **Long-lived tokens** with no expiry: exposure risk mitigated by hashing and instant revocation. Expiring tokens plus refresh are a later learning step.

## Consequences
- **Apps use exactly the developer API and webhooks** that API keys use; there's one authorization model.
- **Any change to the consent/issuance rules** needs a forged-request test.
