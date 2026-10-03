// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Shared bounded claim size; each relay preserves sequential acknowledgement and persistence. */
@Startup
@ApplicationScoped
class ContextCommitmentDispatchSettings(
    @ConfigProperty(name = "openbank.context.commitment-export.batch-size", defaultValue = "200")
    val batchSize: Int,
    @ConfigProperty(name = "openbank.context.bank-scope") val bankScope: String,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) { "commitment export batch size must be between 1 and 1000" }
    }
    private companion object {
        const val MAX_BATCH_SIZE = 1000
    }
}
