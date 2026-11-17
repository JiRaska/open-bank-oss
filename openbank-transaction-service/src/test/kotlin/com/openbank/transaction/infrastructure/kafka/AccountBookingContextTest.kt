// SPDX-License-Identifier: Apache-2.0

package com.openbank.transaction.infrastructure.kafka

import com.openbank.libs.domain.booking.BookingOperatingMode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.transaction.infrastructure.persistence.repository.singleBookingContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AccountBookingContextTest {
    private val mapper = ObjectMapper()

    @Test
    fun `rejects an unknown version and a branch without an establishment`() {
        val context = mapper.createObjectNode()
            .put("schemaVersion", 2)
            .put("operatingMode", "FOS")
        assertThatThrownBy { parseAccountBookingContext(context) }.isInstanceOf(IllegalArgumentException::class.java)
        context.put("schemaVersion", 1).put("operatingMode", "BRANCH")
        assertThatThrownBy { parseAccountBookingContext(context) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only identical scopes can share a single journal context`() {
        val a = AccountBookingContext(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BookingOperatingMode.FOS,
            null,
        )
        val b = a.copy(legalEntityId = UUID.randomUUID())
        assertThat(singleBookingContext(listOf(a, a))).isEqualTo(a)
        assertThat(singleBookingContext(listOf(null, null))).isNull()
        assertThatThrownBy { singleBookingContext(listOf(a, b)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { singleBookingContext(listOf(a, null)) }.isInstanceOf(IllegalStateException::class.java)
    }
}
