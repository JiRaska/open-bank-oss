# SPDX-License-Identifier: Apache-2.0
# Interest-service REST extension (ADR-0034 Phase 5, issue #938; tightened in the #3679
# follow-up). Extends openbank.rest with interest-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (InterestResource, WithholdingRemittanceResource):
#   interest.create  — accrue, capitalize (#accountId), createRateConfig, assemble (withholding)
#   interest.trigger — accrueAll (daily batch accrual)
#   interest.delete  — deactivateRateConfig (#id)
#   interest.list    — listAllAccruals, listRateConfigs, list (withholding)
#   interest.read    — getAccruals/getSummary/getCapitalizations/getRateConfig (#id), get (withholding)
#
# Base rest.rego already grants operator-read-any (ROLE_OPERATOR/ROLE_ADMIN) and
# compliance-read-any (ROLE_COMPLIANCE) for interest.list/.read — no extension needed for those.
#
# No legitimate M2M writer exists: audited the fleet for callers of interest-service —
# agent-service's InterestServiceClient is read-only (listAccruals/getAccruals), and accrueAll's
# documented "external scheduler" does not exist (no @Scheduled annotation, no CronJob in gitops
# or in-cluster). interest.create/.trigger/.delete are invoked synchronously by admin-ui
# operators only.
#
# Tightening (#3679 follow-up): the fleet caller audit answered "no M2M writer", but two service
# accounts carry ROLE_OPERATOR in the realm — service-account-openbank-services (shared backend
# client) and service-account-openbank-edge (customer edge) — and BOTH could reach the writes:
# this rule used to be role-only, and the rules.yaml role_action_matrix grants ROLE_OPERATOR the
# interest writes, so matrix-allows admitted them even had this rule excluded service accounts.
# interest is money-path (posts real GL journals, #1478) and customer-edge is customer-reachable,
# so a role-only path is not acceptable here. Two layers, both needed:
#   1. the allow rule below excludes every service account outright (delegation idiom), and
#   2. the prohibition vetoes the write actions for ANY service account at the allow head —
#      unreachable via matrix-allows or any future reason.
# Reads are untouched: the edge legitimately serves customer interest views.

package openbank.rest

import rego.v1

allowed_reasons contains "operator-interest-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action in {"interest.create", "interest.trigger", "interest.delete"}
}

# No M2M caller of interest writes exists (fleet audit above), so this loses no legitimate
# caller and fails closed for any FUTURE service account too. Keyed on the action set, not a
# principal allowlist — a new backend client must never silently gain interest writes.
prohibited if {
	startswith(input.principal.id, "service-account-")
	input.action in {"interest.create", "interest.trigger", "interest.delete"}
}
