// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Announces at boot which settlement compensations can actually return money (issue #6037).
 *
 * `@Startup` is load-bearing, not decoration. `@ApplicationScoped` beans are **lazy** in Quarkus —
 * the bean is created via a client proxy on first use — so a warning written in an `init {}` block
 * or a constructor of a plain `@ApplicationScoped` bean never reaches a pod log until something
 * touches it, which for a compensation path can be never. That is exactly how
 * `PdfBoxPadesSealAdapter`'s "every PAdES seal is worthless as evidence" warning managed to never
 * once appear in production (#1299). Compensation is by definition the rare path, so a capability
 * gap that is only announced when compensation runs is announced too late to act on.
 *
 * This is deliberately a plain log line rather than a boot-time `check()` that refuses to start:
 * the ledger half being unimplemented does not make the service unsafe to run — the forward path
 * and both balance reversals are real — it makes one specific failure mode need a manual GL
 * correction. Refusing to boot would take a working money path offline over it.
 */
@Startup
@ApplicationScoped
class SettlementCompensationCapabilities {

    private val log = Logger.getLogger(SettlementCompensationCapabilities::class.java)

    init {
        log.info(
            "Settlement compensation capabilities: reverseDebit=WIRED (balance-service credit), " +
                "reverseCredit=WIRED (balance-service debit), reverseBookToLedger=NOT IMPLEMENTED.",
        )
        log.warn(
            "reverseBookToLedger cannot reverse a general-ledger posting: ledger.reverse is " +
                "maker-checker gated and no journal id is retained. A settlement that fails after " +
                "bookToLedger will unwind both balance movements but leave the GL entry standing, " +
                "and will be marked LEDGER_REVERSAL_UNSUPPORTED for manual correction. See #6037.",
        )
    }
}
