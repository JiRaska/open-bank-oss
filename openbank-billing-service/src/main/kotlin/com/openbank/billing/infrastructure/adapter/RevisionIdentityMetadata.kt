// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.billing.domain.ShadowRevisionIdentity
import com.openbank.libs.productrevision.RevisionIdentity
import java.util.UUID

/** Optional metadata must not turn a legacy fee assessment into a catalog outage. */
internal object RevisionIdentityMetadata {
    fun parse(
        revisionId: String,
        number: JsonNode?,
        behaviour: JsonNode?,
        signature: JsonNode?,
        state: String?,
    ): ShadowRevisionIdentity? = runCatching {
        require(state == "PUBLISHED" || state == "SUPERSEDED")
        require(number != null && number.isIntegralNumber && number.canConvertToLong())
        val version = behaviour?.get("irVersion")
        require(version != null && version.isIntegralNumber && version.canConvertToInt())
        val code = behaviour.get("productCode")
        val hash = signature?.get("revisionHash")
        require(code != null && code.isTextual && hash != null && hash.isTextual)
        val identity =
            RevisionIdentity(
                code.asText(),
                UUID.fromString(revisionId),
                number.longValue(),
                version.intValue(),
                hash.asText(),
            )
        ShadowRevisionIdentity(
            identity.productCode,
            identity.revisionId.toString(),
            identity.revisionNumber,
            identity.irVersion,
            identity.revisionHash,
        )
    }.getOrNull()
}
