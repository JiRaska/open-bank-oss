# SPDX-License-Identifier: Apache-2.0
# Context-service REST authorization extension (ADR-0304, ADR-0308).

package openbank.rest

import rego.v1

prohibited if {
	input.action == "context.authorization.read"
	object.get(input.attributes, "rootScopeVerified", false) != true
}

prohibited if {
	input.action == "context.authorization.read"
	object.get(input.attributes, "purpose", "") != "AUTHORIZATION_REVIEW"
}

prohibited if {
	input.action == "context.authorization.read"
	count({r | r := input.principal.roles[_]; r in {"ROLE_COMPLIANCE", "ROLE_ADMIN"}}) == 0
}

prohibited if {
	input.action == "context.aml-case.read"
	object.get(input.attributes, "rootScopeVerified", false) != true
}

prohibited if {
	input.action == "context.aml-case.read"
	object.get(input.attributes, "purpose", "") != "AML_INVESTIGATION"
}

prohibited if {
	input.action == "context.aml-case.read"
	count({r | r := input.principal.roles[_]; r in {"ROLE_COMPLIANCE", "ROLE_ADMIN"}}) == 0
}

# Assignment administration changes who can see restricted banking context. Only a human
# administrator may perform these exact lifecycle actions; the service persists an independent
# maker/checker decision and immediately expires a revoked assignment.
allowed_reasons contains "context-assignment-admin" if {
	input.principal.type == "HUMAN"
	"ROLE_ADMIN" in input.principal.roles
	not startswith(input.principal.id, "service-account-")
	input.action in {
		"context.assignment.propose",
		"context.assignment.read",
		"context.assignment.decide",
		"context.assignment.revoke",
	}
}

prohibited if {
	startswith(input.action, "context.assignment.")
	startswith(input.principal.id, "service-account-")
}

# Reads remain purpose-bound even if a broad role rule also allows *.read. This is a second
# boundary behind context-service's server-side assignment check.
prohibited if {
	input.action == "context.complaint.read"
	object.get(input.attributes, "assignmentVerified", false) != true
}

prohibited if {
	input.action == "context.complaint.read"
	object.get(input.attributes, "rootScopeVerified", false) != true
}

prohibited if {
	input.action == "context.complaint.read"
	object.get(input.attributes, "purpose", "") != "PAYMENT_COMPLAINT"
}

prohibited if {
	input.action == "context.incident.aggregate.read"
	object.get(input.attributes, "assignmentVerified", false) != true
}

prohibited if {
	input.action == "context.incident.aggregate.read"
	object.get(input.attributes, "rootScopeVerified", false) != true
}

prohibited if {
	input.action == "context.incident.aggregate.read"
	object.get(input.attributes, "purpose", "") != "INCIDENT_IMPACT"
}

prohibited if {
	startswith(input.action, "context.")
	startswith(input.principal.id, "service-account-")
}
