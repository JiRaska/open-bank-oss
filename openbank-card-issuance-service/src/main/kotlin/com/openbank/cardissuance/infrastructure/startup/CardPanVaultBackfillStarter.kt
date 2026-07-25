// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.startup

import com.openbank.cardissuance.application.usecase.CardPanVaultBackfill
import io.quarkus.runtime.Startup
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Runs [CardPanVaultBackfill] once per boot.
 *
 * [Startup] because an `@ApplicationScoped` bean is lazy — without it this would first run on some
 * unrelated request, or never (see the repo CLAUDE.md; the same reason `AesGcmCardSecretCipher`
 * carries it).
 *
 * **Two deliberate properties.**
 *
 * *It runs on a Vert.x duplicated context.* The repository is Hibernate Reactive Panache, and
 * `Panache.withSession`/`withTransaction` throw `No current Vertx context found` off one — a
 * `@PostConstruct` body executes on the plain startup thread, so it cannot call the repository
 * directly. [VertxContextSupport] establishes that context for us.
 *
 * *It never blocks and never fails the boot.* We subscribe (fire-and-forget) rather than
 * `subscribeAndAwait`, so a slow or unreachable database delays no readiness probe, and every
 * failure is caught and logged loudly instead of propagating. The service coming up matters more
 * than the backfill completing: an un-backfilled card shows the same "Detaily" error it shows
 * today, whereas a card-issuance that will not start takes down issuing, blocking and cancelling
 * for everyone. The next boot retries — the pass is idempotent.
 */
@Startup
@ApplicationScoped
class CardPanVaultBackfillStarter(
    private val backfill: CardPanVaultBackfill,
    @ConfigProperty(name = "openbank.card.pan-vault-backfill.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    // TooGenericExceptionCaught: this IS the loud-log-and-carry-on boundary the KDoc describes.
    // Anything the backfill can throw — a driver error, a cipher misconfiguration — must not reach
    // the boot, so the catch is deliberately as wide as the language allows.
    @PostConstruct
    @Suppress("TooGenericExceptionCaught")
    fun onStartup() {
        if (!enabled) {
            log.info("[pan-vault-backfill] disabled by configuration; pre-vault cards keep an empty vault")
            return
        }
        try {
            VertxContextSupport.subscribe(
                { uni(CoroutineScope(Dispatchers.Unconfined)) { backfill.run() }.toMulti() },
                { subscription -> subscription.with({ }, { failure -> logFailure(failure) }) },
            )
        } catch (e: Throwable) {
            logFailure(e)
        }
    }

    private fun logFailure(failure: Throwable) {
        log.error(
            "[pan-vault-backfill] failed; the service is starting anyway. Cards issued before the " +
                "synthetic-PAN vault keep returning CARD_SECURE_DETAILS_NOT_STORED until a later " +
                "boot succeeds.",
            failure,
        )
    }

    private companion object {
        val log: Logger = Logger.getLogger(CardPanVaultBackfillStarter::class.java)
    }
}
