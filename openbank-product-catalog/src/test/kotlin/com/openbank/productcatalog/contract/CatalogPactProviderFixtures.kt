// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/** Provider-state data only; every fixture uses a separate aggregate so Pact order is irrelevant. */
@ApplicationScoped
class CatalogPactProviderFixtures(private val dataSource: DataSource) {
    fun specificationCodeIsAvailable() = Unit

    fun offeringPrerequisiteExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, OFFERING_SPECIFICATION_ID, "PACT_OFFERING_PREREQUISITE")
    }

    fun draftPrerequisiteOfferingExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, DRAFT_SPECIFICATION_ID, "PACT_DRAFT_PREREQUISITE")
        insertOffering(connection, DRAFT_OFFERING_ID, DRAFT_SPECIFICATION_ID, "PACT_DRAFT_OFFERING")
    }

    fun editableDraftExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, UPDATE_SPECIFICATION_ID, "PACT_UPDATE_PREREQUISITE")
        insertOffering(connection, UPDATE_OFFERING_ID, UPDATE_SPECIFICATION_ID, "PACT_UPDATE_OFFERING")
        insertDraft(connection, UPDATE_REVISION_ID, UPDATE_OFFERING_ID, "pact-original-author")
    }

    fun independentlyCheckableDraftExists() = dataSource.connection.use { connection ->
        insertSpecification(connection, PUBLISH_SPECIFICATION_ID, "PACT_PUBLISH_PREREQUISITE")
        insertOffering(connection, PUBLISH_OFFERING_ID, PUBLISH_SPECIFICATION_ID, "PACT_PUBLISH_OFFERING")
        insertDraft(connection, PUBLISH_REVISION_ID, PUBLISH_OFFERING_ID, "pact-independent-author")
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

    private companion object {
        const val SCHEMA_ID = "org.openbank.insurance.term-life"
        val OFFERING_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val DRAFT_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val UPDATE_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
        val PUBLISH_SPECIFICATION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000004")
        val DRAFT_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val UPDATE_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val PUBLISH_OFFERING_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")
        val UPDATE_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val PUBLISH_REVISION_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000002")
        const val REVISION_CONTENT =
            """{"name":{"en":"Term life"},"attributes":{"coverage":{"amount":"100000","currency":"EUR"},"termYears":20,"premiumModel":"CALCULATED"},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]}"""
    }
}
