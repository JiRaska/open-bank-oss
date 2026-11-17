// SPDX-License-Identifier: Apache-2.0
package com.openbank.finrep.infrastructure.batch

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.mapper.F0101Mapper
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.libs.domain.booking.BookingOperatingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat

internal typealias OperatingMode = BookingOperatingMode

internal data class ReportingScope(
    val contextId: String,
    val legalEntityId: String,
    val mode: OperatingMode,
    val homeCountry: String,
    val hostCountry: String,
    val establishmentId: String?,
    val perimeterVersionId: String,
)

internal data class FrozenFinrepInput(
    val schemaVersion: Int,
    val scope: ReportingScope,
    val asOf: LocalDate,
    val closeEvidenceId: String,
    val trialBalance: TrialBalanceSnapshot,
)

internal data class FrozenFinrepResult(
    val scope: ReportingScope,
    val closeEvidenceId: String,
    val inputSha256: String,
    val template: FinrepTemplate,
)

/** Offline pilot. The expected digest must come from the approved handoff, independently of the input file. */
object FrozenFinrepBatch {
    private const val ARGUMENT_COUNT = 5
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_LINES = 50_000
    private val countryCode = Regex("[A-Z]{2}")
    private val digest = Regex("[0-9a-f]{64}")
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == ARGUMENT_COUNT) {
            "Usage: <snapshot.json> <approved-sha256> <context-id> <as-of-date> <output.json>"
        }
        val arguments = args.iterator()
        val inputPath = arguments.next()
        val expectedDigest = arguments.next()
        val contextId = arguments.next()
        val date = arguments.next()
        val outputPath = arguments.next()
        val bytes = Files.newInputStream(Path.of(inputPath)).use { it.readNBytes(MAX_BYTES + 1) }
        val result = render(bytes, expectedDigest, contextId, LocalDate.parse(date))
        Files.write(Path.of(outputPath), mapper.writeValueAsBytes(result), StandardOpenOption.CREATE_NEW)
    }

    internal fun render(
        bytes: ByteArray,
        expectedDigest: String,
        contextId: String,
        asOf: LocalDate,
    ): FrozenFinrepResult {
        require(bytes.size in 1..MAX_BYTES) { "Snapshot exceeds the batch input budget or is empty" }
        require(digest.matches(expectedDigest)) { "An approved SHA-256 digest is required" }
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        require(actual == expectedDigest) { "Snapshot digest does not match approved evidence" }

        val input = mapper.readValue<FrozenFinrepInput>(bytes)
        val scope = input.scope
        require(input.schemaVersion == 1) { "Unsupported snapshot schema" }
        require(scope.contextId.isNotBlank() && scope.contextId == contextId && input.asOf == asOf) {
            "Snapshot belongs to a different reporting context or date"
        }
        require(scope.legalEntityId.isNotBlank() && scope.perimeterVersionId.isNotBlank()) {
            "Entity and perimeter version are required"
        }
        require(input.closeEvidenceId.isNotBlank()) { "Close evidence is required" }
        require(countryCode.matches(scope.homeCountry) && countryCode.matches(scope.hostCountry))
        when (scope.mode) {
            OperatingMode.DOMESTIC -> require(scope.homeCountry == scope.hostCountry)
            OperatingMode.FOS -> require(scope.homeCountry != scope.hostCountry && scope.establishmentId == null)
            OperatingMode.BRANCH -> require(
                scope.homeCountry != scope.hostCountry && !scope.establishmentId.isNullOrBlank(),
            )
        }
        require(input.trialBalance.lines.size in 1..MAX_LINES) { "Invalid trial balance line count" }
        require(input.trialBalance.lines.all { it.currency == "CZK" }) {
            "The pilot requires a CZK trial balance"
        }
        val template = F0101Mapper.map(input.trialBalance, asOf)
        require(template.isBalanced) { "Producer attestation and accounting identity must agree" }
        return FrozenFinrepResult(scope, input.closeEvidenceId, actual, template)
    }
}
