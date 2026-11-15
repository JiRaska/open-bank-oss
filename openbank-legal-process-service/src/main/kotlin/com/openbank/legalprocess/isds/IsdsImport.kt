// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.legalprocess.isds

import com.openbank.legalprocess.LegalDeliveryOutcome

/** A mailbox is bound by configuration to one authenticated ISDS connection. */
interface IsdsSignedDownloads {
    fun message(messageId: String): ByteArray

    fun deliveryInfo(messageId: String): ByteArray
}

fun interface IsdsInbox {
    /** Atomically retain both signed originals and the pending review receipt. */
    fun record(
        mailboxNamespace: String,
        messageId: String,
        message: ByteArray,
        delivery: ByteArray,
    ): LegalDeliveryOutcome
}

/** Explicit import of an already known ordinary message. Listing and marking are separate operations. */
class IsdsImport(
    private val mailboxNamespace: String,
    private val downloads: IsdsSignedDownloads,
    private val inbox: IsdsInbox,
) {
    init {
        require(Regex("[a-z0-9][a-z0-9._:-]{0,122}").matches(mailboxNamespace)) { "Invalid ISDS mailbox namespace" }
    }

    fun importMessage(messageId: String): LegalDeliveryOutcome {
        require(ISDS_MESSAGE_ID.matches(messageId)) { "Invalid ISDS message ID" }
        val message = downloads.message(messageId)
        val delivery = downloads.deliveryInfo(messageId)
        return inbox.record(mailboxNamespace, messageId, message, delivery)
    }
}

internal val ISDS_MESSAGE_ID = Regex("[1-9][0-9]{0,18}")
internal const val MAX_SIGNED_EVIDENCE_BYTES = 25 * 1024 * 1024
