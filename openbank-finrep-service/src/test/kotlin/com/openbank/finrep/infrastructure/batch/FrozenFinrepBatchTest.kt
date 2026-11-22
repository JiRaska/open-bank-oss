// SPDX-License-Identifier: Apache-2.0
package com.openbank.finrep.infrastructure.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat

class FrozenFinrepBatchTest {
    private val date = LocalDate.parse("2026-09-30")

    @Test
    fun `FOS and branch inputs stay scoped even for the same bank and date`() {
        val fos = snapshot("bank-a-fos", "bank-a", "FOS", "null")
        val branch = snapshot("bank-a-branch", "bank-a", "BRANCH", "\"branch-a\"")
        val other = snapshot("bank-b-fos", "bank-b", "FOS", "null")

        for ((context, bytes) in listOf("bank-a-fos" to fos, "bank-a-branch" to branch, "bank-b-fos" to other)) {
            val result = FrozenFinrepBatch.render(bytes, digest(bytes), context, date)
            assertEquals(context, result.scope.contextId)
            assertEquals(BigDecimal("42"), result.template.cells.single { it.rowRef == "r0380" }.value)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenFinrepBatch.render(branch, digest(branch), "bank-a-fos", date)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenFinrepBatch.render(other, digest(other), "bank-a-fos", date)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenFinrepBatch.render(branch, digest(fos), "bank-a-branch", date)
        }
    }

    private fun snapshot(context: String, bank: String, mode: String, establishment: String): ByteArray = """
        {
          "schemaVersion": 1,
          "scope": {
            "contextId": "$context", "legalEntityId": "$bank", "mode": "$mode",
            "homeCountry": "AT", "hostCountry": "CZ", "establishmentId": $establishment,
            "perimeterVersionId": "solo-v1"
          },
          "asOf": "2026-09-30",
          "closeEvidenceId": "fixture-close",
          "trialBalance": {
            "ledgerReportsBalanced": true,
            "lines": [
              {"code":"1510","accountType":"ASSET","net":42,"currency":"CZK"},
              {"code":"6000","accountType":"EQUITY","net":-42,"currency":"CZK"}
            ]
          }
        }
    """.trimIndent().encodeToByteArray()

    private fun digest(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
