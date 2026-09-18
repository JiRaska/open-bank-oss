// SPDX-License-Identifier: Apache-2.0
package com.openbank.fraud.application.port.out

import java.util.UUID

enum class FraudCaseAccessDecision { ALLOWED, DENIED, UNAVAILABLE }

/** Verifies the caller's live assignment to this exact case before source details leave Fraud. */
interface FraudCaseContextAccess {
    suspend fun check(caseId: UUID, bearer: String): FraudCaseAccessDecision
}
