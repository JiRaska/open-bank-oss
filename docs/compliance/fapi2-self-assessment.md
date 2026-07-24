# FAPI 2.0 conformance — self-assessment checklist

> A runbook for a FAPI 2.0 Security Profile self-assessment of the sandbox authorization server
> (Keycloak) and resource server (`openbank-psd2-service`) using the free
> [OpenID Foundation conformance suite](https://openid.net/certification/conformance/). No OSS
> banking platform holds a FAPI 2.0 certification; even a **published self-assessment run** is a
> credibility artifact none of the category has. Formal certification is a per-deployment fee to the
> OpenID Foundation, decided only after the suite passes green.
>
> **Positioning, not a certification.** This lists what to run and the config to check first; it is
> not a claim of conformance.

## What is in scope here

The PSD2/XS2A surface is the FAPI-relevant one: `openbank-psd2-service` (RS), `openbank-sca-service`
(SCA), `openbank-consent-service`, `openbank-tpp-registry-service`, and Keycloak as the AS
(`iam` namespace). FAPI 2.0 Security Profile (final 2025-02) is the target; Message Signing (final
2025-08) is a separate, later profile.

## Pre-run config checklist (the usual FAPI 2.0 fail points)

Check these in the Keycloak realm/client config and `openbank-psd2-service` before wasting a suite
run — each is a common hard fail:

- [ ] **PAR (Pushed Authorization Requests) required** — the client must not accept a plain
      front-channel `authorization_request`; `require_pushed_authorization_requests = true`.
- [ ] **Sender-constrained tokens** — **DPoP** or **mTLS** client-certificate-bound access tokens.
      A plain bearer token fails FAPI 2.0. Decide which (mTLS fits the existing mesh; DPoP fits
      public clients).
- [ ] **PKCE with S256** enforced (no `plain`).
- [ ] **Strict `iss` / `aud`** validation on every token and the authorization response.
- [ ] **JARM** (JWT-secured authorization response) if response signing is used — else confirm the
      redirect response mode is FAPI-acceptable.
- [ ] **No unencrypted redirect URIs**; HTTPS only; exact redirect-URI matching.
- [ ] Token lifetimes and refresh-token rotation within the profile's bounds.

## Run

1. Deploy the OpenID conformance suite (self-hosted image, or the hosted instance) with **network
   reachability to the sandbox AS** — this needs the sandbox Keycloak reachable from the runner,
   which is the one thing an in-repo assessment cannot do headless.
2. Configure a test plan: **FAPI 2.0 Security Profile**, AS = the openbank realm, RS =
   `openbank-psd2-service` an XS2A endpoint.
3. Run the plan; fix the config gaps it flags (they will mostly be from the checklist above); re-run
   until green.
4. **Publish the results** in-repo (`docs/compliance/fapi2-results/`) — the artifact is the point,
   whether or not formal certification follows.

## What is preparable vs not

- **Preparable in-repo now:** the config-diff (the checklist above turned into concrete Keycloak
  client settings + `psd2-service` config keys), and the conformance-suite test-plan JSON.
- **Needs a live run (not headless):** the suite must reach the sandbox AS over the network. That is
  the maintainer's run (or grant the assessor cluster access) — it is the only blocking step.
