// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0-only.txt for details.

package com.openbank.agent.application.port.out

/**
 * Exact immutable input to a catalog-review run. The application sees a bounded snapshot rather
 * than a REST client or an open-ended catalog query, so review cannot silently drift to a newer
 * revision while a human is considering the proposal.
 */
data class CatalogRevisionSnapshot(
    val offeringId: String,
    val revisionId: String,
    val state: String,
    val schemaRef: String,
    /** Present for a published snapshot; DRAFT revisions use [document]'s context hash instead. */
    val contentHash: String?,
    val document: String,
)

/** Read-only outbound port for one exact Generic Catalog v2 revision. */
interface CatalogRevisionReadPort {
    fun get(offeringId: String, revisionId: String): CatalogRevisionSnapshot
}
