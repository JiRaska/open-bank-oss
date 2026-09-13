// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.`in`.AttestRepresentationCommand
import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.RegistryLookupUseCase
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.application.usecase.RepresentationAttestationService
import com.openbank.kyb.application.usecase.StaleAttestationException
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Per-entity human confirmation, and what happens when the register text moves (#9711). */
class RepresentationAttestationServiceTest {

    private val now = Instant.parse("2026-09-11T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ico = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "27074358")

    private class InMemoryAttestations : RepresentationAttestationRepository {
        val rows = mutableListOf<RepresentationAttestation>()
        override suspend fun findActive(identifier: LegalEntityIdentifier, ruleTextHash: String) =
            rows.firstOrNull { it.identifier == identifier && it.ruleTextHash == ruleTextHash && it.isActive }

        override suspend fun findLatestFor(identifier: LegalEntityIdentifier) =
            rows.filter { it.identifier == identifier && it.isActive }.maxByOrNull { it.attestedAt }

        override suspend fun attest(attestation: RepresentationAttestation): RepresentationAttestation {
            rows.replaceAll {
                if (it.identifier == attestation.identifier &&
                    it.isActive
                ) {
                    it.copy(supersededAt = attestation.attestedAt)
                } else {
                    it
                }
            }
            rows += attestation
            return attestation
        }

        override suspend fun listFor(identifier: LegalEntityIdentifier) =
            rows.filter { it.identifier == identifier }.sortedByDescending { it.attestedAt }
    }

    private class FixedLookup(var extract: RegistryExtract?) : RegistryLookupUseCase {
        override suspend fun lookup(cmd: LookupCommand): RegistryExtract? = extract
    }

    private fun extract(ruleText: String, mode: RepresentationMode = RepresentationMode.SOLE, signers: Int? = 1) =
        RegistryExtract(
            identifier = ico,
            legalName = "Příklad s.r.o.",
            legalFormCode = "112",
            legalFormClass = LegalFormClass.LIMITED_COMPANY,
            status = EntityStatus.ACTIVE,
            registeredAddress = null,
            incorporatedOn = null,
            taxId = null,
            representatives = emptyList(),
            representationRule = RepresentationRule(mode, signers, ruleText),
            source = "ares",
            sourceRef = null,
            verification = ExtractVerification.VERIFIED,
            fetchedAt = now,
        )

    private fun service(lookup: FixedLookup, store: InMemoryAttestations) = RepresentationAttestationService().apply {
        this.attestations = store
        this.lookup = lookup
        this.clock = this@RepresentationAttestationServiceTest.clock
    }

    @Test
    fun `an unconfirmed rule is Unattested and confirming it makes the same text Attested`() {
        val ex = extract("Jednatel jedná samostatně.")
        val store = InMemoryAttestations()
        val svc = service(FixedLookup(ex), store)

        assertThat(runBlocking { svc.decide(ex) }).isInstanceOf(RepresentationDecision.Unattested::class.java)

        runBlocking {
            svc.attest(
                AttestRepresentationCommand(
                    scheme = IdentifierScheme.CZ_ICO,
                    identifier = "27074358",
                    ruleTextHash = RepresentationAttestation.hashOf(ex.representationRule.sourceText),
                    confirmedSigners = 2,
                    confirmedRoles = emptyList(),
                    operator = "operator-anna",
                ),
            )
        }

        val after = runBlocking { svc.decide(ex) }
        assertThat(after).isInstanceOf(RepresentationDecision.Attested::class.java)
        val a = (after as RepresentationDecision.Attested).attestation
        assertThat(a.confirmedSigners).describedAs("the human's number, not the parser's").isEqualTo(2)
        assertThat(a.parsedSigners).describedAs("the parser's opinion is recorded, never used to gate").isEqualTo(1)
        assertThat(a.correctedTheParser).isTrue()
    }

    @Test
    fun `a text differing only in spacing and diacritics is the SAME rule`() {
        val original = extract("Jednatel jedná  samostatně.")
        val store = InMemoryAttestations()
        val svc = service(FixedLookup(original), store)
        runBlocking {
            svc.attest(
                AttestRepresentationCommand(
                    IdentifierScheme.CZ_ICO,
                    "27074358",
                    RepresentationAttestation.hashOf(original.representationRule.sourceText),
                    1,
                    emptyList(),
                    "operator-anna",
                ),
            )
        }

        // A re-fetch the register rendered slightly differently must not force a second review —
        // reviewer fatigue is how a control stops being read.
        val refetched = extract("JEDNATEL JEDNÁ SAMOSTATNĚ.")
        assertThat(runBlocking { svc.decide(refetched) })
            .isInstanceOf(RepresentationDecision.Attested::class.java)
    }

    @Test
    fun `a GENUINELY amended rule is Superseded, carrying the confirmation it invalidates`() {
        val store = InMemoryAttestations()
        val original = extract("Jednatel jedná samostatně.")
        val svc = service(FixedLookup(original), store)
        runBlocking {
            svc.attest(
                AttestRepresentationCommand(
                    IdentifierScheme.CZ_ICO,
                    "27074358",
                    RepresentationAttestation.hashOf(original.representationRule.sourceText),
                    1,
                    emptyList(),
                    "operator-anna",
                ),
            )
        }

        val amended = extract("Jednají vždy dva jednatelé společně.", RepresentationMode.JOINT_N, 2)
        val decision = runBlocking { svc.decide(amended) }

        assertThat(decision).isInstanceOf(RepresentationDecision.Superseded::class.java)
        val s = decision as RepresentationDecision.Superseded
        assertThat(s.previous.confirmedSigners)
            .describedAs("the old count must be shown to the reviewer, and must NOT be reused")
            .isEqualTo(1)
        assertThat(s.previous.attestedBy).isEqualTo("operator-anna")
    }

    @Test
    fun `confirming a hash that no longer matches the register is refused`() {
        val store = InMemoryAttestations()
        val lookup = FixedLookup(extract("Jednatel jedná samostatně."))
        val svc = service(lookup, store)
        val hashOnScreen = RepresentationAttestation.hashOf("Jednatel jedná samostatně.")

        // The register is amended between the form being rendered and the operator pressing save.
        lookup.extract = extract("Jednají vždy dva jednatelé společně.", RepresentationMode.JOINT_N, 2)

        assertThatThrownBy {
            runBlocking {
                svc.attest(
                    AttestRepresentationCommand(
                        IdentifierScheme.CZ_ICO,
                        "27074358",
                        hashOnScreen,
                        1,
                        emptyList(),
                        "operator-anna",
                    ),
                )
            }
        }
            .describedAs("confirming here would attest a rule nobody read")
            .isInstanceOf(StaleAttestationException::class.java)
            .hasMessageContaining("changed")

        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `re-confirming supersedes the previous row rather than replacing it — the history is the audit trail`() {
        val ex = extract("Jednatel jedná samostatně.")
        val store = InMemoryAttestations()
        val svc = service(FixedLookup(ex), store)
        val hash = RepresentationAttestation.hashOf(ex.representationRule.sourceText)
        val cmd =
            AttestRepresentationCommand(IdentifierScheme.CZ_ICO, "27074358", hash, 1, emptyList(), "operator-anna")

        runBlocking {
            svc.attest(cmd)
            svc.attest(cmd.copy(confirmedSigners = 2, operator = "operator-bob", note = "board minutes say two"))
        }

        val history = runBlocking { svc.history(IdentifierScheme.CZ_ICO, "27074358") }
        assertThat(history).hasSize(2)
        assertThat(history.count { it.isActive }).isEqualTo(1)
        assertThat(history.first { it.isActive }.confirmedSigners).isEqualTo(2)
        assertThat(history.first { it.isActive }.attestedBy).isEqualTo("operator-bob")
    }
}
