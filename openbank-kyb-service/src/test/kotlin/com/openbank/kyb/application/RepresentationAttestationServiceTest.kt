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
import com.openbank.kyb.domain.model.Representative
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

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
        override suspend fun cached(cmd: LookupCommand): RegistryExtract? = extract
    }

    private fun extract(
        ruleText: String,
        mode: RepresentationMode = RepresentationMode.SOLE,
        signers: Int? = 1,
        members: List<String> = emptyList(),
    ) = RegistryExtract(
        identifier = ico,
        legalName = "Příklad s.r.o.",
        legalFormCode = "112",
        legalFormClass = LegalFormClass.LIMITED_COMPANY,
        status = EntityStatus.ACTIVE,
        registeredAddress = null,
        incorporatedOn = null,
        taxId = null,
        representatives = members.map { Representative(it, null, "jednatelé", "jednatel", null) },
        representationRule = RepresentationRule(mode, signers, ruleText),
        source = "ares",
        sourceRef = null,
        verification = ExtractVerification.VERIFIED,
        fetchedAt = now,
    )

    private fun service(lookup: FixedLookup, store: InMemoryAttestations, autoConfirm: Boolean = true) =
        RepresentationAttestationService().apply {
            this.autoConfirmSingleMember = Optional.of(autoConfirm)
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

    // --- the single-member exception -----------------------------------------------------------

    private val soleText = "Za společnost jedná jednatel samostatně."

    @Test
    fun `one statutory member and a SOLE rule is confirmed by the system and persisted`() {
        val ex = extract(soleText, members = listOf("Oldřich Vaněk"))
        val store = InMemoryAttestations()
        val decision = runBlocking { service(FixedLookup(ex), store).decide(ex) }

        assertThat(decision).isInstanceOf(RepresentationDecision.Attested::class.java)
        val a = (decision as RepresentationDecision.Attested).attestation
        assertThat(a.attestedBy).isEqualTo("system:single-statutory-member")
        assertThat(a.confirmedSigners).isEqualTo(1)
        assertThat(a.confirmedRoles).isEmpty()
        assertThat(a.parsedMode).isEqualTo(RepresentationMode.SOLE)
        assertThat(a.parsedSigners).isEqualTo(1)
        assertThat(a.note).contains("exactly one member")
        assertThat(a.attestedAt).isEqualTo(now)
        assertThat(store.rows).describedAs("persisted, so the audit trail sees it").containsExactly(a)
        // Next time it is found as an ordinary active attestation — no second row.
        runBlocking { service(FixedLookup(ex), store).decide(ex) }
        assertThat(store.rows).hasSize(1)
    }

    @Test
    fun `two statutory members stay Unattested even when the rule reads SOLE`() {
        val ex = extract(soleText, members = listOf("Oldřich Vaněk", "Eva Dvořáková"))
        val store = InMemoryAttestations()
        assertThat(runBlocking { service(FixedLookup(ex), store).decide(ex) })
            .isInstanceOf(RepresentationDecision.Unattested::class.java)
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `one member with a JOINT or UNKNOWN rule stays Unattested`() {
        listOf(
            extract("jednatelé společně", RepresentationMode.JOINT_ALL, null, listOf("Oldřich Vaněk")),
            extract("dva jednatelé", RepresentationMode.JOINT_N, 2, listOf("Oldřich Vaněk")),
            // A count of one is not a SOLE verdict: only the mode says the parser found no joint clause.
            extract("jednatel spolu s prokuristou", RepresentationMode.JOINT_N, 1, listOf("Oldřich Vaněk")),
            extract("nečitelné", RepresentationMode.UNKNOWN, null, listOf("Oldřich Vaněk")),
        ).forEach { ex ->
            val store = InMemoryAttestations()
            assertThat(runBlocking { service(FixedLookup(ex), store).decide(ex) })
                .describedAs(ex.representationRule.mode.name)
                .isInstanceOf(RepresentationDecision.Unattested::class.java)
            assertThat(store.rows).isEmpty()
        }
    }

    @Test
    fun `an unverified or inactive extract is not auto-confirmed`() {
        listOf(
            extract(soleText, members = listOf("Oldřich Vaněk")).copy(verification = ExtractVerification.UNVERIFIED),
            extract(soleText, members = listOf("Oldřich Vaněk")).copy(status = EntityStatus.IN_LIQUIDATION),
        ).forEach { ex ->
            assertThat(runBlocking { service(FixedLookup(ex), InMemoryAttestations()).decide(ex) })
                .isInstanceOf(RepresentationDecision.Unattested::class.java)
        }
    }

    @Test
    fun `a changed text over a previous human attestation is Superseded, never auto-confirmed`() {
        val old = extract("Jednatelé jednají společně.", RepresentationMode.JOINT_ALL, null, listOf("Oldřich Vaněk"))
        val store = InMemoryAttestations()
        val svc = service(FixedLookup(old), store)
        runBlocking {
            svc.attest(
                AttestRepresentationCommand(
                    scheme = IdentifierScheme.CZ_ICO,
                    identifier = "27074358",
                    ruleTextHash = RepresentationAttestation.hashOf(old.representationRule.sourceText),
                    confirmedSigners = 1,
                    confirmedRoles = emptyList(),
                    operator = "operator-anna",
                ),
            )
        }
        val amended = extract(soleText, members = listOf("Oldřich Vaněk"))
        assertThat(runBlocking { svc.decide(amended) }).isInstanceOf(RepresentationDecision.Superseded::class.java)
        assertThat(store.rows.map { it.attestedBy }).containsExactly("operator-anna")
    }

    @Test
    fun `with the kill switch off a single member still goes to a human`() {
        val ex = extract(soleText, members = listOf("Oldřich Vaněk"))
        val store = InMemoryAttestations()
        assertThat(runBlocking { service(FixedLookup(ex), store, autoConfirm = false).decide(ex) })
            .isInstanceOf(RepresentationDecision.Unattested::class.java)
        assertThat(store.rows).isEmpty()
    }

    @Test
    fun `application yaml ships the switch ON behind an env override`() {
        val yaml = java.io.File("src/main/resources/application.yaml").readText()
        assertThat(yaml).contains(
            "auto-confirm-single-member: \${OPENBANK_KYB_REPRESENTATION_AUTO_CONFIRM_SINGLE_MEMBER:true}",
        )
    }
}
