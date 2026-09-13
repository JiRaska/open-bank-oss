// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application.port.out

import com.openbank.communication.domain.PlaybookVersion
import java.time.Instant
import java.util.UUID

interface PlaybookVersionRepository {
    suspend fun create(version: PlaybookVersion): PlaybookVersion
    suspend fun find(id: UUID): PlaybookVersion?
    suspend fun latestVersionNumber(personaId: UUID): Int
    suspend fun submit(id: UUID, at: Instant): PlaybookVersion?
    suspend fun publish(id: UUID, checker: String, at: Instant): PlaybookVersion?
    suspend fun retire(id: UUID, checker: String, at: Instant): PlaybookVersion?
    suspend fun findPublished(personaId: UUID): PlaybookVersion?
}
