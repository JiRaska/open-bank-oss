// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

data class CustomerMcpConnectSelection(val accounts: List<CustomerMcpConnectAccountScope>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerMcpConnectAccountScope(val accountId: UUID, val capabilities: List<String>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerMcpConnectRequests(val requests: List<CustomerMcpConnectReview>)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CustomerMcpConnectReview(
    val requestRef: String,
    val partyId: UUID,
    val clientId: String,
    val redirectHost: String,
    val resource: String,
    val scopes: List<String>,
    val parJkt: String,
    val expiresAtEpochSecond: Long,
    val status: String,
    val accounts: List<CustomerMcpConnectAccountScope>,
    val challengeId: UUID? = null,
    val approvalRequestId: String? = null,
    val contextSha256: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerMcpConnections(val connections: List<CustomerMcpConnection>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomerMcpConnection(
    val connectionId: UUID,
    val partyId: UUID,
    val clientId: String,
    val resource: String,
    val expiresAtEpochSecond: Long,
    val status: CustomerMcpConnectionStatus,
    val accounts: List<CustomerMcpConnectAccountScope>,
)

enum class CustomerMcpConnectionStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
}
