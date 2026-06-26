# ADR-0093 — Public developer portal for the PSD2 XS2A API

**Status:** Accepted (2026-06-15 — Phase 1 implemented: static public docs + hardened edge)
**Date:** 2026-06-15
**Relates to:** ADR-0090 (PSD2 XS2A Berlin + ČOBS), ADR-0056 (admin-ui is the internal-only console),
ADR-0027 (cloud-agnostic in-cluster substrate), ADR-0030 (threat-model discipline for public boundaries),
ADR-0081 (cluster segmentation / NetworkPolicy baseline)

## Context

ADR-0090 shipped a Berlin Group NextGenPSD2 + ČOBS XS2A API. PSD2 RTS Art. 30 requires not just a
dedicated interface but a **usable** one: TPPs across the EU integrate against published docs, sandbox
credentials and onboarding guidance. There is no developer-facing surface for any of that today.

The obvious place — the admin-ui — is the wrong place. admin-ui is the **internal operations console**
and, by ADR-0056, the sole browser→cluster path for **operators**. A TPP developer portal is
**external**, untrusted-audience, internet-exposed. Bundling it into admin-ui would breach that
internal-only security boundary. It belongs on its own external surface, like customer-edge is the
external customer hub.

## Decision

Stand up **`openbank-developer-portal`** — a separate, public, **static** documentation surface for the
XS2A API, fronted by a hardened edge. Phase 1 is read-only docs; interactive TPP self-service is layered
on later.

- **D1 — Static, zero-secret, zero-backend.** The portal is plain HTML/CSS + a vendored OpenAPI renderer
  (Redoc, served same-origin, no runtime CDN). It holds **no credentials**, has **no database**, and
  **proxies no banking service** — minimal blast radius for an internet-facing surface. Served by an
  unprivileged nginx under the restricted Pod Security Standard with a read-only root filesystem.

- **D2 — Hardened public edge.** `developer.open-bank.tech` on the existing nginx ingress with
  browser-trusted Let's Encrypt. The edge carries the controls, not the pod:
  **ModSecurity + OWASP Core Rule Set** in blocking mode (scoped to this ingress, not cluster-wide),
  per-client **rate limiting**, forced TLS + HSTS, and a strict same-origin **Content-Security-Policy**
  / `X-Frame-Options: DENY` / `nosniff`. In-cluster WAF (no AWS WAF / CloudFront) keeps the
  cloud-agnostic posture of ADR-0027.

- **D3 — Content (Phase 1).** Berlin XS2A + ČOBS **API reference** (renders psd2-service's OpenAPI),
  getting-started, authentication (redirect + decoupled SCA, ADR-0021), the consent model, eIDAS
  QWAC/QSEAL + TPP-identity requirements, sandbox specifics (QSEAL advisory, stub downstreams,
  `/open-banking/v2` deprecation), and versioning. The OpenAPI is bundled at build time from the
  service's `openapi.yaml` (single source of truth).

- **D4 — Image lifecycle.** The portal is not a Gradle service, so it is **outside** the auto-deploy
  fleet pipeline; its image is built, **cosign-signed** (the same KMS trust root every openbank image
  uses, so Kyverno `verify-image-signatures` admits it — ADR-0030) and pushed on content change. GitOps
  owns the deployment via the app-of-apps.

## Deferred (later phases)

- **Phase 2 — TPP self-service:** a Keycloak `openbank-developers` realm; register a sandbox app →
  sandbox `client_id`/credentials; manage QWAC/QSEAL certificate thumbprints (feeding tpp-registry);
  redirect-URI management. Stateful and money-adjacent — needs tpp-registry deployed first.
- **Phase 3 — interactive "try-it" console** against the sandbox XS2A API.

## Alternatives considered

- **Inside admin-ui.** Rejected — admin-ui is internal-only (ADR-0056); an external TPP audience must
  not share that surface.
- **A Next.js app (like admin-ui).** Rejected for Phase 1 — an SSR/Node runtime is a larger attack
  surface than static files for a docs site. A static site is the more secure default; revisit if
  Phase 2/3 interactivity warrants it.
- **AWS WAF + CloudFront.** Rejected — managed WAF/CDN is stronger DDoS-wise but is AWS lock-in against
  the cloud-agnostic substrate (ADR-0027). In-cluster ModSecurity keeps portability.

## Consequences

- A conformant, usable XS2A docs surface (RTS Art. 30 "usable dedicated interface").
- A new internet-facing boundary ⇒ a threat model is required (ADR-0030): `docs/threat-models/developer-portal.md`.
- The OpenAPI is duplicated into the portal image at build time; a content change in the service contract
  means a portal rebuild. Acceptable; the build copies from the service's source-of-truth spec.
- WAF false positives are possible; CRS paranoia level starts at 1 and audit logs go to stdout for tuning.
