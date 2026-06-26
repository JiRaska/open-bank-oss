// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.notification.infrastructure.push

import com.openbank.notification.application.port.out.PushMessage
import com.openbank.notification.application.port.out.PushSender
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.domain.model.PushResult
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

/**
 * [PushSender] facade that dispatches to the per-platform adapter. The consumer depends only
 * on this; adding a platform (e.g. web push) means adding an adapter and a branch here.
 */
@ApplicationScoped
class PushSenderRouter(private val fcm: FcmPushSender, private val apns: ApnsPushSender) : PushSender {
    override fun send(message: PushMessage): Uni<PushResult> = when (message.platform) {
        PushPlatform.FCM -> fcm.send(message)
        PushPlatform.APNS -> apns.send(message)
    }
}
