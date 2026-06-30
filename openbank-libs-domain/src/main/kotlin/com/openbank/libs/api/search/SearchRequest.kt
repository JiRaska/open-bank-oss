// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.search

/**
 * Server-side, DB-safety-bounded representation of a free-text + filter search request.
 *
 * This is the shared *contract* (ADR-0055): every domain service that exposes a search
 * endpoint normalises its raw query params through [SearchRequest.of] and answers with a
 * [com.openbank.libs.api.pagination.CursorPage]. The library deliberately owns only the
 * cross-cutting guardrails — page-size clamping, wildcard semantics, minimum-term length,
 * and LIKE-metacharacter escaping — while the actual SQL (which columns are searchable,
 * which index backs them) stays per-domain. Centralising the guardrails means an
 * individual service can never accidentally accept `limit=1000000` or run a single-char
 * `ILIKE '%a%'` full-table scan: the policy is enforced here, once.
 *
 * Semantics:
 *  - `q` is trimmed. Blank, the literal [WILDCARD] (`*`), or anything shorter than
 *    [MIN_TERM_LENGTH] collapses to [wildcard] = true and [term] = null, i.e. "list the
 *    first page within my authorised scope, no fulltext predicate". A 1-char `ILIKE`
 *    matches almost everything anyway, so treating it as a plain list is both the correct
 *    UX ("show me everything I can see") and the DB-safe choice.
 *  - A `q` of [MIN_TERM_LENGTH]+ chars sets [term] (LIKE-escaped) and [wildcard] = false.
 *  - `limit` is coerced into `[1, MAX_LIMIT]`; a null/absent limit defaults to [DEFAULT_LIMIT].
 *
 * Authorisation note: [SearchRequest] carries no identity. Scoping a search to "everything
 * the caller may see" is the endpoint's job (RBAC + party/tenant predicate). The library
 * bounds *cost*, not *visibility*.
 */
data class SearchRequest(
    /** LIKE-escaped free-text term, or null in [wildcard] mode. Never contains raw `%`/`_`. */
    val term: String?,
    /** True when the caller asked to list within scope (blank / `*` / sub-[MIN_TERM_LENGTH]). */
    val wildcard: Boolean,
    /** Page size, already clamped to `[1, MAX_LIMIT]`. Safe to inline into `LIMIT`. */
    val limit: Int,
    /** Opaque keyset cursor (see [com.openbank.libs.api.pagination.CursorEncoder]); null = first page. */
    val cursor: String?,
    /** Exact-match structured filters (e.g. `status=ACTIVE`), passed through verbatim. */
    val filters: Map<String, String> = emptyMap(),
) {
    /** True when a fulltext predicate should be applied (i.e. not [wildcard]). */
    val hasTerm: Boolean get() = !wildcard && term != null

    companion object {
        /** Default page size when the caller omits `limit`. */
        const val DEFAULT_LIMIT: Int = 20

        /** Hard server-enforced ceiling on page size. Protects the DB from `limit=1000000`. */
        const val MAX_LIMIT: Int = 100

        /**
         * Minimum length of a fulltext term. Shorter inputs collapse to [wildcard] (list)
         * rather than running an unselective `ILIKE '%x%'` scan.
         */
        const val MIN_TERM_LENGTH: Int = 2

        /** Caller-facing sentinel meaning "everything within my scope". */
        const val WILDCARD: String = "*"

        /**
         * Normalise raw query params into a bounded [SearchRequest].
         *
         * @param q raw free-text from the client (may be null, blank, `*`, or a real term)
         * @param limit raw requested page size (null/absent → [DEFAULT_LIMIT]; clamped to [MAX_LIMIT])
         * @param cursor opaque keyset cursor for the next page, or null for the first
         * @param filters structured exact-match filters
         */
        fun of(
            q: String?,
            limit: Int? = null,
            cursor: String? = null,
            filters: Map<String, String> = emptyMap(),
        ): SearchRequest {
            val clampedLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val raw = q?.trim()
            val wildcard = raw.isNullOrEmpty() || raw == WILDCARD || raw.length < MIN_TERM_LENGTH
            val term = if (wildcard) null else escapeLike(raw!!)
            return SearchRequest(
                term = term,
                wildcard = wildcard,
                limit = clampedLimit,
                cursor = cursor,
                filters = filters,
            )
        }

        /**
         * Escape the SQL `LIKE`/`ILIKE` metacharacters `\`, `%`, `_` so user input is matched
         * literally and cannot turn a bounded term into a `%`-prefixed full scan or inject a
         * wildcard. Pair with `ESCAPE '\'` in the query. Order matters: escape the escape char
         * first.
         */
        fun escapeLike(input: String): String = input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
