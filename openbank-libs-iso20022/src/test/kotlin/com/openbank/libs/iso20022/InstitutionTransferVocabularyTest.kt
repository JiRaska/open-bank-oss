// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InstitutionTransferVocabularyTest {
    @Test
    fun `each bank-own rail names its currency and pacs009 clearing system`() {
        assertThat(InstitutionTransferRail.entries.map { Triple(it, it.currency, it.clearingSystem) }).containsExactly(
            Triple(InstitutionTransferRail.CERTIS, "CZK", "CERTIS"),
            Triple(InstitutionTransferRail.TARGET2, "EUR", "TGT"),
            Triple(InstitutionTransferRail.SWIFT, null, null),
        )
    }

    @Test
    fun `only the contract 9_2 statuses are announced`() {
        assertThat(InstitutionTransferStatus.entries.filter { it.announced }).containsExactly(
            InstitutionTransferStatus.ACCEPTED,
            InstitutionTransferStatus.SENT,
            InstitutionTransferStatus.SETTLED,
            InstitutionTransferStatus.REJECTED,
        )
        assertThat(InstitutionTransferStatus.SUBMITTING.announced).isFalse()
    }
}
