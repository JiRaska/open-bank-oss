// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Row mapping for `suppressions` (V6__suppressions.sql).
 *
 * EVERY column name is explicit, and that is load-bearing rather than stylistic: consent-service
 * configures **no** `physical-naming-strategy`, so Hibernate's implicit name for a property is the
 * property name verbatim, and Postgres folds an unquoted identifier to lower case. `createdAt`
 * therefore resolved to `createdat` while the migration created `created_at`, and every read of
 * this table answered `500 INTERNAL_ERROR` —
 * `SQLGrammarException: column se1_0.createdat does not exist (42703)`. Six of the ten columns
 * were wrong the same way (party_id, reason_code, created_by, created_at, revoked_at, revoked_by);
 * the four that "worked" — id, scope, value, source — are single words, which is why the class
 * looked fine.
 *
 * Its two siblings in this package, ConsentEntity and ConsentOutboxEntity, both spell every
 * column out. This one did not, and nothing noticed until schemathesis fuzzed the route (#5705
 * follow-up): a unit test that mocks SuppressionRepository never issues the SQL, and the service
 * had no integration test driving the endpoint against a real database.
 *
 * Only the six services that set `CamelCaseToUnderscoresNamingStrategy` may omit the name;
 * consent is not one of them. `check-entity-column-names.py` now enforces that split.
 */
@Entity
@Table(name = "suppressions")
class SuppressionEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "scope", nullable = false, length = 10)
    lateinit var scope: String

    @Column(name = "value", length = 255)
    var value: String? = null

    @Column(name = "reason_code", nullable = false, length = 30)
    lateinit var reasonCode: String

    @Column(name = "source", nullable = false, length = 100)
    lateinit var source: String

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: OffsetDateTime

    @Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null

    @Column(name = "revoked_by")
    var revokedBy: String? = null
}
