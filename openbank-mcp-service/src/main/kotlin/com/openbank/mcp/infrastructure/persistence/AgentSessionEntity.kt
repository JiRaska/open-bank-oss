// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.persistence

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One staff OBO session row (ADR-0224 D2). `roleCeiling` is a JSON array string of ROLE_* names. */
@Entity
@Table(name = "agent_session")
class AgentSessionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "subject", nullable = false)
    lateinit var subject: String

    @Column(name = "role_ceiling", nullable = false, columnDefinition = "TEXT")
    lateinit var roleCeiling: String

    @Column(name = "client_id", nullable = false)
    lateinit var clientId: String

    @Column(name = "jti")
    var jti: String? = null

    @Column(name = "purpose")
    var purpose: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
}
