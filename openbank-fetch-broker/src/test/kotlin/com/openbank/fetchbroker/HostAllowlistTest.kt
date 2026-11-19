// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker

import com.openbank.fetchbroker.domain.HostAllowlist
import com.openbank.fetchbroker.domain.HostGrant
import com.openbank.fetchbroker.domain.UrlDecision
import com.openbank.fetchbroker.infrastructure.charter.AgentsYamlCharterHosts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HostAllowlistTest {
    private val grants = mapOf("example.org" to HostGrant("example.org"))

    @Test
    fun `a listed host is allowed`() {
        assertThat(
            HostAllowlist.decide("https://example.org/laws/1", grants),
        ).isInstanceOf(UrlDecision.Allowed::class.java)
        assertThat(HostAllowlist.decide("https://EXAMPLE.org./", grants)).isInstanceOf(UrlDecision.Allowed::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://other.example/laws",
            "https://sub.example.org/",
            "https://example.org/a?q=secret",
            "https://example.org/a?",
            "https://example.org/a#frag",
            "http://example.org/",
            "https://user@example.org/",
            "https://example.org:8443/",
            "https://192.0.2.10/",
            "https://[2001:db8::1]/",
            "ftp://example.org/",
            "not a url",
            "/relative/path",
        ],
    )
    fun `everything else is denied`(url: String) {
        assertThat(HostAllowlist.decide(url, grants)).isInstanceOf(UrlDecision.Denied::class.java)
    }

    @Test
    fun `wildcard and ip entries are never listable`() {
        assertThat(HostAllowlist.isListableHost("*.example.org")).isFalse()
        assertThat(HostAllowlist.isListableHost("192.0.2.10")).isFalse()
        assertThat(HostAllowlist.isListableHost("localhost")).isFalse()
        assertThat(HostAllowlist.isListableHost("eur-lex.europa.eu")).isTrue()
    }

    @Test
    fun `charter parsing drops wildcard and ip entries and ignores disabled charters`() {
        val yaml = javaClass.classLoader.getResource("test-charters/agents.yaml")!!.readText()
        val hosts = AgentsYamlCharterHosts(yaml)
        assertThat(hosts.grantsFor("agent:web-reader").keys).containsExactlyInAnyOrder("example.org", "js.example.org")
        assertThat(hosts.grantsFor("disabled-reader")).isEmpty()
        assertThat(hosts.grantsFor("nobody")).isEmpty()
        assertThat(AgentsYamlCharterHosts(null).grantsFor("web-reader")).isEmpty()
        assertThat(AgentsYamlCharterHosts(": : not yaml [").grantsFor("web-reader")).isEmpty()
    }

    @Test
    fun `an object-shaped tier grant counts, and a read grant of another resource does not`() {
        val yaml = """
            agents:
              - id: object-reader
                enabled: true
                tools:
                  allow:
                    - {tier: read, resources: [read.governance]}
                    - {tier: read, resources: [read.public_web]}
                data_scope: {public_web: {hosts: [www.cnb.cz]}}
              - id: other-reader
                enabled: true
                tools:
                  allow:
                    - {tier: read, resources: [read.governance]}
                    - {tier: write_proposal, resources: [read.public_web]}
                data_scope: {public_web: {hosts: [www.cnb.cz]}}
        """.trimIndent()
        val hosts = AgentsYamlCharterHosts(yaml)
        assertThat(hosts.grantsFor("agent:object-reader").keys).containsExactly("www.cnb.cz")
        assertThat(hosts.grantsFor("agent:other-reader")).isEmpty()
    }

    @Test
    fun `the real agents yaml grants the regulatory watcher exactly its register hosts`() {
        val yaml = java.io.File("../openbank-libs/governance/agents.yaml").readText()
        assertThat(AgentsYamlCharterHosts(yaml).grantsFor("agent:regulatory-obligations-watcher").keys).containsExactlyInAnyOrder(
            "eur-lex.europa.eu", "uoou.gov.cz", "www.cnb.cz", "www.eba.europa.eu", "www.fau.gov.cz",
            "www.financnisprava.gov.cz", "www.gsft.cz", "www.zakonyprolidi.cz",
        )
    }
}
