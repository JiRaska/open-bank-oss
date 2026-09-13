// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.startup

import com.openbank.cardissuance.application.usecase.CardPanKeyReencrypt
import io.quarkus.runtime.Startup
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Runs [CardPanKeyReencrypt] once per boot — but only when an operator has actually configured
 * [previousWrappedDek] (ADR-0262 follow-up). Absent, the overwhelming common case, this does
 * nothing: no config read beyond the one Optional, no repository query, no OpenBao call. Safe to
 * leave wired permanently rather than something an operator has to remember to disable: once every
 * row has migrated, a further pass just decrypts everything under the current cipher on the first
 * try and reports zero migrated (`CardPanKeyReencrypt`'s own fast path).
 *
 * Same two deliberate properties as [CardPanVaultBackfillStarter], and for the same reasons — see
 * that class's doc: runs on a Vert.x duplicated context ([VertxContextSupport]), and never blocks
 * or fails the boot (fire-and-forget, every failure caught and logged loudly). A rotation that
 * hasn't finished migrating yet is not an outage; a card-issuance pod that won't start is.
 */
@Startup
@ApplicationScoped
class CardPanKeyReencryptStarter(
    private val reencrypt: CardPanKeyReencrypt,
    @ConfigProperty(name = "openbank.card.envelope.previous-wrapped-dek")
    private val previousWrappedDek: Optional<String>,
) {

    // TooGenericExceptionCaught: this IS the loud-log-and-carry-on boundary, same as the backfill
    // starter's identical suppression.
    @PostConstruct
    @Suppress("TooGenericExceptionCaught")
    fun onStartup() {
        val wrapped = previousWrappedDek.orElse("")
        if (wrapped.isBlank()) {
            return
        }
        try {
            VertxContextSupport.subscribe(
                { uni(CoroutineScope(Dispatchers.Unconfined)) { reencrypt.run(wrapped) }.toMulti() },
                { subscription -> subscription.with({ }, { failure -> logFailure(failure) }) },
            )
        } catch (e: Throwable) {
            logFailure(e)
        }
    }

    private fun logFailure(failure: Throwable) {
        log.error(
            "[pan-key-reencrypt] failed; the service is starting anyway. Rows still on the previous " +
                "DEK stay migrated on a later boot — Transit still serves that key version, so " +
                "nothing is unreadable in the meantime.",
            failure,
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(CardPanKeyReencryptStarter::class.java)
    }
}
