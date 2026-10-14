// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.AgencyDocument
import com.openbank.pension.application.port.out.StateAgencyGateway
import com.openbank.pension.application.port.out.TransmissionReceipt
import com.openbank.pension.application.port.out.TransmissionStatus
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The production [StateAgencyGateway]. It is a manual handoff and **transmits nothing**. MF's
 * channel and file specification are not public (research T2). The document is kept as batch or
 * report evidence, and an operator files it through MF's own channel. The answer is
 * [TransmissionStatus.AWAITING_MANUAL_FILING] with a content-hash reference (`MANUAL:sha256:…`), so
 * no reader can take it for a delivery. When MF's transport is specified, an SFTP or API adapter
 * replaces this class behind the same port.
 */
@ApplicationScoped
@UnlessBuildProfile(anyOf = ["dev", "test"])
class ManualHandoffStateAgencyGateway : StateAgencyGateway {
    private val log = Logger.getLogger(ManualHandoffStateAgencyGateway::class.java)

    override suspend fun transmit(document: AgencyDocument): TransmissionReceipt {
        val reference = manualReference(document)
        log.infof(
            "state agency %s %s NOT transmitted (no public MF channel); awaiting manual filing as %s",
            document.kind,
            document.fileName,
            reference,
        )
        return TransmissionReceipt(TransmissionStatus.AWAITING_MANUAL_FILING, reference)
    }
}

/** dev/test only: records each document so tests and the E2E simulator can read what was "sent". */
@ApplicationScoped
@IfBuildProfile(anyOf = ["dev", "test"])
class RecordingStateAgencyGateway : StateAgencyGateway {
    val sent: MutableList<AgencyDocument> = CopyOnWriteArrayList()

    override suspend fun transmit(document: AgencyDocument): TransmissionReceipt {
        sent += document
        return TransmissionReceipt(TransmissionStatus.AWAITING_MANUAL_FILING, manualReference(document))
    }
}

internal fun manualReference(document: AgencyDocument): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(document.payload.toByteArray(Charsets.UTF_8))
    return "MANUAL:sha256:" + hash.joinToString("") { "%02x".format(it) }
}
