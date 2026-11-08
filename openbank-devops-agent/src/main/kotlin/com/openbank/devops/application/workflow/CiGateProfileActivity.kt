// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.workflow

import com.openbank.devops.application.port.out.CiGateProfilePort
import com.openbank.devops.domain.model.CiGateProfileEvidence
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

@ActivityInterface
interface CiGateProfileActivity {
    @ActivityMethod
    fun collectGateProfile(): CiGateProfileEvidence
}

/** Read-only evidence collection; no detector, proposal or release decision is made here. */
@ApplicationScoped
class CiGateProfileActivityImpl(private val port: CiGateProfilePort) : CiGateProfileActivity {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun collectGateProfile(): CiGateProfileEvidence = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { port.collect() }.asUni()
    }
}
