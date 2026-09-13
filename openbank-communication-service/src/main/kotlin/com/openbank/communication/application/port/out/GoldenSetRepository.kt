// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application.port.out

import com.openbank.communication.domain.GoldenSetEntry
import java.util.UUID

interface GoldenSetRepository {
    suspend fun create(entry: GoldenSetEntry): GoldenSetEntry
    suspend fun find(id: UUID): GoldenSetEntry?
    suspend fun findByPersona(personaId: UUID): List<GoldenSetEntry>
    suspend fun delete(id: UUID): Boolean
}
