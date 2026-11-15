// SPDX-License-Identifier: Apache-2.0

package com.openbank.legalprocess.isds

import com.openbank.legalprocess.LegalDeliveryOutcome
import com.openbank.legalprocess.PostgresLegalDeliveryInbox
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class IsdsMailboxPoll(
    private val store: PostgresIsdsPoll,
    private val inbox: PostgresLegalDeliveryInbox,
    private val client: IsdsSoapDownloads,
    private val mailbox: String,
    private val firstFrom: Instant,
) {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    fun run(now: Instant): UUID? = store.exclusive(mailbox) {
        val cursor = cursor() ?: firstFrom
        val through = minOf(now, cursor.plus(WINDOW))
        if (through <= cursor) return@exclusive null
        val from = maxOf(firstFrom, cursor.minus(OVERLAP))
        val run = start(from, through)
        val ids = try {
            discover(from, through)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(run, "INTERRUPTED")
            return@exclusive run
        } catch (_: Exception) {
            null
        }
        if (ids != null) recordDiscovery(run, ids)
        importPending(run)
        val status = when {
            Thread.currentThread().isInterrupted -> "INTERRUPTED"
            ids == null -> "DISCOVERY_FAILED"
            else -> null
        }
        finish(run, status)
        run
    }

    private fun PostgresIsdsPoll.LockedMailbox.importPending(run: UUID) {
        val importer = IsdsImport(mailbox, client, inbox)
        for (id in pending()) {
            if (Thread.currentThread().isInterrupted) break
            if (alreadyStored(id)) {
                complete(id, run, imported = false)
            } else {
                attempted(id)
                val outcome = fetch(importer, id)
                if (outcome != null && outcome != LegalDeliveryOutcome.Conflict) {
                    complete(id, run, imported = outcome is LegalDeliveryOutcome.Received)
                }
            }
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun fetch(importer: IsdsImport, id: String): LegalDeliveryOutcome? = try {
        importer.importMessage(id)
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    private fun discover(from: Instant, to: Instant): Set<String> {
        val windows = ArrayDeque<Pair<Instant, Instant>>()
        windows.add(from to to)
        val ids = mutableSetOf<String>()
        var remaining = MAX_REQUESTS
        while (windows.isNotEmpty()) {
            check(remaining-- > 0) { "ISDS discovery interval exceeded its request budget" }
            val (start, end) = windows.removeFirst()
            val page = client.received(start, end, PAGE_LIMIT)
            if (page.size == PAGE_LIMIT) {
                val duration = Duration.between(start, end)
                check(duration > SPLIT_OVERLAP.multipliedBy(2)) { "ISDS discovery interval is saturated" }
                val split = start.plus(duration.dividedBy(2))
                windows.add(start to split)
                windows.add(split.minus(SPLIT_OVERLAP) to end)
            } else {
                ids.addAll(page)
            }
        }
        return ids
    }

    private companion object {
        val SPLIT_OVERLAP: Duration = Duration.ofSeconds(1)
        val WINDOW: Duration = Duration.ofHours(1)
        val OVERLAP: Duration = Duration.ofMinutes(2)
        const val PAGE_LIMIT = 100
        const val MAX_REQUESTS = 31
    }
}
