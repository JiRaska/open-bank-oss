# SPDX-License-Identifier: Apache-2.0
# AnaCredit-service REST extension (ADR-0034 Phase 5 bootstrap, issue #938).
# Extends openbank.rest with anacredit-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (AnaCreditResource):
#   anacredit.create  — registerExposure (feed a credit exposure in)
#   anacredit.list    — listAllExposures
#   anacredit.read    — renderReturn (#referenceDate)
#
# Base rest.rego already grants operator-read-any (ROLE_OPERATOR/ROLE_ADMIN) and
# compliance-read-any (ROLE_COMPLIANCE) for anacredit.list/.read — no extension needed for
# those. anacredit.create has no generic base-rego grant (it is a non-resource-scoped write),
# so this extension covers only that gap.
#
# No verified M2M caller exists for anacredit-service (audited: no REST client anywhere in the
# fleet calls it, no NetworkPolicy ingress allow-list beyond the shared platform baseline) — the
# exposure feed is fed in by an operator/compliance officer, not another service. Deliberately
# NOT granting a "service-*" M2M rule here, unlike consent/sca's shared-client carve-outs, since
# there is nothing to carve out for.

package openbank.rest

import rego.v1

# HUMANS ONLY (GHSA-58jq-9hq3-66jr, issue #4228). This IS a write: `@POST
# /api/v1/anacredit/exposures` (AnaCreditResource.registerExposure). Without the exclusion the
# rule was role-only, and `service-account-openbank-services` — the identity nearly every backend
# service authenticates as — carries ROLE_OPERATOR in the docker and CI realms and is classified
# HUMAN by AuthorizeInterceptor, so every backend service could feed the ECB regulatory return.
# anacredit-service runs AUTHZ_ENFORCE=false today, so this was latent rather than live; the
# exclusion lands before the enforce flip rather than after it.
#
# The exclusion strands no caller, and this is the one entry where the audit was already on
# record: the header above states it ("No verified M2M caller exists for anacredit-service") and
# re-confirming it in #4228 found no REST client in the fleet targeting the service. That claim
# is now load-bearing rather than advisory, which is the point of writing the exclusion down.
allowed_reasons contains "operator-anacredit-create" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "anacredit.create"
}
