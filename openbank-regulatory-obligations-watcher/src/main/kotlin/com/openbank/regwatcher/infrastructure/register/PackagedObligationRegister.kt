// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.register

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.regwatcher.application.port.ObligationRegisterPort
import com.openbank.regwatcher.domain.Obligation

/**
 * The obligations register as packaged at build time (build.gradle.kts copies
 * `openbank-libs/governance/regulatory-obligations.yaml`). The image therefore watches exactly the
 * register revision it was built from; a register change reaches the watcher on the next release,
 * which is the same rule the fetch broker follows for agents.yaml. A missing or unparseable
 * resource is a build defect and fails at construction, never as an empty (= "nothing to watch")
 * register.
 */
class PackagedObligationRegister(yaml: String) : ObligationRegisterPort {
    private val rows: List<Obligation> = parse(yaml)

    override fun obligations(): List<Obligation> = rows

    private fun parse(yaml: String): List<Obligation> {
        val root = ObjectMapper(YAMLFactory()).readTree(yaml)
        val list = root.path("obligations")
        require(list.isArray && list.size() > 0) { "regulatory-obligations register has no obligations" }
        return list.map { o ->
            Obligation(
                id = o.path("id").asText(),
                name = o.path("name").path("en").asText(o.path("name").asText("")),
                legalBasis = o.path("legal_basis").asText(""),
                cadence = o.path("cadence").asText(""),
                cadenceConfidence = o.path("cadence_confidence").asText(""),
                sources = sources(o.path("sources")),
            )
        }
    }

    private fun sources(node: JsonNode): Map<String, String> = buildMap {
        node.fields().forEach { (k, v) -> if (v.isTextual && v.asText().startsWith("https://")) put(k, v.asText()) }
    }

    companion object {
        fun fromClasspath(path: String): PackagedObligationRegister {
            val text = Thread.currentThread().contextClassLoader.getResourceAsStream(path)?.use {
                it.readBytes().decodeToString()
            } ?: error("register resource missing: $path — packaged by build.gradle.kts")
            return PackagedObligationRegister(text)
        }
    }
}
