// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.application.port.out

import com.openbank.engagement.domain.model.game.CustomerGameCatalog
import com.openbank.engagement.domain.model.game.GameCatalog
import java.time.Instant
import java.util.UUID

interface GameStore {
    suspend fun catalog(): GameCatalog
    suspend fun update(catalog: GameCatalog, actor: String, at: Instant): GameCatalog
    suspend fun customer(party: UUID, beta: Boolean, at: Instant): CustomerGameCatalog
    suspend fun complete(party: UUID, level: String, beta: Boolean, at: Instant): Boolean
    suspend fun audit(): List<Map<String, Any>>
    suspend fun erase(party: UUID)
}
