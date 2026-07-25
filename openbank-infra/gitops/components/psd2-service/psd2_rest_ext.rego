# SPDX-License-Identifier: Apache-2.0
# psd2-service REST extension (ADR-0034 Phase 5; ADR-0090 Berlin Group XS2A; issue #1797).
# Extends openbank.rest with the PSD2/XS2A allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: psd2-service ships @Authorize on 21 endpoints with the app default
# AUTHZ_ENFORCE=true (application.yaml: `authz.enforce: "${AUTHZ_ENFORCE:true}"`), but its
# live pod had NO `opa` container — so the interceptor's PDP call to localhost:8181 failed
# and every one of those endpoints failed closed for every caller. Wiring the sidecar + this
# bundle restores service. This rollout keeps AUTHZ_ENFORCE=false (advisory) so the live
# decision log can confirm the "would DENY" population is empty before the enforce flip (a
# separate, deliberate follow-up per the rules.yaml AUTHZ_ENFORCE guardrail).
#
# psd2-service is NOT in rules.yaml money_path_services, and none of list/read/create/
# initiate/delete is a four_eyes.verbs verb — so no four-eyes flag is raised here,
# deliberately. Do NOT add four-eyes logic in this file; that is rest.rego's job.
#
# =======================================================================================
# CALLER AUDIT (issue #1797) — read this before touching a rule below.
#
# The PSD2 surface has ONE real caller class, and it is NOT a Keycloak identity.
#
# 1. THIRD-PARTY PROVIDERS (TPPs) — AISP/PISP — the only caller of all 21 @Authorize
#    endpoints. A TPP authenticates with an eIDAS QWAC (mutual TLS), surfaced to the app as
#    `SSL-CLIENT-S-DN` (or `X-TPP-ID` in the sandbox). It carries NO OIDC bearer token.
#    AuthorizeInterceptor.principalType() reads SecurityContext.userPrincipal, which is null
#    for a bearer-less request, so every TPP call arrives at OPA as
#        {"type": "ANONYMOUS", "id": "anonymous", "roles": []}
#    — NOT "HUMAN", and never "SERVICE" (see the note at the bottom of this file).
#    A rule written for HUMAN + a role would therefore have turned the current 503
#    fail-closed into a 403 fail-closed and changed nothing for the actual caller.
#
#    Authentication and TPP authorization already happened BEFORE the interceptor runs:
#    EidasMtlsFilter (@Priority(Priorities.AUTHENTICATION), infrastructure/rest/filter/)
#    gates every path under `open-banking/` (except `open-banking/sandbox/`) and `v1/`. It
#    401s a request with no TPP identity (CERTIFICATE_MISSING), calls tpp-registry-service
#    to check the TPP's eIDAS role (PISP for `/payments`, otherwise AISP) and licence
#    status, 401s a TPP that is not authorized (CERTIFICATE_INVALID), and 503s when the
#    registry is unreachable — the request only reaches an @Authorize method once that
#    passed. QsealSignatureFilter (@Priority(Priorities.AUTHORIZATION)) adds per-message
#    QSEAL integrity on the Berlin write surface. On top of that, every handler requires a
#    Consent-ID and the use case re-validates that consent against consent-service, scoped
#    to the tppId the filter attached.
#
#    OPA cannot re-derive any of that: the tppId lives in a JAX-RS request property, not in
#    the AuthzQuery, so the PDP genuinely has no TPP-specific input to discriminate on. The
#    honest policy is therefore a namespace grant for the eIDAS plane (rule below), NOT a
#    role check that would be security theatre while denying the real caller. Narrowing this
#    further requires passing the TPP identity into the authz query — a service-code change,
#    tracked as a follow-up, deliberately out of scope for this fail-closed unbricking.
#
# 2. ADMIN-UI / STAFF (ROLE_OPERATOR, ROLE_ADMIN) — no in-repo caller of a gated path.
#    The System Health page probes `/q/health/ready` and `/api/v1/info` (neither annotated;
#    the legacy `healthPath: /open-banking/v2/accounts` entry in the admin-ui health route is
#    dead metadata — HEALTH_PATH is hardcoded to /q/health/ready). The generic
#    `/api/svc/psd2-service/<path>` BFF proxy forwards a staff bearer and could reach a gated
#    path ad hoc; such a call is HUMAN + ROLE_OPERATOR/ADMIN, and base rest.rego's
#    operator-read-any ALREADY grants psd2.read and psd2.list for those roles. Deliberately
#    NO rule here restates that, and deliberately no rule grants staff psd2.create /
#    psd2.initiate / psd2.delete — an operator must not be able to initiate a payment or
#    revoke a consent through the TPP surface. Least privilege: do not restate a base grant.
#
# 3. OTHER SERVICES — none. The NetworkPolicy admits only same-namespace pods, admin-ui and
#    security-scanner; psd2-service is a caller of consent-service and tpp-registry-service,
#    not a callee of any service. So there is no M2M service-account principal to grant.
#
# 4. AI AGENTS — none today. The MCP server (openbank-mcp-service) curates its own tools and
#    routes AI_AGENT through rest.rego's agent-charter-allows rule, which consults
#    agents.yaml; adding a psd2 tool there needs a charter entry, not a rule here.
#
# ENDPOINT → ACTION → RULE map (all 21 @Authorize methods):
#
#   psd2.list      GET    /v1/accounts                              BerlinAisResource
#                  GET    /open-banking/v2/accounts                 AisResource
#   psd2.read      GET    /v1/accounts/{id}/balances|transactions   BerlinAisResource   (x2)
#                  GET    /open-banking/v2/accounts/{id}/…          AisResource         (x2)
#                  GET    /v1/consents/{id}, /{id}/status           BerlinConsentResource (x2)
#                  GET    /open-banking/v2/consents/{id}, /status   ConsentResource     (x2)
#                  GET    /v1/payments/{product}/{id}/status        BerlinPisResource
#                  GET    /open-banking/v2/payments/{p}/{id}/status PisResource
#   psd2.create    POST   /v1/consents                              BerlinConsentResource
#                  POST   /open-banking/v2/consents                 ConsentResource
#   psd2.initiate  POST   /v1/payments/{paymentProduct}             BerlinPisResource
#                  POST   /open-banking/v2/payments/{sepa-credit-transfers,
#                         instant-sepa-credit-transfers,domestic-cz,sipo}  PisResource (x4)
#   psd2.delete    DELETE /v1/consents/{id}                         BerlinConsentResource
#                  DELETE /open-banking/v2/consents/{id}            ConsentResource
#
#   → psd2.read / psd2.list  : psd2-tpp-eidas-qwac (TPP) + operator-read-any (base, staff)
#   → psd2.create / .initiate / .delete : psd2-tpp-eidas-qwac ONLY (TPP)
# =======================================================================================

package openbank.rest

import rego.v1

# The eIDAS QWAC plane. A TPP presents no bearer, so it reaches OPA as ANONYMOUS — see
# caller-audit item 1 above for why that is the correct and only possible discriminator
# here, and what has already authenticated the caller by the time this rule is consulted.
#
# The grant is bounded on three independent axes, all load-bearing:
#   * BUNDLE — this file ships only in psd2-service's own OPA sidecar bundle
#     (psd2-opa-bundle.yaml), so no other service's PDP can ever evaluate it.
#   * ACTION — an explicit five-element set, NOT a `startswith(input.action, "psd2.")`
#     prefix. A future psd2.* action (e.g. a funds-confirmation or a bulk-payment verb)
#     must be added here consciously, with its own caller evidence, rather than inheriting
#     an anonymous grant by name.
#   * PATH — every one of the 21 annotated methods sits under `/open-banking/v2…` or
#     `/v1/…`, i.e. inside EidasMtlsFilter's gated prefix set. THIS IS THE INVARIANT THIS
#     RULE DEPENDS ON: if you add an @Authorize(action = "psd2.…") method on a path the
#     filter does NOT gate (notably anything under `open-banking/sandbox/`), this rule
#     would hand it to a genuinely unauthenticated caller. Add the path to
#     EidasMtlsFilter's `gated` predicate in the same change, or give the endpoint its own
#     action outside this set.
allowed_reasons contains "psd2-tpp-eidas-qwac" if {
	input.principal.type == "ANONYMOUS"
	input.action in {
		"psd2.list",
		"psd2.read",
		"psd2.create",
		"psd2.initiate",
		"psd2.delete",
	}
}

# NOTE (issue #266 fleet audit, rules.yaml: authz_policy): AuthorizeInterceptor never emits
# principal.type == "SERVICE" — only ANONYMOUS/AI_AGENT/HUMAN — and no realm client is granted
# ROLE_SERVICE, so a SERVICE-gated rule is structurally unreachable dead code. psd2-service has
# no M2M callee surface at all (caller-audit item 3), so this file identifies no
# `service-account-<clientId>` principal either; if one ever appears, gate it on
# input.principal.id, never on a bare HUMAN + ROLE_OPERATOR check (real staff carry
# ROLE_OPERATOR too, which would over-grant the TPP payment surface to any operator session).
