// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** Provider-state data only; every fixture uses a separate aggregate so Pact order is irrelevant. */
@ApplicationScoped
class CatalogPactProviderFixtures(
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
    private val catalogJson: CatalogJson,
) {
    fun specificationCodeIsAvailable() = dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM catalog_specifications WHERE code = ?").use { statement ->
            statement.setString(1, "PACT_STUDIO_SPEC")
            statement.executeUpdate()
        }
    }

    fun offeringPrerequisiteExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, OFFERING_SPECIFICATION_ID, "PACT_OFFERING_PREREQUISITE")
        connection.prepareStatement("DELETE FROM catalog_offerings WHERE code = ?").use { statement ->
            statement.setString(1, "PACT_STUDIO_OFFER")
            statement.executeUpdate()
        }
    }

    fun draftPrerequisiteOfferingExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, DRAFT_SPECIFICATION_ID, "PACT_DRAFT_PREREQUISITE")
        insertOffering(connection, DRAFT_OFFERING_ID, DRAFT_SPECIFICATION_ID, "PACT_DRAFT_OFFERING")
        deleteRevisionsForOffering(connection, DRAFT_OFFERING_ID)
    }

    fun editableDraftExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, UPDATE_SPECIFICATION_ID, "PACT_UPDATE_PREREQUISITE")
        insertOffering(connection, UPDATE_OFFERING_ID, UPDATE_SPECIFICATION_ID, "PACT_UPDATE_OFFERING")
        deleteRevisionsForOffering(connection, UPDATE_OFFERING_ID)
        insertDraft(connection, UPDATE_REVISION_ID, UPDATE_OFFERING_ID, "pact-original-author")
    }

    fun independentlyCheckableDraftExists(): Map<String, Any> = dataSource.connection.use { connection ->
        // Broker verification may replay this interaction. A published revision cannot be
        // deleted or turned back into a draft, so give each current replay its own aggregate.
        // Historical Admin UI Pacts have no path generator and still request these fixed IDs.
        // Keep their draft available without resetting an already published revision.
        insertSpecification(connection, PUBLISH_SPECIFICATION_ID, "PACT_PUBLISH_PREREQUISITE")
        insertOffering(connection, PUBLISH_OFFERING_ID, PUBLISH_SPECIFICATION_ID, "PACT_PUBLISH_OFFERING")
        insertDraft(connection, PUBLISH_REVISION_ID, PUBLISH_OFFERING_ID, "pact-independent-author")
        val offeringId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val offeringCode = "PACT_PUBLISH_OFFERING_${offeringId.toString().replace("-", "").uppercase()}"
        insertOffering(connection, offeringId, PUBLISH_SPECIFICATION_ID, offeringCode)
        insertDraft(connection, revisionId, offeringId, "pact-independent-author")
        mapOf("publishOfferingId" to offeringId.toString(), "publishRevisionId" to revisionId.toString())
    }

    /** Immutable fixed-rate banking revision consumed by interest-service's catalog adapter Pact. */
    fun publishedFixedRateDepositRevisionExists() = dataSource.connection.use { connection ->
        // The cursor endpoint is globally ordered. Isolate this state so `limit=1` proves the
        // event the interest consumer will actually acknowledge, not a pack-install side event.
        connection.createStatement().use { it.executeUpdate("DELETE FROM catalog_outbox") }
        connection.createStatement().use {
            it.execute("SELECT setval('catalog_outbox_cursor_position_seq', 1, false)")
        }
        insertSpecification(connection, INTEREST_SPECIFICATION_ID, "PACT_INTEREST_DEPOSIT")
        insertOffering(connection, INTEREST_OFFERING_ID, INTEREST_SPECIFICATION_ID, "PACT_INTEREST_OFFERING")
        connection.prepareStatement(
            "INSERT INTO catalog_revisions " +
                "(id, offering_id, revision_no, schema_id, schema_version, state, content, effective_from, " +
                "maker_id, checker_id, reason, content_hash, created_at, updated_at, lock_version) " +
                "VALUES (?, ?, 1, ?, 2, 'PUBLISHED', CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            val now = FIXTURE_TIME
            statement.setObject(1, INTEREST_REVISION_ID)
            statement.setObject(2, INTEREST_OFFERING_ID)
            statement.setString(3, DEPOSIT_SCHEMA_ID)
            statement.setString(4, INTEREST_REVISION_CONTENT)
            statement.setObject(5, OffsetDateTime.parse("2027-01-01T00:00:00Z"))
            statement.setString(6, "pact-interest-author")
            statement.setString(7, "pact-interest-checker")
            statement.setString(8, "published fixed-rate fixture")
            statement.setString(9, "a".repeat(64))
            statement.setObject(10, now)
            statement.setObject(11, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO catalog_outbox " +
                "(id, aggregate_type, aggregate_id, event_type, schema_version, occurred_at, " +
                "headers, payload, created_at) " +
                "VALUES (?, 'catalog.revision', ?, 'com.openbank.catalog.revision_published', 1, ?, " +
                "'{}'::jsonb, '{}'::jsonb, ?) ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            val now = FIXTURE_TIME
            statement.setObject(1, INTEREST_EVENT_ID)
            statement.setObject(2, INTEREST_REVISION_ID)
            statement.setObject(3, now)
            statement.setObject(4, now)
            statement.executeUpdate()
        }
    }

    /** Published loan revision consumed by lending before an application is persisted. */
    fun publishedPricedLoanRevisionExists() = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO catalog_specifications " +
                "(id, code, schema_id, schema_version, created_at, lock_version) VALUES (?, ?, ?, 2, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, LOAN_SPECIFICATION_ID)
            statement.setString(2, "PACT_PRICED_LOAN")
            statement.setString(3, LOAN_SCHEMA_ID)
            statement.setObject(4, LOAN_FIXTURE_TIME)
            statement.executeUpdate()
        }
        insertOffering(connection, LOAN_OFFERING_ID, LOAN_SPECIFICATION_ID, "PACT_PRICED_LOAN_OFFERING")
        connection.prepareStatement(
            "INSERT INTO catalog_revisions " +
                "(id, offering_id, revision_no, schema_id, schema_version, state, content, effective_from, " +
                "maker_id, checker_id, reason, content_hash, created_at, updated_at, lock_version) " +
                "VALUES (?, ?, 1, ?, 2, 'PUBLISHED', CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, LOAN_REVISION_ID)
            statement.setObject(2, LOAN_OFFERING_ID)
            statement.setString(3, LOAN_SCHEMA_ID)
            statement.setString(4, LOAN_REVISION_CONTENT)
            statement.setObject(5, LOAN_FIXTURE_TIME)
            statement.setString(6, "pact-loan-author")
            statement.setString(7, "pact-loan-checker")
            statement.setString(8, "published priced loan fixture")
            statement.setString(9, "b".repeat(64))
            statement.setObject(10, LOAN_FIXTURE_TIME)
            statement.setObject(11, LOAN_FIXTURE_TIME)
            statement.executeUpdate()
        }
    }

    /** Synthetic published DIP revision with two immutable, role-qualified approvals. */
    fun publishedPensionStrategyApprovalsExist() = dataSource.connection.use { connection ->
        val content = mapper.readTree(PENSION_REVISION_CONTENT)
        val approvalDigest = catalogJson.sha256(
            mapper.createObjectNode().apply {
                put("offeringId", PENSION_OFFERING_ID.toString())
                put("revisionId", PENSION_REVISION_ID.toString())
                put("revisionNumber", 0)
                put("schemaId", PENSION_SCHEMA_ID)
                put("schemaVersion", 2)
                put("effectiveFrom", "2026-10-01T00:00:00Z")
                put("effectiveTo", "2026-11-01T00:00:00Z")
                set<JsonNode>("content", content)
            },
        )
        connection.prepareStatement(
            "INSERT INTO catalog_specifications " +
                "(id, code, schema_id, schema_version, created_at, lock_version) VALUES (?, ?, ?, 2, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, PENSION_SPECIFICATION_ID)
            statement.setString(2, "PACT_PENSION_DIP_STRATEGY")
            statement.setString(3, PENSION_SCHEMA_ID)
            statement.setObject(4, PENSION_FIXTURE_TIME)
            statement.executeUpdate()
        }
        insertOffering(connection, PENSION_OFFERING_ID, PENSION_SPECIFICATION_ID, "PACT_PENSION_DIP_OFFERING")
        connection.prepareStatement(
            "INSERT INTO catalog_revisions " +
                "(id, offering_id, revision_no, schema_id, schema_version, state, content, effective_from, " +
                "effective_to, maker_id, checker_id, reason, content_hash, pension_approval_digest, " +
                "created_at, updated_at, lock_version) " +
                "VALUES (?, ?, 1, ?, 2, 'PUBLISHED', CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, PENSION_REVISION_ID)
            statement.setObject(2, PENSION_OFFERING_ID)
            statement.setString(3, PENSION_SCHEMA_ID)
            statement.setString(4, PENSION_REVISION_CONTENT)
            statement.setObject(5, OffsetDateTime.parse("2026-10-01T00:00:00Z"))
            statement.setObject(6, OffsetDateTime.parse("2026-11-01T00:00:00Z"))
            statement.setString(7, "pact-pension-maker")
            statement.setString(8, "pact-pension-checker")
            statement.setString(9, "independently reviewed DIP strategy fixture")
            statement.setString(10, catalogJson.sha256(content))
            statement.setString(11, approvalDigest)
            statement.setObject(12, PENSION_FIXTURE_TIME)
            statement.setObject(13, PENSION_FIXTURE_TIME)
            statement.executeUpdate()
        }
        listOf("LEGAL_COUNSEL", "PRODUCT_OWNER").forEachIndexed { index, role ->
            connection.prepareStatement(
                "INSERT INTO pension_revision_approvals " +
                    "(id, revision_id, role, issuer, subject, digest, reason, approved_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            ).use { statement ->
                statement.setObject(1, if (index == 0) PENSION_LEGAL_APPROVAL_ID else PENSION_PRODUCT_APPROVAL_ID)
                statement.setObject(2, PENSION_REVISION_ID)
                statement.setString(3, role)
                statement.setString(4, "pact-fixture")
                statement.setString(5, "pact-$role")
                statement.setString(6, approvalDigest)
                statement.setString(7, "independent $role review")
                statement.setObject(8, OffsetDateTime.parse("2026-10-08T${10 + index}:00:00Z"))
                statement.executeUpdate()
            }
        }
    }

    private fun insertSpecification(connection: Connection, id: UUID, code: String) {
        connection.prepareStatement(
            "INSERT INTO catalog_specifications " +
                "(id, code, schema_id, schema_version, created_at, lock_version) VALUES (?, ?, ?, 1, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setString(2, code)
            statement.setString(3, SCHEMA_ID)
            statement.setObject(4, OffsetDateTime.now())
            statement.executeUpdate()
        }
    }

    private fun insertOffering(connection: Connection, id: UUID, specificationId: UUID, code: String) {
        connection.prepareStatement(
            "INSERT INTO catalog_offerings (id, specification_id, code, market, lock_version) " +
                "VALUES (?, ?, ?, '{}'::jsonb, 0) ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            statement.setObject(1, id)
            statement.setObject(2, specificationId)
            statement.setString(3, code)
            statement.executeUpdate()
        }
    }

    private fun insertDraft(connection: Connection, id: UUID, offeringId: UUID, makerId: String) {
        connection.prepareStatement(
            "INSERT INTO catalog_revisions " +
                "(id, offering_id, revision_no, schema_id, schema_version, state, content, maker_id, " +
                "created_at, updated_at, lock_version) VALUES (?, ?, 1, ?, 1, 'DRAFT', CAST(? AS jsonb), ?, ?, ?, 0) " +
                "ON CONFLICT (id) DO NOTHING",
        ).use { statement ->
            val now = OffsetDateTime.now()
            statement.setObject(1, id)
            statement.setObject(2, offeringId)
            statement.setString(3, SCHEMA_ID)
            statement.setString(4, REVISION_CONTENT)
            statement.setString(5, makerId)
            statement.setObject(6, now)
            statement.setObject(7, now)
            statement.executeUpdate()
        }
    }

    private fun deleteRevisionsForOffering(connection: Connection, offeringId: UUID) {
        connection.prepareStatement("DELETE FROM catalog_revisions WHERE offering_id = ?").use { statement ->
            statement.setObject(1, offeringId)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val SCHEMA_ID = "org.openbank.insurance.term-life"
        const val DEPOSIT_SCHEMA_ID = "org.openbank.banking.deposit"
        val OFFERING_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val DRAFT_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val UPDATE_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
        val PUBLISH_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000004")
        val DRAFT_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val UPDATE_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val PUBLISH_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")
        val UPDATE_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val PUBLISH_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000002")
        val INTEREST_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000010")
        val INTEREST_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000010")
        val INTEREST_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000010")
        val INTEREST_EVENT_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000010")
        val LOAN_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000011")
        val LOAN_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000011")
        val LOAN_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000011")
        val PENSION_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000084")
        val PENSION_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000084")
        val PENSION_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000084")
        val PENSION_LEGAL_APPROVAL_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000084")
        val PENSION_PRODUCT_APPROVAL_ID: UUID = UUID.fromString("40000000-0000-0000-0000-000000000085")
        const val PENSION_SCHEMA_ID = "org.openbank.retirement.pension-savings"
        val PENSION_FIXTURE_TIME: OffsetDateTime = OffsetDateTime.parse("2026-10-08T09:00:00Z")
        const val PENSION_REVISION_CONTENT =
            """{"name":{"en":"Pact DIP strategy"},"attributes":{"productLine":"DIP","jurisdictionPackId":"CZ/DIP","fundStrategy":"DYNAMIC","reviewStatus":"LEGAL_AND_COMMERCIAL_REVIEWED","instrumentClasses":["BOND_FUNDS","EQUITY_FUNDS"]},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
        val FIXTURE_TIME: OffsetDateTime = OffsetDateTime.parse("2027-01-01T00:00:00Z")
        val LOAN_FIXTURE_TIME: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        const val REVISION_CONTENT =
            """{"name":{"en":"Term life"},"attributes":{"coverage":{"amount":"100000","currency":"EUR"},"termYears":20,"premiumModel":"CALCULATED"},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
        const val INTEREST_REVISION_CONTENT =
            """{"name":{"en":"Interest pact deposit"},"attributes":{"currency":"EUR","productType":"SAVINGS","interest":{"rateType":"FIXED","dayCount":"ACT_365","payoutFrequency":"MONTHLY","annualRate":"0.012345678901234567"}},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
        const val LOAN_SCHEMA_ID = "org.openbank.banking.loan"
        const val LOAN_REVISION_CONTENT =
            """{"name":{"en":"Lending pact loan"},"attributes":{"productType":"INSTALLMENT_LOAN","currency":"EUR","tenorMonths":12,"amortizationMethod":"ANNUITY","nominalAnnualRate":"0.0699","accrualBasis":"ACT_365","allocationOrder":["INTEREST","PRINCIPAL"],"minPrincipalAmount":"1000","maxPrincipalAmount":"50000"},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
    }
}
