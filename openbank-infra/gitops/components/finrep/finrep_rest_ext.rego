# SPDX-License-Identifier: Apache-2.0
package openbank.rest

import rego.v1

# The regulatory cockpit relays staff tokens. Explicit inspection actions and an
# unscoped request keep this grant separate from the shared read and tenant grants.
allowed_reasons contains "staff-finrep-inspection" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_ADMIN", "ROLE_OPERATOR"}
	role in input.principal.roles
	input.action in {
		"finrep.template.inspect",
		"finrep.corep.inspect",
		"finrep.periods.inspect",
		"finrep.preflight.inspect",
	}
}
