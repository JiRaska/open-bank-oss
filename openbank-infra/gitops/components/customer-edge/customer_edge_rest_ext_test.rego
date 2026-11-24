# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package openbank.rest_test

import data.openbank.rest
import rego.v1

connect_input(action, attrs) := {
	"action": action,
	"principal": {"type": "HUMAN", "roles": ["ROLE_CUSTOMER"]},
	"attributes": attrs,
}

test_connect_allows_app_session if {
	every action in {"customer.mcp-connect.read", "customer.mcp-connect.select", "customer.mcp-connect.revoke"} {
		rest.allow with input as connect_input(action, {"token_azp": "openbank-app", "token_act": false})
	}
}

test_connect_denies_other_clients_or_exchanged_tokens if {
	every action in {"customer.mcp-connect.read", "customer.mcp-connect.select", "customer.mcp-connect.revoke"} {
		every attrs in [
			{"token_azp": "third-party-assistant", "token_act": false},
			{"token_azp": "openbank-app", "token_act": true},
			{},
		] {
			not rest.allow with input as connect_input(action, attrs)
		}
	}
}
