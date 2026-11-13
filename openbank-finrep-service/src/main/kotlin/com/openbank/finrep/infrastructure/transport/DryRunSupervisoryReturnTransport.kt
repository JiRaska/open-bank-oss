// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.transport

import com.openbank.finrep.application.port.out.SupervisoryReturnPackage
import com.openbank.finrep.application.port.out.SupervisoryReturnTransport
import com.openbank.finrep.application.port.out.TransportResult
import com.openbank.finrep.domain.model.TransportOutcome
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Default transport: transmits NOTHING and says so with [TransportOutcome.DRY_RUN], which never
 * advances a submission to SUBMITTED. A live regulator adapter replaces this bean
 * (`@DefaultBean`) once the institution holds the channel credentials.
 */
@ApplicationScoped
@DefaultBean
class DryRunSupervisoryReturnTransport : SupervisoryReturnTransport {
    override val name: String = "dry-run"

    override suspend fun transmit(pkg: SupervisoryReturnPackage): TransportResult {
        log.infof(
            "dry-run supervisory transport: nothing transmitted for submission %s (%s %s rev %d)",
            pkg.submissionId,
            pkg.key.templateId,
            pkg.key.referencePeriod,
            pkg.key.revision,
        )
        return TransportResult(
            TransportOutcome.DRY_RUN,
            detail = "no supervisory transport configured; nothing transmitted",
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(DryRunSupervisoryReturnTransport::class.java)
    }
}
