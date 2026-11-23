// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.domain

import java.time.Instant

/** `web.fetch` output shape (ADR-0387 D1). */
enum class FetchMode {
    TEXT,
    MARKDOWN,
    ;

    companion object {
        fun parse(raw: String?): FetchMode = when (raw?.lowercase()) {
            null, "", "text" -> TEXT
            "markdown" -> MARKDOWN
            else -> throw IllegalArgumentException("mode must be one of: text, markdown")
        }
    }
}

/**
 * The outcome of one call. Its own enum, never a boolean: a page that could not be rendered must
 * not read as an (empty) success — the "successful no-op shares a flag" trap (ADR-0387 D8).
 */
enum class FetchOutcome { FETCHED, DENIED, NOT_RENDERABLE }

/** How a charter wants a host read: plain HTTP (default) or the JS renderer (ADR-0387 D3). */
enum class RenderMode { STATIC, JS }

data class HostGrant(val host: String, val render: RenderMode = RenderMode.STATIC)

/** The ADR-0387 D4 response envelope. [content] is already wrapped by [UntrustedContent]. */
data class FetchEnvelope(
    val url: String,
    val finalUrl: String?,
    val fetchedAt: Instant,
    val sha256: String?,
    val bytes: Int,
    val renderer: String,
    val outcome: FetchOutcome,
    val content: String?,
    val reason: String? = null,
    val truncated: Boolean = false,
)

/** What a renderer produced for one URL. */
sealed interface RenderResult {
    /** [raw] is the source bytes as received (hashed for evidence); [text] the extracted text. */
    class Rendered(val finalUrl: String, val raw: ByteArray, val text: String) : RenderResult

    data class NotRenderable(val reason: String) : RenderResult

    data class Denied(val reason: String) : RenderResult
}
