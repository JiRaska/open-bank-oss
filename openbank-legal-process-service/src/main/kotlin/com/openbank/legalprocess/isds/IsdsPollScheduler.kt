// SPDX-License-Identifier: Apache-2.0

package com.openbank.legalprocess.isds

import com.openbank.legalprocess.PostgresLegalDeliveryInbox
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.Optional
import javax.sql.DataSource

@Startup
@ApplicationScoped
internal class IsdsPollScheduler(
    store: PostgresIsdsPoll,
    dataSource: DataSource,
    @ConfigProperty(name = "legal.isds.poll.enabled", defaultValue = "false") enabled: Boolean,
    @ConfigProperty(name = "legal.isds.poll.test-mailbox") mailbox: Optional<String>,
    @ConfigProperty(name = "legal.isds.poll.first-from") firstFrom: Optional<String>,
    @ConfigProperty(name = "legal.isds.poll.basic") basic: Optional<String>,
) {
    private val poll = if (enabled) {
        val key = mailbox.orElseThrow()
        require(key.startsWith("test-") && Regex("[a-z0-9][a-z0-9._:-]{0,122}").matches(key))
        val configuredStart = Instant.parse(firstFrom.orElseThrow())
        require(!configuredStart.isAfter(Instant.now()))
        val secret = basic.orElseThrow()
        require(secret.isNotBlank())
        val client = IsdsSoapDownloads.authenticated(IsdsSoapDownloads.Environment.PUBLIC_TEST, { secret })
        IsdsMailboxPoll(store, PostgresLegalDeliveryInbox(dataSource), client, key, configuredStart)
    } else {
        null
    }

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun pollMailbox() {
        poll?.run(Instant.now())
    }
}
