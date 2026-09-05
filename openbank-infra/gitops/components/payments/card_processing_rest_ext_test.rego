# SPDX-License-Identifier: Apache-2.0
# Tests for card_processing_rest_ext.rego (ADR-0283 phase 1, #8809).
#
# A policy test that only asserts ALLOW is worth very little: the interesting property of an
# authorisation rule is what it REFUSES, and a rule that allowed everything would pass such a suite
# unchanged. Every allow below therefore has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

operator := {"type": "HUMAN", "roles": ["ROLE_OPERATOR"], "id": "service-account-openbank-services"}

admin := {"type": "HUMAN", "roles": ["ROLE_ADMIN"], "id": "admin@openbank.local"}

viewer := {"type": "HUMAN", "roles": ["ROLE_VIEWER"], "id": "viewer@openbank.local"}

anonymous := {"type": "ANONYMOUS", "roles": [], "id": ""}

test_operator_may_authorize if {
	"operator-cardprocessing-write" in rest.allowed_reasons with input as {"principal": operator, "action": "cardprocessing.authorize"}
}

test_operator_may_clear_and_reverse if {
	"operator-cardprocessing-write" in rest.allowed_reasons with input as {"principal": operator, "action": "cardprocessing.clear"}
	"operator-cardprocessing-write" in rest.allowed_reasons with input as {"principal": operator, "action": "cardprocessing.reverse"}
}

test_viewer_may_not_authorize if {
	not "operator-cardprocessing-write" in rest.allowed_reasons with input as {"principal": viewer, "action": "cardprocessing.authorize"}
}

test_anonymous_may_not_authorize if {
	not "operator-cardprocessing-write" in rest.allowed_reasons with input as {"principal": anonymous, "action": "cardprocessing.authorize"}
}

test_viewer_may_read if {
	"operator-card-processing-read" in rest.allowed_reasons with input as {"principal": viewer, "action": "cardprocessing.read"}
}

# The sandbox can move money end to end, so ROLE_OPERATOR is NOT enough — the one place where the
# simulator's grant is deliberately narrower than the real path's.
test_operator_may_not_simulate if {
	not "admin-card-processing-simulate" in rest.allowed_reasons with input as {"principal": operator, "action": "cardprocessing.simulate"}
}

test_admin_may_simulate if {
	"admin-card-processing-simulate" in rest.allowed_reasons with input as {"principal": admin, "action": "cardprocessing.simulate"}
}

# A read grant must not leak into a write: the reasons are separate rules and must stay separate.
test_read_reason_does_not_cover_write if {
	not "operator-card-processing-read" in rest.allowed_reasons with input as {"principal": operator, "action": "cardprocessing.authorize"}
}
