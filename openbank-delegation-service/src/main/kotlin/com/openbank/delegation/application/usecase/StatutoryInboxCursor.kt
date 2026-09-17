// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A position in one immutable rule snapshot, never an authorization token. */
internal object StatutoryInboxCursor {
    fun encode(ruleHash: String, operation: StatutoryDelegationOperation): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1\n$ruleHash\n${operation.createdAt}\n${operation.id}".toByteArray(Charsets.UTF_8),
        )

    fun decode(value: String, ruleHash: String): Pair<Instant, UUID> {
        require(value.isNotBlank() && value.length <= MAX_CURSOR_LENGTH) { "invalid inbox cursor" }
        return runCatching {
            val fields = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('\n')
            require(fields.size == CURSOR_FIELDS)
            val version = fields[VERSION_INDEX]
            val encodedRuleHash = fields[RULE_HASH_INDEX]
            val createdAt = fields[CREATED_AT_INDEX]
            val id = fields[ID_INDEX]
            require(version == "v1" && encodedRuleHash == ruleHash)
            Instant.parse(createdAt) to UUID.fromString(id)
        }.getOrElse { throw IllegalArgumentException("invalid inbox cursor") }
    }

    private const val MAX_CURSOR_LENGTH = 256
    private const val CURSOR_FIELDS = 4
    private const val VERSION_INDEX = 0
    private const val RULE_HASH_INDEX = 1
    private const val CREATED_AT_INDEX = 2
    private const val ID_INDEX = 3
}
