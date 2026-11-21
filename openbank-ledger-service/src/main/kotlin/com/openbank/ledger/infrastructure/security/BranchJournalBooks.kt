// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.security

import com.openbank.ledger.domain.model.checkConflict
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

@ApplicationScoped
class BranchJournalBooks(private val db: Pool) {
    suspend fun requireDisbursementBook(bookId: UUID, identity: SecurityIdentity, reference: String) {
        val token = identity.principal as? JsonWebToken
        if (token?.getClaim<String>("azp") != "openbank-lending" ||
            token.getClaim<String>("preferred_username") != "service-account-openbank-lending"
        ) {
            throw ForbiddenException("A branch disbursement requires the Lending service")
        }
        require(DISBURSEMENT.matches(reference)) { "Branch booking is scoped to Lending disbursements" }
        val registered = db.preparedQuery("SELECT 1 FROM ledger_branch_books WHERE establishment_id = $1")
            .execute(Tuple.of(bookId)).awaitSuspending().firstOrNull()
        checkConflict(registered != null) { "Branch book is not registered" }
    }

    private companion object {
        val DISBURSEMENT = Regex("^loan:[0-9a-f-]{36}:disbursement$")
    }
}
