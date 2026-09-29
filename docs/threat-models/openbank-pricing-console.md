# Threat model — openbank-pricing-console

Scope: the staff console for pricing, `pricing.open-bank.tech`, deployed in namespace `pricing`
and adopted under GitOps on 2026-09-28 (JiRaska/openbank-pricing#1). Code lives in
`JiRaska/openbank-pricing`; this model covers the deployment view.

## Assets
- **Staff sessions.** The console signs staff in with OIDC (Keycloak client
  `openbank-pricing-console`) and holds a session secret (ExternalSecret
  `pricing-console-auth`).
- **Write access to price definitions** through `pricing-service`.

## Trust boundaries
1. The internet reaches ingress-nginx, which reaches the console on :3100.
   - The host is public: the sandbox edge applies no source-IP allow-list (owner decision
     2026-09-29). The console requires a Keycloak login (unauthenticated requests are redirected
     to `/login`), which is the control at this boundary.
   - The NetworkPolicy admits only ingress-nginx, admin-ui and same-namespace pods.
2. The console calls `pricing-service` (:8150) and `product-catalog` inside the cluster.
3. The browser authenticates against Keycloak (`kc.open-bank.tech`).

## Threats & mitigations (STRIDE)
- **Spoofing.** A non-staff user could sign in.
  - Mitigation: the OIDC authorization-code flow against the `openbank` realm, plus the source
    allow-list at the edge.
  - Residual: the console's Keycloak client is live-only; it is not in the realm template.
- **Tampering.** A request could be forged from another site (CSRF).
  - Mitigation: the session cookie and OIDC state.
  - Owed: confirm SameSite/CSRF handling in the console code.
- **Repudiation.** Console actions must be attributable. The console forwards the staff
  token to pricing-service, where attribution has to be recorded. See the service's threat
  model.
- **Information disclosure.**
  - Mitigation: security headers come from the edge (HSTS, nosniff) and from the per-Ingress
    allow-listed response headers. TLS uses cert-manager Let's Encrypt.
- **Denial of service.** Internal staff tool, one replica. Acceptable for its purpose.
- **Elevation of privilege.** A console user could do more than their role allows.
  - Mitigation: the console does not authorise on its own. pricing-service's OPA sidecar
    decides every write.

## Residual risk / follow-ups
- Move both pricing Keycloak clients into the realm template.
- Review the console's CSRF and session settings in `JiRaska/openbank-pricing`.
