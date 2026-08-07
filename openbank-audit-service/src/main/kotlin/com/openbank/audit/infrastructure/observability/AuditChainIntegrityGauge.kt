// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.infrastructure.observability

import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the state of the tamper-evident audit hash chain (ADR-0133) as metrics.
 *
 * Before this bean, audit-service emitted **no domain metric at all**. The chain existed, and
 * [AuditRepository.verifyChain] could prove it intact, but the only way to ask was a human calling
 * `GET /api/v1/audit/integrity` by hand. So a broken link — the single event this control exists to
 * detect — was silent: no series, no alert, no panel, nothing that degrades. Tamper-evidence nobody
 * reads is not evidence, and "somebody would have noticed" is the assumption every incident in this
 * repo's history has falsified.
 *
 * Four series, all gauges:
 *  - `openbank.audit.chain.intact` — 1 or 0. The alert condition.
 *  - `openbank.audit.chain.entries.checked` — links successfully recomputed on the last run.
 *  - `openbank.audit.chain.entries.unchained` — pre-V5 rows with no `record_hash`. Counted, not
 *    verifiable, and NOT a failure — but it must be visible, because a chain of zero verifiable
 *    rows would otherwise report `intact=1` and look perfect.
 *  - `openbank.audit.chain.last.verified.timestamp.seconds` — epoch seconds of the last completed
 *    run. This is the one that makes the others trustworthy.
 *
 * **Why the timestamp gauge is not optional.** A gauge is only published while the process is up
 * and only after the first run: if this scheduler never fires — misconfiguration, a crash loop, the
 * flag left off — `openbank_audit_chain_intact` is *absent*, not 0, and an alert written as `== 0`
 * stays green forever about a check that never ran. The same shape cost this repo the
 * control-liveness sentinel (0 schedules, 0 findings, nothing red). So the alerting rule pairs
 * `== 0` with `absent()` and with a staleness bound on this timestamp, and the gauge is what makes
 * the staleness bound expressible.
 *
 * Cadence is a full chain walk, so it is hourly by default and configurable; `SKIP` prevents a slow
 * walk from overlapping itself. The run is also timed (`openbank.audit.chain.verify.duration`) so
 * the cost of the control is itself observable rather than assumed cheap.
 *
 * `suspend fun`, never `runBlocking`: a plain `@Scheduled` method carries no Vert.x context, so a
 * blocking bridge around reactive Panache throws HR000068 and the job aborts having done nothing —
 * silently, because the throw lands outside any per-item catch. Five fleet schedulers had never run
 * for exactly this reason (#2148, #2187); `rules.yaml: scheduled_methods` and
 * check-no-runblocking-in-scheduled.py now enforce the suspend form.
 */
@Startup
@ApplicationScoped
class AuditChainIntegrityGauge {

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var auditRepository: AuditRepository

    /**
     * Off would make this bean pointless, so it defaults ON — unlike the retention scheduler next
     * door, which defaults off because it DELETES. This one only reads and recomputes hashes; the
     * dangerous state here is not running it.
     */
    @ConfigProperty(name = "openbank.audit.chain-verify.enabled", defaultValue = "true")
    var enabled: Boolean = true

    private val log = Logger.getLogger(AuditChainIntegrityGauge::class.java)

    // Strong references held here: Micrometer keeps only a weak reference to a gauge's source
    // object by default, so a locally-scoped holder is collected and the series silently stops
    // updating — it does not disappear, which is worse, because it freezes at its last value.
    private val intact = AtomicLong(0)
    private val checked = AtomicLong(0)
    private val unchained = AtomicLong(0)
    private val unverifiableLegacy = AtomicLong(0)
    private val lastVerifiedEpochSeconds = AtomicLong(0)

    @PostConstruct
    fun register() {
        Gauge.builder("openbank.audit.chain.intact") { intact.get().toDouble() }
            .description("1 when every audit hash-chain link recomputed correctly on the last run, 0 when broken")
            .strongReference(true)
            .register(registry)
        Gauge.builder("openbank.audit.chain.entries.checked") { checked.get().toDouble() }
            .description("Audit entries whose chain link was verified on the last run")
            .strongReference(true)
            .register(registry)
        Gauge.builder("openbank.audit.chain.entries.unchained") { unchained.get().toDouble() }
            .description("Audit entries predating the hash chain (no record_hash) — counted, not verifiable")
            .strongReference(true)
            .register(registry)
        // Separate from `unchained` on purpose. These rows DO carry a hash; it simply cannot be
        // recomputed, because the pre-#3586 canonical form hashed nanoseconds the database
        // truncated (#3505). Reporting them as unchained would say "never had a hash", which is a
        // different fact, and would let a genuine gap hide inside a known one. This series is
        // expected to be CONSTANT: if it ever grows, something is writing the old form again.
        Gauge.builder("openbank.audit.chain.entries.unverifiable.legacy") { unverifiableLegacy.get().toDouble() }
            .description(
                "Chained entries written with the pre-#3586 canonical form — permanently unverifiable, not broken",
            )
            .strongReference(true)
            .register(registry)
        Gauge.builder("openbank.audit.chain.last.verified.timestamp.seconds") {
            lastVerifiedEpochSeconds.get().toDouble()
        }
            .description("Epoch seconds of the last completed chain verification")
            .strongReference(true)
            .register(registry)
    }

    /**
     * Hourly by default. The cron is config so an operator can tighten it after a suspected
     * incident without a redeploy, or widen it if the walk becomes expensive on a large log.
     */
    @Scheduled(
        cron = "\${openbank.audit.chain-verify.cron:0 0 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // The breadth IS the requirement, so it is suppressed with a reason rather than narrowed: the
    // rule is "no failure to RUN may ever be reported as tampering", and that has to hold for
    // whatever the reactive stack throws — a Hibernate PersistenceException, a CompletionException
    // wrapping a driver timeout, a pool exhaustion. Enumerating those would be a list that goes
    // stale on the next Quarkus bump, and the one it missed would surface as a false
    // AuditChainBroken critical. Same trade as OpenAiCompatibleLlmGatewayClient's suppression.
    @Suppress("TooGenericExceptionCaught")
    suspend fun verify() {
        if (!enabled) return
        val startedAt = System.nanoTime()
        val result = try {
            auditRepository.verifyChain()
        } catch (ex: RuntimeException) {
            // Leave the previous values in place rather than reporting 0/intact=0: a DB blip is not
            // evidence of tampering, and a false "chain broken" page at 03:00 would teach everyone
            // to ignore the real one. The staleness of the timestamp gauge is what surfaces this —
            // it stops advancing, and the alert rule bounds it.
            log.errorf(ex, "audit chain verification failed to run — leaving previous gauge values in place")
            return
        }
        registry.timer("openbank.audit.chain.verify.duration")
            .record(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS)

        intact.set(if (result.intact) 1 else 0)
        checked.set(result.checked)
        unchained.set(result.unchained)
        unverifiableLegacy.set(result.unverifiableLegacy)
        lastVerifiedEpochSeconds.set(Instant.now().epochSecond)

        if (result.intact) {
            log.debugf(
                "audit chain intact: %d links verified, %d pre-chain rows",
                result.checked,
                result.unchained,
            )
        } else {
            // The entry id is the incident's starting point, so it belongs in the log even though
            // the alert cannot carry it (a UUID is not a Prometheus label).
            log.errorf(
                "AUDIT CHAIN BROKEN after %d verified links — first broken entry %s",
                result.checked,
                result.firstBrokenEntryId,
            )
        }
    }
}
