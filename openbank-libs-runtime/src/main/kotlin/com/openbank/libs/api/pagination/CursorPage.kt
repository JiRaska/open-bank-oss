// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.pagination

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.Base64

data class CursorPage<T>(val data: List<T>, val pagination: PageInfo)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageInfo(
    val limit: Int,
    val hasNextPage: Boolean,
    val nextCursor: String? = null,
    val previousCursor: String? = null,
    val totalCount: Long? = null,
)

object CursorEncoder {
    fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    fun decode(cursor: String): String = String(Base64.getUrlDecoder().decode(cursor))
}
