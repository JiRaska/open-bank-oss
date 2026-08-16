// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

/**
 * Three-state outcome of one EMAIL send (issue #4737), the exact shape [PushSendOutcome] already
 * carries for the push channel — deliberately the same vocabulary rather than a second one.
 *
 * The defect this exists to make impossible: `ReactiveMailer.send` under `quarkus.mailer.mock=true`
 * completes **successfully** without opening an SMTP connection, and the consumer's only question
 * was "did the Uni fail?". So the mocked send took the success branch and committed
 * `status = SENT` with `sent_at` populated for a message that never left the process — the
 * `PushResult.skipped()` defect (ADR-0252 phase 0), on the channel #4363 is considering re-routing
 * *to*. That one counted undelivered pushes as delivered in an environment with no credentials and
 * was found by a customer rather than by any signal, because a no-op that reports as success is
 * indistinguishable from work.
 *
 * The deployed sandbox runs with the mailer mocked **deliberately** — the gitops manifest says so
 * outright ("No SMTP in the sandbox — mock the mailer so dispatch logs instead of opening a dead
 * localhost:1025 connection"). That is a legitimate configuration, and it is precisely why the
 * *outcome* has to say so: the honesty of the record must not depend on how the environment is
 * configured.
 */
enum class EmailSendOutcome {
    /**
     * The mailer accepted the message for transport. **Not** proof of delivery — an SMTP accept is
     * a handoff, which a later bounce can still refine (`BOUNCED`, ADR-0239 D4). Named for what
     * this process can actually establish, the same reason [PushSendOutcome.ACCEPTED] is not
     * called `DELIVERED`.
     */
    ACCEPTED,

    /**
     * The mailer is mocked, so nothing left this process. A successful no-op, not a delivery, and
     * carried as its own value rather than a flag shared with [ACCEPTED] — that sharing *is* the
     * bug in both channels' history.
     */
    MOCKED,

    /** The mailer rejected the message, or the call failed. */
    FAILED,
}
