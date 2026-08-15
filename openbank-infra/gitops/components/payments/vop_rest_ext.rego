# SPDX-License-Identifier: Apache-2.0
# VoP REST extension (ADR-0171 — Verification of Payee, Reg. (EU) 2024/886 Art. 5c).
# Extends openbank.rest with the vop-domain allow reason.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Action gated (VopResource):
#   vop.verify — check a payee name against an IBAN (POST /api/v1/vop/verify)
#
# The action prefix is deliberately `vop`, matching the module name openbank-vop-service, so
# `money_path_scopes` in the base rest.rego derives "vop" and actually matches. Contrast
# gen-sepa-instant-opa-bundle.sh, whose real prefix is `sctInstPayment` while the derived scope
# is "sepa-instant" — a mismatch that silently stops four_eyes_required firing for that rail
# (issue #395). Naming this `vop.create` or `payeeVerification.*` would reintroduce that bug.
#
# Base rest.rego grants operator-read-any / compliance-read-any for *.read + *.list. `vop.verify`
# is neither, so it has no base coverage — that is the gap this extension fills.
#
# NOTE on why this is a *write-shaped* action with a read's consequences: vop.verify mints
# nothing and changes no money state, but it is not a `*.read` either — it is a name oracle
# (docs/threat-models/openbank-vop-service.md). Authorization deliberately does NOT try to bound
# the oracle: a payer must be able to check a payee they do not own, so any read-role holder may
# call it. The enumeration control is the per-requester rate limit (VopRateLimitFilter) plus the
# response asymmetry (never echo a name on NO_MATCH), not this rule.

package openbank.rest

import rego.v1

# Operators, admins, the payments desk and viewers may verify a payee. The admin-ui payments
# console initiates with the signed-in operator's OWN bearer token (BFF pattern, ADR-0080 P1).
# ROLE_VIEWER is included because verification is a pre-payment check, not a money movement —
# the same role set the resource's own @RolesAllowed declares.
#
# HUMANS ONLY (GHSA-58jq-9hq3-66jr, issue #4228). Without the exclusion this rule is role-only,
# and `service-account-openbank-services` — the identity nearly every backend service
# authenticates as — carries ROLE_OPERATOR and is classified HUMAN by AuthorizeInterceptor, so it
# reached vop.verify through the OPERATOR branch. That reach was pure over-grant here, because the
# rails have their own identity-pinned reason directly below: measured against this bundle, the
# shared account resolved BOTH ["m2m-vop-verify", "operator-vop-verify"], so removing the second
# strands no caller. Unlike the sibling debt entries this one needed no caller audit — the
# legitimate M2M caller was already named by its own rule.
#
# Do NOT "simplify" this back by deleting m2m-vop-verify and relying on ROLE_OPERATOR: that
# re-creates the exposure, and it would then also admit every OTHER service-account holding the
# role, not just the payment rails.
allowed_reasons contains "operator-vop-verify" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	input.action == "vop.verify"
}

# M2M: the payment rails verify a payee before execution. Gated on the Keycloak
# client_credentials convention (`service-account-*` preferred_username), which the
# AuthorizeInterceptor classifies as HUMAN — there is no SERVICE principal type, and a rule gated
# on one is unreachable dead code (rules.yaml: authz_policy.principal_type_service_unreachable).
#
# Deliberately scoped to vop.verify ONLY, never a `vop.` family prefix: this is the sole action
# today, and a family grant would silently pre-authorise any future write/config action (e.g. a
# threshold flip, which the threat model flags as the change with real fraud consequence).
#
# Deliberately NOT scoped to one hardcoded client id: more than one rail legitimately verifies a
# payee (sepa-instant, sepa-payment, domestic-payment, psd2), and unlike device.enroll this is not
# an account-takeover primitive. It IS a name oracle, which is why the rate limit — not a
# per-caller allow-list — is the control that bounds it.
allowed_reasons contains "m2m-vop-verify" if {
	input.principal.type == "HUMAN"
	startswith(input.principal.id, "service-account-")
	input.action == "vop.verify"
}
