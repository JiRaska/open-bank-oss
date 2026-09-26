// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

import com.openbank.settlement.domain.model.SettlementStatus

/** The stable v1 response vocabulary, independent of internal recovery states. */
enum class SettlementResponseStatus {
    PENDING,
    DEBITED,
    CREDITED,
    BOOKED,
    REJECTED,
    REVERSED,
    CREDITED_REVERSED,
    REVERSAL_FAILED,
    LEDGER_REVERSAL_UNSUPPORTED,
    LEDGER_NOT_POSTED,
    LEDGER_STATE_UNKNOWN,
    LEDGER_REVERSED,
    ;

    companion object {
        fun fromDomain(status: SettlementStatus): SettlementResponseStatus = when (status) {
            SettlementStatus.PENDING, SettlementStatus.BALANCE_STATE_UNKNOWN -> PENDING
            SettlementStatus.DEBITED -> DEBITED
            SettlementStatus.CREDITED -> CREDITED
            SettlementStatus.BOOKED -> BOOKED
            SettlementStatus.REJECTED -> REJECTED
            SettlementStatus.REVERSED -> REVERSED
            SettlementStatus.CREDITED_REVERSED -> CREDITED_REVERSED
            SettlementStatus.REVERSAL_FAILED -> REVERSAL_FAILED
            SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED -> LEDGER_REVERSAL_UNSUPPORTED
            SettlementStatus.LEDGER_NOT_POSTED -> LEDGER_NOT_POSTED
            SettlementStatus.LEDGER_STATE_UNKNOWN -> LEDGER_STATE_UNKNOWN
            SettlementStatus.LEDGER_REVERSED -> LEDGER_REVERSED
        }
    }
}
