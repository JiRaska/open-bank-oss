// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming

@ApplicationScoped
class AuthorityHistoryConsumer(
    private val decoder: AuthorityEventDecoder,
    private val history: AuthorityHistoryRepository,
    private val meters: MeterRegistry,
) {
    @Incoming("delegation-history-in")
    suspend fun consume(payload: String) {
        var outcome = "failed"
        try {
            val evidence = decoder.decode(payload)
            if (evidence == null) {
                outcome = "ignored"
                return
            }
            history.append(evidence)
            outcome = "recorded"
        } finally {
            meters.counter("openbank_context_authority_events", "outcome", outcome).increment()
        }
    }
}
