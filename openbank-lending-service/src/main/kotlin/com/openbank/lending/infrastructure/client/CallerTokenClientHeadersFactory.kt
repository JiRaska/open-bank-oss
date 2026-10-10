// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory

/**
 * Forwards the INBOUND caller's `Authorization` header and nothing else — never a service token.
 *
 * libs-runtime's `BearerTokenClientHeadersFactory` prefers a `ServiceTokenProvider` bean whenever one
 * exists and only falls back to the caller's header, so on a service with an OIDC client (this one)
 * it would silently turn a person's evidence read into a machine's. For the audit-chain evidence
 * read that is exactly what must not happen: audit-service's rule admits humans only, and its access
 * record must name the person who looked (#11900). No inbound header means no outbound header, and
 * audit-service answers 401 — fail closed, by construction.
 */
@ApplicationScoped
class CallerTokenClientHeadersFactory : ClientHeadersFactory {
    override fun update(
        incoming: MultivaluedMap<String, String>,
        outgoing: MultivaluedMap<String, String>,
    ): MultivaluedMap<String, String> {
        val merged = MultivaluedHashMap<String, String>()
        outgoing.forEach { (k, v) -> if (!k.equals(AUTHORIZATION, ignoreCase = true)) merged[k] = v }
        incoming.entries.firstOrNull { it.key.equals(AUTHORIZATION, ignoreCase = true) }
            ?.value?.firstOrNull()?.let { merged.putSingle(AUTHORIZATION, it) }
        return merged
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
