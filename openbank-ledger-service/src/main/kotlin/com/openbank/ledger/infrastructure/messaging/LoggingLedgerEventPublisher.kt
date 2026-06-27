// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.messaging

import com.openbank.ledger.application.port.out.LedgerEventPublisher
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class LoggingLedgerEventPublisher : LedgerEventPublisher {
    private val log: Logger = Logger.getLogger(LoggingLedgerEventPublisher::class.java)

    override suspend fun publish(topic: String, key: String, event: Any) {
        log.infov("Ledger event topic={0} key={1} event={2}", topic, key, event)
    }
}
