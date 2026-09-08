// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.ingest

import com.openbank.transaction.infrastructure.image.LogoImages
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * Downloads a merchant logo from an operator-named URL — the one place this service reaches out to
 * the internet, and therefore the one place worth being paranoid about.
 *
 * **This endpoint is a server-side request forgery primitive by construction.** An operator says
 * "fetch this URL" and the service fetches it, from inside the cluster, with the cluster's network
 * position. The URL an attacker wants is not a logo: it is `http://169.254.169.254/latest/meta-data/`
 * (cloud credentials), an internal admin port, or a service that only trusts callers on the pod
 * network. The image never has to come back — a request that *reached* one of those is already the
 * whole attack, and a 400 afterwards looks like a rejected logo.
 *
 * So the fetch is fenced four ways, and each one is load-bearing on its own:
 *
 *  1. **An allowlist of hosts, empty by default.** Not a blocklist: enumerating the addresses that
 *     must not be reached is a game you lose to the next one you did not think of. With no
 *     configuration this feature does not exist, so a deployment that has not thought about it is
 *     not exposed by upgrading.
 *  2. **HTTPS only.** A plaintext fetch is both interceptable and the shape most internal endpoints
 *     take.
 *  3. **Every resolved address must be publicly routable.** An allowlisted name whose DNS answer is
 *     `127.0.0.1` or a link-local address is refused — that is what makes the allowlist a control
 *     rather than a spelling check.
 *  4. **Redirects are refused, never followed.** A redirect is the standard bypass: the allowlisted
 *     host answers 302 to the metadata service, and a client that follows it has done exactly what
 *     the allowlist was written to prevent.
 *
 * **Residual risk, stated rather than papered over.** Between the address check and the connection
 * the name can be re-resolved by the JDK's own connect, so a DNS-rebinding attacker who controls an
 * *allowlisted* name can still steer the second lookup. Closing that needs connecting to a pinned
 * IP with SNI/Host preserved, which `java.net.http.HttpClient` does not expose. The allowlist is
 * what bounds it: the attacker must already own a name the bank chose to trust.
 *
 * What comes back is bytes, and bytes are not an image until [LogoImages] says so — the download is
 * capped, then decoded, dimension-checked and re-encoded like any upload.
 */
@ApplicationScoped
class LogoFetcher(
    /**
     * Comma-separated hosts this service may fetch a logo from. Absent means the feature is OFF.
     *
     * `Optional<String>` and no `defaultValue`: an empty default would NOT make it optional —
     * SmallRye still answers `SRCFG00014: required but could not be found` — and the service would
     * simply not boot (root CLAUDE.md).
     */
    @ConfigProperty(name = "openbank.merchant.logo.fetch.allowed-hosts")
    private val allowedHostsConfig: Optional<String>,
) {
    /** Why a fetch was refused, in a sentence an operator can act on. */
    class RefusedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    /** The bytes and where they came from, ready for [LogoImages.render]. */
    data class Fetched(val bytes: ByteArray, val sourceUrl: String, val contentType: String?) {
        // Defined over the source URL: comparing image bytes by reference is never what a caller
        // means, and two fetches of one URL are the same fetch for every purpose here.
        override fun equals(other: Any?): Boolean = other is Fetched && other.sourceUrl == sourceUrl

        override fun hashCode(): Int = sourceUrl.hashCode()
    }

    private val allowedHosts: Set<String> =
        allowedHostsConfig.orElse("")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** True when a host allowlist has been configured. With none, [fetch] refuses everything. */
    fun isEnabled(): Boolean = allowedHosts.isNotEmpty()

    /** The configured allowlist, for the operator API to report rather than make an operator guess. */
    fun allowedHosts(): Set<String> = allowedHosts

    private val client: HttpClient = HttpClient.newBuilder()
        // NEVER, not NORMAL. See the class KDoc: following one redirect is how an allowlist is
        // defeated, and there is no logo worth reachable-by-redirect.
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    /**
     * Fetch [rawUrl], or refuse it.
     *
     * @throws RefusedException when the feature is off, the URL is not HTTPS, its host is not
     *   allowlisted, it resolves to a non-public address, the response is not 200, or the body
     *   exceeds the cap.
     */
    // Every throw below is a distinct refusal an operator has to be able to tell apart — "not
    // allowlisted", "resolves inward" and "answered a redirect" call for three different actions,
    // and collapsing them would say only that the URL was refused.
    @Suppress("ThrowsCount")
    fun fetch(rawUrl: String): Fetched {
        if (!isEnabled()) {
            throw RefusedException(
                "logo fetching is disabled: no openbank.merchant.logo.fetch.allowed-hosts configured",
            )
        }
        val uri = parse(rawUrl)
        val host = uri.host?.lowercase()
            ?: throw RefusedException("URL has no host")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw RefusedException("only https URLs may be fetched; got '${uri.scheme}'")
        }
        if (host !in allowedHosts) {
            throw RefusedException("host '$host' is not in the configured allowlist (${allowedHosts.joinToString()})")
        }
        requirePubliclyRoutable(host)

        val response = try {
            client.send(
                HttpRequest.newBuilder(uri)
                    .header("Accept", "image/png,image/jpeg,image/gif")
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: java.io.IOException) {
            throw RefusedException("fetch failed: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RefusedException("fetch was interrupted", e)
        }

        requireUsableStatus(response.statusCode())

        val bytes = response.body().use { readCapped(it) }
        return Fetched(
            bytes = bytes,
            sourceUrl = uri.toString(),
            contentType = response.headers().firstValue("content-type").orElse(null),
        )
    }

    /**
     * Refuses any status but 200, and says specially why a 3xx is one of them.
     *
     * A redirect arrives as a status rather than being followed, and an operator who is told
     * "redirect" knows to supply the final URL; told only "refused", they retry the same one.
     */
    internal fun requireUsableStatus(status: Int) {
        if (status in REDIRECT_RANGE) {
            throw RefusedException(
                "source answered $status (a redirect); redirects are never followed — supply the final URL",
            )
        }
        if (status != HTTP_OK) throw RefusedException("source answered HTTP $status")
    }

    private fun parse(rawUrl: String): URI = try {
        URI(rawUrl.trim())
    } catch (e: java.net.URISyntaxException) {
        throw RefusedException("not a valid URL: ${e.message}", e)
    }

    /**
     * Refuses a host that resolves to anything not on the public internet.
     *
     * EVERY resolved address is checked, not just the first: a name with an A record for a public
     * address and another for `127.0.0.1` would otherwise pass, and which one the connection uses is
     * not ours to decide.
     */
    @Suppress("ThrowsCount")
    private fun requirePubliclyRoutable(host: String) {
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw RefusedException("host '$host' does not resolve: ${e.message}", e)
        }
        if (addresses.isEmpty()) throw RefusedException("host '$host' resolves to no address")
        addresses.forEach { address ->
            if (!isPubliclyRoutable(address)) {
                throw RefusedException(
                    "host '$host' resolves to the non-public address ${address.hostAddress}",
                )
            }
        }
    }

    /**
     * True only for an address on the public internet.
     *
     * Written as an allowlist of "not any of these" over the JDK's own classifications plus the two
     * ranges it has no predicate for: cloud metadata sits on 169.254.169.254 (link-local, covered),
     * carrier-grade NAT 100.64.0.0/10 is not covered, and IPv6 unique-local fc00::/7 is not either.
     */
    private fun isPubliclyRoutable(address: InetAddress): Boolean {
        if (isReservedByJdkClassification(address)) return false
        val bytes = address.address
        if (bytes.size == IPV4_BYTES) {
            val first = bytes[0].toInt() and BYTE_MASK
            val second = bytes[1].toInt() and BYTE_MASK
            // 100.64.0.0/10 — carrier-grade NAT, routable inside a provider network and nowhere else.
            if (first == CGNAT_FIRST_OCTET && second in CGNAT_SECOND_OCTET_RANGE) return false
        }
        if (bytes.size == IPV6_BYTES) {
            // fc00::/7 — IPv6 unique local. `isSiteLocalAddress` only covers the deprecated fec0::/10.
            if ((bytes[0].toInt() and IPV6_ULA_MASK) == IPV6_ULA_PREFIX) return false
        }
        return true
    }

    /**
     * The ranges the JDK already classifies: any-local, loopback, link-local (which is where cloud
     * metadata lives), site-local, and multicast.
     */
    private fun isReservedByJdkClassification(address: InetAddress): Boolean = address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress

    /**
     * Reads at most [LogoImages.MAX_UPLOAD_BYTES], and refuses a body that exceeds it.
     *
     * Streamed rather than trusting `Content-Length`: the header is whatever the source says, and a
     * source that lies about it is the one you least want to allocate for.
     */
    internal fun readCapped(input: java.io.InputStream): ByteArray {
        val cap = LogoImages.MAX_UPLOAD_BYTES
        val buffer = ByteArray(cap + 1)
        var read = 0
        while (read <= cap) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read > cap) throw RefusedException("source body exceeds $cap bytes")
        if (read == 0) throw RefusedException("source returned an empty body")
        return buffer.copyOf(read)
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val USER_AGENT = "openbank-transaction-service/logo-ingest"
        const val HTTP_OK = 200
        val REDIRECT_RANGE = 300..399
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val BYTE_MASK = 0xFF
        const val CGNAT_FIRST_OCTET = 100
        val CGNAT_SECOND_OCTET_RANGE = 64..127
        const val IPV6_ULA_MASK = 0xFE
        const val IPV6_ULA_PREFIX = 0xFC
    }
}
