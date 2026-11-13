// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.libs.governance.ProposalState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "cz_credit_policy_command_receipt")
class CzCreditPolicyCommandReceiptEntity {
    @Id
    @Column(name = "scoped_key_digest")
    lateinit var keyDigest: String

    @Column(name = "request_hash")
    lateinit var requestHash: String

    @Column(name = "proposal_id")
    lateinit var proposalId: UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "response_state")
    lateinit var responseState: ProposalState
}
