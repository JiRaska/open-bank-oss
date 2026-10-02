// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** #9430: reference binding is opt-in at challenge creation, strict once opted in. */
class ScaChallengeReferenceBindingTest {

    private val grant = ScaPurpose.DELEGATION_GRANT

    private fun linking(stored: String?) = DynamicLinkingData(null, null, null, null, stored)

    private fun DynamicLinkingData.consumedWith(reference: String?, purpose: ScaPurpose = grant) =
        authorises(null, null, null, reference = reference, purpose = purpose)

    @Test
    fun `legacy grant challenge without a reference accepts a consumer that supplies one`() {
        assertThat(linking(null).consumedWith("grant:v1:abc")).isTrue()
    }

    @Test
    fun `bound grant challenge accepts the same reference`() {
        assertThat(linking("grant:v1:abc").consumedWith("grant:v1:abc")).isTrue()
    }

    @Test
    fun `bound grant challenge refuses a different reference`() {
        assertThat(linking("grant:v1:abc").consumedWith("grant:v1:xyz")).isFalse()
    }

    @Test
    fun `bound grant challenge refuses a missing reference`() {
        assertThat(linking("grant:v1:abc").consumedWith(null)).isFalse()
    }

    @Test
    fun `payment remittance reference is not restated by payment consumers`() {
        val payment = linking("invoice 42")
        assertThat(payment.consumedWith(null, ScaPurpose.PAYMENT_INITIATION)).isTrue()
        assertThat(payment.consumedWith("invoice 43", ScaPurpose.PAYMENT_INITIATION)).isFalse()
    }
}
