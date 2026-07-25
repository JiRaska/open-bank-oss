# SPDX-License-Identifier: Apache-2.0
# dispute-service REST extension (ADR-0034 Phase 5; ADR-0085 complaints; issue #1797).
# Extends openbank.rest with the one allow reason the base policy does not already cover.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: dispute-service ships @Authorize on 14 endpoint methods with the app
# default AUTHZ_ENFORCE=true (application.yaml: `authz.enforce: "${AUTHZ_ENFORCE:true}"`), but
# its Deployment declared NO `opa` container — confirmed live: the running pod has exactly one
# container, `dispute-service`. So the interceptor's PDP call to localhost:8181 failed and every
# one of those 14 methods failed closed for every caller. Wiring the sidecar + this bundle
# restores service. This rollout keeps AUTHZ_ENFORCE=false (advisory) so the live decision log
# can confirm the "would DENY" population is empty before the enforce flip (a separate,
# deliberate follow-up per the rules.yaml AUTHZ_ENFORCE guardrail).
#
# dispute-service is NOT in rules.yaml money_path_services, and neither `update` nor `resolve`
# is a four_eyes.verbs verb (transfer/post/reverse/freeze/release/flip/transitionStatus/recall/
# settle/disburse/send) — so no four-eyes flag is raised here, deliberately. Do NOT add
# four-eyes logic in this file; that is rest.rego's job.
#
# =======================================================================================
# CALLER AUDIT (issue #1797) — read this before touching the rule below.
#
# Ingress is bounded by network-policies.yaml: only same-namespace pods and the namespaces
# `customer-edge`, `platform` (agent-service), `admin-ui` and `security-scanner` can reach
# port 8135 at all. Every caller below was traced to a call site, not assumed.
#
# THE HEADLINE FINDING: **not one caller of this service reaches OPA as AI_AGENT**, even
# though these @Authorize annotations were added by PR #738 "for the AI-agent bridge"
# (issue #401). See item 3 — the agent identity does not survive the outbound hop. A rule
# written for AI_AGENT here would be unreachable dead code.
#
# 1. CUSTOMER-EDGE (M2M) — the only customer-facing caller of an @Authorize method.
#    CustomerEdgeResource.listDisputes (openbank-customer-edge/.../CustomerEdgeResource.kt)
#    does `upstream.get("$disputeServiceUrl/api/v1/disputes/account/$accountId", partyId)`,
#    landing on DisputeResource.listByAccount — `@Authorize(action = "dispute.list",
#    resource = "#accountId")`. UpstreamClient.get attaches a **client_credentials** bearer
#    for the `openbank-edge` client (UpstreamClient.serviceToken); the customer's own token
#    is NOT forwarded — the partyId travels as the X-Party-Id header. The realm grants that
#    service account ROLE_OPERATOR (keycloak/realm-template.json clientScopeMappings). A
#    Keycloak client_credentials token is classified **HUMAN** by AuthorizeInterceptor —
#    never "SERVICE" (see the note at the bottom of this file). So it arrives as
#        {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}
#    and base rest.rego's `operator-read-any` (HUMAN + ROLE_OPERATOR/ADMIN + verb in
#    {list, read}) ALREADY grants it. Deliberately NOT restated here — least privilege says
#    do not re-grant what the base policy already covers.
#    The edge's other two dispute calls — POST /api/v1/disputes (open) and POST
#    /api/v1/complaints (file) — carry NO @Authorize and never consult the PDP.
#
# 2. ADMIN-UI / STAFF (HUMAN, a real Keycloak user token). The `/disputes` page issues
#    exactly one upstream call, `GET /api/v1/disputes` → `dispute.list`, through the BFF
#    proxy (src/app/api/svc/[service]/[...path]/route.ts), which relays the signed-in
#    operator's OWN access token (`session.user.accessToken`) — not a service token — and
#    401s when there is none. The page is READ-ONLY: no in-repo UI resolves a dispute or
#    closes a complaint today. A staff user with ROLE_ADMIN or ROLE_OPERATOR is granted by
#    base `operator-read-any`; nothing is restated here.
#
# 3. AGENT-SERVICE — reaches this service as HUMAN, **not** AI_AGENT. This is the trap.
#    DisputeServiceClient (openbank-agent-service/.../client/ServiceClients.kt) is annotated
#    `@RegisterProvider(OidcClientRequestReactiveFilter::class)`, so the outbound call carries
#    a **client_credentials** token for the `openbank-services` client. The AI-agent identity
#    (`sub` prefixed `agent:`) exists only inside agent-service's OWN OPA check; it is not
#    propagated on this hop. AuthorizeInterceptor.principalType() returns AI_AGENT only for a
#    `sub` starting with `agent:`, so dispute-service sees
#        {"type": "HUMAN", "id": "service-account-openbank-services", ...}
#    Consequently base rest.rego's `agent-charter-allows` → agents.rego bridge (whose
#    rest_domains map does contain `"query.disputes.readonly": {"dispute", "complaint"}`) is
#    NEVER consulted for a call that actually arrives here. Adding an AI_AGENT rule to this
#    file would be structurally unreachable — do not.
#    Note also that in the CLUSTER realm template `openbank-services` is granted no realm role
#    at all (only `openbank-edge` appears in clientScopeMappings), so these calls are today
#    rejected by the class-level @RolesAllowed before the interceptor ever runs — a separate,
#    pre-existing gap that this PR does not touch and must not paper over with an OPA grant,
#    since OPA is not what denies them. If that account is later granted ROLE_OPERATOR, its
#    reads are covered by `operator-read-any` and its writes are correctly excluded by the
#    service-account guard in the rule below.
#
# 4. SECURITY-SCANNER (namespace `security-scanner`) — a DAST prober, not a functional caller.
#    It probes `/`, `/q/openapi`, `/q/metrics`, `/q/info`, `/q/dev` and the management health
#    port, never `/api/v1/disputes` or `/api/v1/complaints`. Anonymous; expected to be denied.
#    No rule grants it anything.
#
# 5. ROLE_COMPLIANCE / ROLE_AUDITOR — base rest.rego's `compliance-read-any` would grant
#    `.read` to ROLE_COMPLIANCE, but the class-level @RolesAllowed on both resources lists
#    only ROLE_VIEWER/ROLE_OPERATOR/ROLE_ADMIN/ROLE_SERVICE, so such a caller is 403'd by
#    JAX-RS before the interceptor runs. Nothing to do here; noted so the next reader does
#    not "fix" a gap the resource layer already closes.
#
# 6. ROLE_VIEWER — a DELIBERATE NON-GRANT, called out because it looks like an omission.
#    Both resources' class-level @RolesAllowed admits ROLE_VIEWER to every GET, and the
#    seeded demo@openbank.local user holds ROLE_VIEWER and nothing else — but base
#    `operator-read-any` requires ROLE_OPERATOR/ROLE_ADMIN, so OPA denies a pure viewer.
#    This file does NOT widen OPA to match, for three reasons: (a) the admin-ui gates the
#    /disputes page on `compliance:view` = [ADMIN, COMPLIANCE, AUDITOR], so a viewer is not
#    an intended consumer of this data; (b) disputes and ADR-0085 statutory complaints carry
#    customer PII, so granting every viewer would be a widening, not status-quo preservation;
#    (c) the repo's documented posture for exactly this @RolesAllowed-vs-rego divergence
#    (admin-ui/src/lib/auth/roles.ts, ADR-0176 D7 `notifications:view`) is to treat the
#    @RolesAllowed side as the over-broad one and leave the rego narrow. Worth re-confirming
#    against the advisory decision log before the enforce flip: if a real viewer shows up as
#    a would-DENY, narrow @RolesAllowed rather than widening this file.
#
# ENDPOINT → ACTION → GRANTING RULE map (all 14 @Authorize methods):
#
#   DisputeResource (/api/v1/disputes)
#     GET  /                        dispute.list    → operator-read-any (base)  [admin-ui staff]
#     GET  /{id}                    dispute.read    → operator-read-any (base)
#     GET  /reference/{ref}         dispute.read    → operator-read-any (base)
#     GET  /account/{accountId}     dispute.list    → operator-read-any (base)  [customer-edge M2M]
#     GET  /{id}/timeline           dispute.read    → operator-read-any (base)
#     GET  /{id}/evidence           dispute.read    → operator-read-any (base)
#     GET  /{id}/evidence/verify    dispute.read    → operator-read-any (base)
#     PUT  /{id}                    dispute.update  → dispute-staff-write   (THIS FILE)
#     POST /{id}/resolve            dispute.resolve → dispute-staff-write   (THIS FILE)
#
#   ComplaintResource (/api/v1/complaints)
#     GET  /                        complaint.list   → operator-read-any (base)
#     GET  /{id}                    complaint.read   → operator-read-any (base)
#     POST /{id}/interim-reply      complaint.update → dispute-staff-write  (THIS FILE)
#     POST /{id}/resolve            complaint.update → dispute-staff-write  (THIS FILE)
#     POST /{id}/close              complaint.update → dispute-staff-write  (THIS FILE)
#
#   Net: the nine read/list methods need NO rule here — base rest.rego already grants every
#   real caller. This file adds exactly one rule, for the write plane, which base grants to
#   nobody.
# =======================================================================================

package openbank.rest

import rego.v1

# The back-office write plane: resolving a dispute and handling a regulatory complaint.
#
# Base rest.rego grants NO write verb on this namespace (operator-read-any is scoped to
# {list, read}), so without this rule the enforce flip would deny the entire ADR-0085
# statutory complaint workflow — interim reply, resolve, close — plus dispute resolution,
# while the statutory deadline clock kept running.
#
# THE `service-account-` EXCLUSION IS THE LOAD-BEARING PART, not the role check. The
# customer-edge M2M identity `service-account-openbank-edge` is classified HUMAN, the realm
# grants it **ROLE_OPERATOR** (caller-audit item 1), it is admitted through the NetworkPolicy,
# and ROLE_OPERATOR satisfies the method-level @RolesAllowed on every write endpoint here. So
# a rule gated on `HUMAN + ROLE_OPERATOR` ALONE would silently hand the customer-facing edge
# the power to resolve a dispute in the bank's favour or close a statutory complaint — an
# unbounded privilege escalation reachable from a customer-facing service. The
# `not startswith(input.principal.id, "service-account-")` line is what keeps it out, and it
# must not be removed. Same idiom as operator-compose-message / operator-decide-message-approval
# in rest.rego (ADR-0176 D4/D5), used here for the same reason: to distinguish real staff from
# an M2M identity that merely happens to carry the role.
#
# The action set is an explicit three-element set, NOT a `startswith(input.action, "dispute.")`
# prefix: a future write action (e.g. dispute.chargeback or complaint.reopen) must be added
# here consciously, with its own caller evidence, rather than inheriting the staff write grant
# by name. It also keeps ROLE_VIEWER out of the write plane regardless of item 6 above.
allowed_reasons contains "dispute-staff-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action in {
		"dispute.update",
		"dispute.resolve",
		"complaint.update",
	}
}

# NOTE (issue #266 fleet audit, rules.yaml: authz_policy): AuthorizeInterceptor never emits
# principal.type == "SERVICE" — only ANONYMOUS/AI_AGENT/HUMAN — and no realm client is granted
# ROLE_SERVICE, so a SERVICE-gated rule is structurally unreachable dead code. The "ROLE_SERVICE"
# entries in this service's @RolesAllowed lists are exactly that same dead vocabulary one layer
# up: no realm client holds that role, so it grants nobody. The real M2M callers here are
# identified by input.principal.id (Keycloak's `service-account-<clientId>` convention) — and
# they are identified in order to be EXCLUDED from the write plane, never gated on a bare
# HUMAN + ROLE_OPERATOR check, which they would satisfy.
