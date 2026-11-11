// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.report

import com.openbank.libs.governance.Proposal
import java.util.UUID

/** How a monitoring case may be closed. A hold is not one of them (ADR-0371 §9). */
enum class CaseDisposition { CLEAR, REPORT }

/** What a second, different person is asked to approve for one monitoring case. */
data class DispositionRequest(val caseId: UUID, val disposition: CaseDisposition, val outcome: OutcomeLabel, val note: String?)

/** The fleet maker-checker proposal (`libs/governance`) carrying a [DispositionRequest]. */
typealias DispositionProposal = Proposal<DispositionRequest>
