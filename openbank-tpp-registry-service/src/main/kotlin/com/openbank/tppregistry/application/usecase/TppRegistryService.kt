// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.application.usecase

import com.openbank.tppregistry.application.port.`in`.*
import com.openbank.tppregistry.application.port.out.TppRepository
import com.openbank.tppregistry.domain.model.*
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class ConsentNotFoundException(msg: String) : RuntimeException(msg)
class TppNotFoundException(msg: String) : RuntimeException(msg)
class TppAlreadyExistsException(msg: String) : RuntimeException(msg)
class EbaSyncUnavailableException : RuntimeException("EBA sync temporarily unavailable")

@ApplicationScoped
class TppRegistryService(private val repo: TppRepository, private val clock: Clock) : TppRegistryUseCase {

    override suspend fun checkAuthorization(query: CheckTppAuthorizationQuery): TppAuthorizationResult {
        val tpp = repo.findByTppId(query.tppId)
            ?: return TppAuthorizationResult(query.tppId, false, emptySet(), "TPP not found in registry")

        if (tpp.status != TppStatus.ACTIVE) {
            return TppAuthorizationResult(query.tppId, false, tpp.roles, "TPP status is ${tpp.status}")
        }

        if (!tpp.roles.contains(query.requiredRole)) {
            return TppAuthorizationResult(query.tppId, false, tpp.roles, "TPP does not have role ${query.requiredRole}")
        }

        val now = LocalDate.now(clock)
        if (tpp.qwacExpiresAt != null && tpp.qwacExpiresAt.isBefore(now)) {
            return TppAuthorizationResult(query.tppId, false, tpp.roles, "QWAC certificate expired")
        }

        return TppAuthorizationResult(query.tppId, true, tpp.roles, null)
    }

    override suspend fun registerTpp(cmd: RegisterTppCommand): TppEntry {
        val existing = repo.findByTppId(cmd.tppId)
        if (existing != null) throw TppAlreadyExistsException("TPP ${cmd.tppId} already registered")

        val now = OffsetDateTime.now(clock)
        val entry = TppEntry(
            id = UUID.randomUUID(),
            tppId = cmd.tppId,
            name = cmd.name,
            countryCode = cmd.countryCode,
            nca = cmd.nca,
            roles = cmd.requireRoles(),
            status = TppStatus.ACTIVE,
            qwacSubjectDn = cmd.qwacSubjectDn,
            qsealSubjectDn = cmd.qsealSubjectDn,
            qwacExpiresAt = null,
            qsealExpiresAt = null,
            registeredAt = now,
            updatedAt = now,
            blacklistedAt = null,
            blacklistReason = null,
        )
        // The event travels with the aggregate into ONE transaction (issue #4007) — the registry
        // row and TPP_REGISTERED commit together, or neither does.
        return repo.save(entry, TppEvents.registered(entry))
    }

    override suspend fun blacklistTpp(cmd: BlacklistTppCommand): TppEntry {
        val tpp = repo.findByTppId(cmd.tppId)
            ?: throw TppNotFoundException("TPP ${cmd.tppId} not found")
        val now = OffsetDateTime.now(clock)
        val updated = tpp.copy(
            status = TppStatus.BLACKLISTED,
            blacklistedAt = now,
            blacklistReason = cmd.reason,
            updatedAt = now,
        )
        return repo.update(updated, TppEvents.blacklisted(updated, now))
    }

    override suspend fun getTpp(query: GetTppQuery): TppEntry =
        repo.findByTppId(query.tppId) ?: throw TppNotFoundException("TPP ${query.tppId} not found")

    override suspend fun listTpps(query: ListTppsQuery): List<TppEntry> =
        repo.list(query.countryCode, query.role, query.status, query.limit, query.afterCursor)

    override suspend fun triggerEbaSync(): EbaRegisterSyncState {
        val state = attemptEbaSync()
        repo.saveSyncState(state)
        return state
    }

    override suspend fun getSyncState(): EbaRegisterSyncState =
        repo.getSyncState() ?: EbaRegisterSyncState(null, null, 0, null)

    @Timeout(3000)
    @Retry(maxRetries = 1, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    open suspend fun attemptEbaSync(): EbaRegisterSyncState = EbaRegisterSyncState(
        lastSyncAt = OffsetDateTime.now(clock),
        lastSuccessAt = null,
        totalEntries = 0,
        errorMessage = "EBA sync not yet implemented — manual registration only",
    )
}
