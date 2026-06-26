# Threat model — Developer Portal (ADR-0093)

**Surface:** `developer.open-bank.tech` — public, internet-facing, static PSD2/XS2A documentation.
**Posture:** read-only; no credentials, no database, no backend calls. Defence-in-depth at the edge.

## Assets
- Public API documentation + the bundled OpenAPI spec (already public by design).
- The domain's reputation/availability (a defaced or down portal harms TPP trust).
- The cluster (the portal must never be a pivot into internal services).

## Trust boundaries
- Internet → nginx ingress (TLS terminates here; WAF runs here).
- Ingress → portal pod (in-cluster, plain HTTP 8080).
- The portal pod has **no** trust relationship with any banking service.

## Threats & mitigations (STRIDE-ish)
| Threat | Mitigation |
| --- | --- |
| **Spoofing the site** (phishing TPPs) | Browser-trusted Let's Encrypt cert; HSTS `preload`; forced TLS (ssl-redirect). |
| **Tampering / defacement** | Image is **cosign-signed** (KMS) and Kyverno `verify-image-signatures` Enforce admits only signed images; pod root filesystem is **read-only**; content is immutable in the image (no upload path, no CMS). |
| **Injection / OWASP Top-10 probing** | **ModSecurity + OWASP CRS** (blocking) at the ingress; no server-side code, no DB, no query processing in the pod (static files only) → no SQLi/SSRF/RCE sink. |
| **XSS** | Strict same-origin **CSP** (`script-src 'self'`, no inline script, no external CDN); `nosniff`; content is author-controlled static HTML. |
| **Clickjacking** | `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'`. |
| **Repudiation** | WAF audit log (JSON) to stdout → Loki; ingress access logs. |
| **Information disclosure** | No secrets in the image or pod (verified: zero-secret); `server_tokens off`; only the already-public spec + docs are served. |
| **DoS** | Per-client **rate limiting** + connection caps at the ingress; 2 replicas; tiny resource footprint. Volumetric DDoS beyond the ingress is a known gap (no CDN/edge-DDoS in the cloud-agnostic posture — accepted for sandbox; revisit for production per ADR-0027). |
| **Elevation / cluster pivot** | Restricted PSS, non-root (uid 101), `allowPrivilegeEscalation: false`, all caps dropped, seccomp RuntimeDefault; **egress NetworkPolicy = DNS only** (no path to internal services); dedicated namespace. |

## Residual risk / follow-ups
- Volumetric DDoS protection is ingress-level only (no managed CDN) — acceptable for sandbox.
- WAF tuning: CRS paranoia level 1 to start; monitor audit logs for false positives/bypass and raise.
- Phase 2 (TPP self-service, Keycloak realm) introduces an auth + state surface — **separate threat
  model** before it ships.
