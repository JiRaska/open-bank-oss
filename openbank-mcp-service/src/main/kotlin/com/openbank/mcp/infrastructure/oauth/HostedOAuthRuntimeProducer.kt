// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.customer.CustomerChannel
import com.openbank.mcp.infrastructure.customer.DelegationGrantSource
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import io.quarkus.runtime.Startup
import io.vertx.core.Vertx
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.mutiny.pgclient.PgPool
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Optional

@Startup
@ApplicationScoped
internal class HostedOAuthRuntimeProducer(
    @ConfigProperty(name = "mcp.hosted-oauth.vendors", defaultValue = "disabled") vendors: String,
    @ConfigProperty(name = "mcp.hosted-oauth.site-file") siteFile: Optional<String>,
    channel: CustomerChannel,
    private val pool: PgPool,
    private val mapper: ObjectMapper,
    private val vertx: Vertx,
    private val authority: DelegationGrantSource,
) : HostedOAuthRuntime {
    override val enabledVendors = HostedOAuthSiteConfiguration.vendors(vendors)

    private val runtime = if (enabledVendors.isEmpty()) {
        null
    } else {
        check(channel.admission?.allowlist?.resource == HostedOAuthAdmission.BANK_RESOURCE) {
            "Hosted OAuth requires the canonical customer MCP channel"
        }
        create(HostedOAuthSiteConfiguration.load(Path.of(siteFile.orElseThrow()), mapper), channel)
    }

    override val start get() = checkNotNull(runtime).start
    override val complete get() = checkNotNull(runtime).complete
    override val grants get() = checkNotNull(runtime).grants
    override val dispatch get() = checkNotNull(runtime).dispatch

    private fun create(site: HostedOAuthSiteConfiguration, channel: CustomerChannel): HostedOAuthRuntime {
        val clock = Clock.systemUTC()
        val openBao = openBao(site)
        val authentication = PinnedHostedVendorAuthentication(
            site.chatGptJwks,
            site.chatGptJwksSha256,
            mapper,
            channel.hostedReplayStore(),
            clock,
            if (Vendor.CLAUDE in enabledVendors) site.claudeCredential() else null,
        )
        val custody = HostedBankCustody(
            HostedBankJwtVerifier(mapper, site.bankKeys, clock),
            authority,
            openBao.custody,
            PgHostedBankGrantStore(pool),
            mapper,
            clock,
        )
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(BANK_CONNECT_TIMEOUT_SECONDS)).build()
        val token = HostedBankTokenClient(http, mapper, openBao.signer, custody)
        val bankAccess = HostedBankAccess(custody, HostedBankDpopBinding(openBao.signer::thumbprint), token)
        return compose(site, openBao, authentication, http, token, bankAccess)
    }

    private fun compose(
        site: HostedOAuthSiteConfiguration,
        openBao: HostedOAuthOpenBao,
        authentication: HostedVendorAuthentication,
        http: HttpClient,
        token: HostedBankTokenClient,
        bankAccess: HostedBankAccess,
    ): HostedOAuthRuntime {
        val availability = HostedActiveConnection { vendor, id -> bankAccess.load(vendor, id) != null }
        val grants = HostedVendorGrants(
            PgHostedVendorGrants(pool),
            authentication,
            availability,
            availability,
            enabledVendors,
        )
        val transactions = PgHostedOAuthTransactions(pool)
        val bankPar = HostedBankParClient(http, mapper, openBao.signer)
        val start = HostedOAuthStart(transactions, bankPar, openBao.custody, mapper, enabledVendors)
        val complete = HostedOAuthComplete(
            transactions,
            token,
            authentication,
            grants,
            openBao.custody,
            mapper,
            enabledVendors,
        )
        return ComposedHostedOAuthRuntime(
            enabledVendors,
            start,
            complete,
            grants,
            HostedMcpDispatch(grants, bankAccess, upstream(site, openBao.signer)),
        )
    }

    private fun openBao(site: HostedOAuthSiteConfiguration): HostedOAuthOpenBao {
        val pepper = site.custodyPepper()
        return try {
            HostedOAuthOpenBao(site.baoAddress, site.baoCa, site.serviceAccountToken, mapper, pepper, site.transitKeys)
        } finally {
            pepper.fill(0)
        }
    }

    private fun upstream(site: HostedOAuthSiteConfiguration, signer: HostedBankTransitProof): HostedBankMcpUpstream =
        HostedBankMcpHttpUpstream.usingDedicatedIdentity(
            vertx,
            site.bankTlsPeer,
            BANK_TLS_PORT,
            PemKeyCertOptions().addCertPath(
                site.bankClientCertificate.toString(),
            ).addKeyPath(site.bankClientKey.toString()),
            PemTrustOptions().addCertPath(site.bankTlsCa.toString()),
            mapper,
            signer,
        )

    private companion object {
        const val BANK_CONNECT_TIMEOUT_SECONDS = 5L
        const val BANK_TLS_PORT = 8443
    }
}
