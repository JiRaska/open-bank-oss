// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationSink
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.CopyOnWriteArrayList

/** Every @QuarkusTest: day-close reports are recorded here instead of reaching a ledger. */
@Alternative
@Priority(1)
@ApplicationScoped
class RecordingCloseSink : CloseParticipationSink {
    override fun deliver(participant: String, report: CloseParticipationReport): Uni<Unit> {
        delivered += participant to report
        return Uni.createFrom().item(Unit)
    }

    companion object {
        val delivered = CopyOnWriteArrayList<Pair<String, CloseParticipationReport>>()
    }
}
