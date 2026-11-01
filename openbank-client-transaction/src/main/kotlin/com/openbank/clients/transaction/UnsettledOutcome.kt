// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clients.transaction

/**
 * A bounded `outcome` label value of the `openbank.payment.unsettled` counter (#13669).
 *
 * Rails that need values beyond the shared [UnsettledOutcome] set declare their own enum implementing
 * this interface, so the shared vocabulary is written once and a rail extension cannot redefine it.
 */
interface UnsettledOutcomeLabel {
    /** The wire value of the `outcome` tag. Changing it renames a Prometheus series and breaks alerts. */
    val label: String
}

/**
 * Why a transaction-service booking attempt did not prove a settlement — the outcome vocabulary every
 * rail calling [TransactionClient] shares. Labels carry no payment identity or provider text.
 */
enum class UnsettledOutcome(override val label: String) : UnsettledOutcomeLabel {
    MISMATCH("mismatch"),

    // A FAILED response, including a replay; this label does not prove the request was replayed.
    FAILED_REPLAY("failed-replay"),
    CONFLICT("conflict"),
    UNEXPECTED_STATUS("unexpected-status"),
    TRANSPORT_ERROR("transport-error"),
    ;

    companion object {
        /** Micrometer name of the counter these values label. */
        const val METRIC: String = "openbank.payment.unsettled"
    }
}
