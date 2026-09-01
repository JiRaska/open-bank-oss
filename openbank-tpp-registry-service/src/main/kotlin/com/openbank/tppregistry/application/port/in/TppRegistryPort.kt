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
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire —
     * this command doubles as the JSON body of `POST /api/v1/tpp-registry`, with no mapping layer
     * in between. Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check
     * the ELEMENTS of a collection, so `{"roles": [null]}` deserialises happily into a
     * `Set<TppRole>` holding a null. Writing the type honestly is what makes [requireRoles]
     * reachable instead of dead code.
     */
    val roles: Set<TppRole?>,
    val qwacSubjectDn: String?,
    val qsealSubjectDn: String?,
) {
    /**
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`;
     * no service-local mapper is added (#526).
     */
    fun requireRoles(): Set<TppRole> = roles.mapIndexed { index, role ->
        requireNotNull(role) { "roles[$index] must not be null" }
    }.toSet()
}

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
