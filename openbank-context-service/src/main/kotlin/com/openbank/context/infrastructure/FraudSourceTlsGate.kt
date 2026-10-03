// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.ConfigProvider
import java.net.URI

/** Reject a plaintext or malformed source-discovery value before this pod becomes ready. */
@Startup
@ApplicationScoped
@Suppress("UtilityClassWithPublicConstructor")
class FraudSourceTlsGate {
    init {
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            require(secureFraudSourceUrl(ConfigProvider.getConfig().getValue(URL_PROPERTY, String::class.java))) {
                "Fraud source requires an HTTPS endpoint"
            }
        }
    }

    private companion object {
        const val URL_PROPERTY = "quarkus.rest-client.fraud-service.url"
    }
}

internal fun secureFraudSourceUrl(value: String): Boolean = runCatching {
    URI(value).let {
        it.scheme == "https" &&
            it.host != null &&
            it.port == FRAUD_MTLS_PORT &&
            it.userInfo == null &&
            it.rawPath.isNullOrEmpty() &&
            it.rawQuery == null &&
            it.rawFragment == null
    }
}.getOrDefault(false)

private const val FRAUD_MTLS_PORT = 8443
