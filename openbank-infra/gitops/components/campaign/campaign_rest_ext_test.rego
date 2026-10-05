# SPDX-License-Identifier: Apache-2.0
# Run with the base policy and campaign_rest_ext.rego only.

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "operator-1", "roles": ["ROLE_OPERATOR"]}
admin := {"type": "HUMAN", "id": "admin-1", "roles": ["ROLE_ADMIN"]}
auditor := {"type": "HUMAN", "id": "auditor-1", "roles": ["ROLE_AUDITOR"]}
viewer := {"type": "HUMAN", "id": "viewer-1", "roles": ["ROLE_VIEWER"]}
edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}
other_service := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

staff_writes := {
	"campaign.create", "campaign.submit", "campaign.pause", "campaign.resume",
	"campaign.close", "campaign.enrol", "campaign.activate",
}

# All currently annotated campaign actions are represented here. The staff rule
# never grants a service account a mutation, even if its token carries ROLE_OPERATOR.
test_staff_write_actions if {
	every action in staff_writes {
		"campaign-staff-write" in allowed_reasons with input as {"principal": operator, "action": action}
		"campaign-staff-write" in allowed_reasons with input as {"principal": admin, "action": action}
		not allow.allow with input as {"principal": viewer, "action": action}
		not allow.allow with input as {"principal": edge, "action": action}
		not allow.allow with input as {"principal": other_service, "action": action}
	}
}

test_staff_and_auditor_read if {
	allow.allow with input as {"principal": operator, "action": "campaign.read"}
	allow.allow with input as {"principal": admin, "action": "campaign.read"}
	allow.allow with input as {"principal": auditor, "action": "campaign.read"}
	not allow.allow with input as {"principal": viewer, "action": "campaign.read"}
	not allow.allow with input as {"principal": edge, "action": "campaign.read"}
}

test_edge_can_validate_only_interaction if {
	allow.allow with input as {"principal": edge, "action": "campaign.interaction.validate"}
	not allow.allow with input as {"principal": other_service, "action": "campaign.interaction.validate"}
	not allow.allow with input as {"principal": viewer, "action": "campaign.interaction.validate"}
	not allow.allow with input as {"principal": operator, "action": "campaign.interaction.validate"}
}
