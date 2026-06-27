// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.application.port.`in`

import com.openbank.tppregistry.domain.model.*
import java.util.UUID

data class CheckTppAuthorizationQuery(
    val tppId: String,
    val requiredRole: TppRole
)

data class RegisterTppCommand(
    val tppId: String,
    val name: String,
    val countryCode: String,
    val nca: String,
    val roles: Set<TppRole>,
    val qwacSubjectDn: String?,
    val qsealSubjectDn: String?
)

data class BlacklistTppCommand(
    val tppId: String,
    val reason: String
)

data class GetTppQuery(val tppId: String)
data class ListTppsQuery(val countryCode: String?, val role: TppRole?, val status: TppStatus?, val limit: Int, val afterCursor: String?)

interface TppRegistryUseCase {
    suspend fun checkAuthorization(query: CheckTppAuthorizationQuery): TppAuthorizationResult
    suspend fun registerTpp(cmd: RegisterTppCommand): TppEntry
    suspend fun blacklistTpp(cmd: BlacklistTppCommand): TppEntry
    suspend fun getTpp(query: GetTppQuery): TppEntry
    suspend fun listTpps(query: ListTppsQuery): List<TppEntry>
    suspend fun triggerEbaSync(): EbaRegisterSyncState
    suspend fun getSyncState(): EbaRegisterSyncState
}
