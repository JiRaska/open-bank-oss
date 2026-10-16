# SPDX-License-Identifier: Apache-2.0
# Product-catalog REST extension (ADR-0034, issue #12823).
# Extends openbank.rest with product-catalog allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (ProductCatalogResource, FeesResource, GenericCatalogResource,
# CatalogEventCursorResource, PensionRevisionApprovalResource):
#   catalog.read / catalog.list   — product, fee, offering and revision reads
#   catalog.create / catalog.update / catalog.author — maker-side authoring
#   catalog.publish               — checker publishes an approved revision
#   catalog.pensionApproval.*     — granted by base rest.rego (pension-catalog-* reasons)
#
# These reasons mirror the endpoints' @RolesAllowed sets, which remain the outer gate and are
# narrower per endpoint (e.g. GenericCatalogResource reads admit no ROLE_API). Maker/checker
# separation is enforced by the service itself and never depends on this policy.
#
# Base rest.rego already grants operator-read-any for *.read / *.list (ROLE_OPERATOR, ROLE_ADMIN).

package openbank.rest

import rego.v1

catalog_read_actions := {"catalog.read", "catalog.list"}

catalog_author_actions := {"catalog.create", "catalog.update", "catalog.author"}

# ROLE_VIEWER is the read-only human staff role ProductCatalogResource / FeesResource admit.
# ROLE_API alone is deliberately insufficient: the same catalog.read action also protects
# generic offerings, so a blanket API grant would bypass main's named pension caller +
# CATALOG_SCOPE_READ boundary. API clients need an existing named, scoped grant instead.
allowed_reasons contains "catalog-read-staff-viewer" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	input.action in catalog_read_actions
	"ROLE_VIEWER" in input.principal.roles
}

# The synthetic catalog-read journey holds only the catalog:read scope (CATALOG_SCOPE_READ).
# The scope alone is deliberately not a general grant (see the pension-catalog-* reasons), so
# only this named caller is admitted on it. Its client issues the profile scope, so the token
# carries preferred_username and principal.id resolves to the service-account name.
allowed_reasons contains "catalog-read-synthetic-journey" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-synthetic-catalog-read")

	# Only an absent legacy marker may fall back to the name; explicit null is malformed.
	not input.principal.service_account == null
	"CATALOG_SCOPE_READ" in input.principal.roles
	input.action in catalog_read_actions
}

# Authoring and publishing are staff actions. A service-account that happens to carry
# ROLE_OPERATOR (the edge client does in the deployed realm) is never a catalog author.
allowed_reasons contains "operator-catalog-author-write" if {
	catalog_staff
	input.action in catalog_author_actions
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "CATALOG_SCOPE_AUTHOR"}
	role in input.principal.roles
}

allowed_reasons contains "operator-catalog-publish-write" if {
	catalog_staff
	input.action == "catalog.publish"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "CATALOG_SCOPE_PUBLISH"}
	role in input.principal.roles
}

catalog_staff if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	not startswith(input.principal.id, "agent:")
}
