# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package openbank.rest

import rego.v1

# The general customer allowance admits human customers-realm tokens. Connect review/selection
# needs the first-party app as well, independently of the resource's audience and profile checks.
prohibited if {
	startswith(input.action, "customer.mcp-connect.")
	not customer_app_session
}

mcp_native_app_session if {
	input.attributes.token_azp in {"openbank-app", "openbank-edge-webauthn"}
	input.attributes.token_act == false
}

prohibited if {
	input.action in {"customer.mcp-native-connect.claim", "customer.mcp-native-connect.prove", "customer.mcp-native-connect.read", "customer.mcp-native-connect.select"}
	not mcp_native_app_session
}
