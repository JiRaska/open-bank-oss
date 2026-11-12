// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import com.openbank.kyb.domain.model.AgreementRecord
import com.openbank.kyb.domain.model.DisclosureAcceptance
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.Signer
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.kyb.infrastructure.persistence.repository.KybJson
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

const val KYB_CURRENT_STATE = "delegation may inspect the current statutory representation"
const val KYB_STALE_STATE = "delegation may inspect a stale statutory representation"
const val KYB_REFUSED_STATE = "the shared backend identity attempts to inspect the current statutory representation"

abstract class KybStatutoryPactVerification {

    protected abstract val sharedWorkloadStatus: Int

    @ConfigProperty(name = "quarkus.http.test-port")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @TestSecurity(user = "pact-verifier", roles = ["ROLE_API"])
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verify(context: PactVerificationContext?) {
        if (context == null) return
        val interaction = context.interaction
        if (interaction.providerStates.any { it.name == KYB_REFUSED_STATE }) {
            check(interaction.asSynchronousRequestResponse()?.response?.status == sharedWorkloadStatus) {
                "the shared workload pact must expect HTTP $sharedWorkloadStatus"
            }
        }
        context.verifyInteraction()
    }

    protected fun currentEvidenceAs(client: String) {
        KybStatutoryPactState.seed(dataSource)
        val name = "service-account-$client"
        val jwt = object : JsonWebToken {
            private val claims = mapOf("sub" to "pact-fixture-subject", "preferred_username" to name, "azp" to client)

            override fun getName(): String = name
            override fun getClaimNames(): Set<String> = claims.keys

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getClaim(claimName: String): T = claims[claimName] as T
        }
        testIdentity.setTestIdentity(QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRole("ROLE_API").build())
    }
}

private object KybStatutoryPactState {
    // Shared with the consumer fixture. Each interaction starts from the same current DB evidence.
    private val caseId = UUID.fromString("b7300000-0000-4000-8000-000000000001")
    private val attestationId = UUID.fromString("b7300000-0000-4000-8000-000000000002")
    private val entityId = UUID.fromString("b7300000-0000-4000-8000-000000000004")
    private val ceremonyId = UUID.fromString("b7300000-0000-4000-8000-000000000005")
    private val signers = listOf(
        UUID.fromString("b7300000-0000-4000-8000-000000000006"),
        UUID.fromString("b7300000-0000-4000-8000-000000000007"),
    )
    private val identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "00000001")
    private val signedAt = Instant.parse("2026-01-02T12:00:00Z")
    private const val RULE_TEXT = "Dva clenove predstavenstva jednaji spolecne."

    fun seed(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            seedAttestation(connection)
            seedCase(connection)
            connection.commit()
        }
    }

    private fun seedAttestation(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO kyb_representation_attestations
                (id, attestation_id, identifier_scheme, identifier_value, rule_text_hash, rule_text,
                 parsed_mode, parsed_signers, confirmed_signers, confirmed_mode, attested_by, attested_at)
            VALUES (nextval('kyb_representation_attestations_seq'), ?, 'CZ_ICO', ?, ?, ?, 'JOINT_N', 2, 2, 'JOINT_N',
                    'pact-kyc-reviewer', CURRENT_TIMESTAMP)
            ON CONFLICT (attestation_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, attestationId)
            statement.setString(2, identifier.value)
            statement.setString(3, RepresentationAttestation.hashOf(RULE_TEXT))
            statement.setString(4, RULE_TEXT)
            statement.executeUpdate()
        }
    }

    private fun seedCase(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO kyb_cases
                (id, case_id, identifier_scheme, identifier_value, initiator_party_id, status, extract_json,
                 entity_party_id, entity_party_active, required_signatures, signers_json, agreement_json,
                 representation_attestation_id, created_at, updated_at)
            VALUES (nextval('kyb_cases_seq'), ?, 'CZ_ICO', ?, ?, 'ACTIVE', ?, ?, TRUE, 2, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (case_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, caseId)
            statement.setString(2, identifier.value)
            statement.setObject(3, signers.first())
            statement.setString(4, KybJson.writeExtract(extract()))
            statement.setObject(5, entityId)
            statement.setString(6, KybJson.writeSigners(signedRoster()))
            statement.setString(7, KybJson.write(agreement()))
            statement.setObject(8, attestationId)
            statement.executeUpdate()
        }
    }

    private fun extract() = RegistryExtract(
        identifier = identifier,
        legalName = "Pact Fixture Company",
        legalFormCode = "112",
        legalFormClass = LegalFormClass.LIMITED_COMPANY,
        status = EntityStatus.ACTIVE,
        registeredAddress = null,
        incorporatedOn = null,
        taxId = null,
        representatives = signers.map { Representative("Pact Representative", null, "board", "member", null) },
        representationRule = RepresentationRule(RepresentationMode.JOINT_N, signers.size, RULE_TEXT),
        source = "pact-fixture",
        sourceRef = null,
        verification = ExtractVerification.VERIFIED,
        fetchedAt = signedAt,
    )

    private fun signedRoster() = signers.mapIndexed { index, party ->
        Signer(
            id = UUID.randomUUID(),
            representativeIndex = index,
            fullName = "Pact Representative",
            dateOfBirth = null,
            partyId = party,
            status = SignerStatus.SIGNED,
            invitationToken = null,
            invitedAt = null,
            identifiedAt = signedAt,
            signedAt = signedAt,
            signatureRef = ceremonyId.toString(),
            isInitiator = index == 0,
        )
    }

    private fun agreement() = AgreementRecord(
        documentId = UUID.randomUUID(),
        ceremonyId = ceremonyId,
        templateCode = "pact-fixture",
        templateVersion = "1",
        sha256 = "a".repeat(64),
        lang = "cs",
        acceptances = signers.map { DisclosureAcceptance(it, signedAt) },
    )
}
