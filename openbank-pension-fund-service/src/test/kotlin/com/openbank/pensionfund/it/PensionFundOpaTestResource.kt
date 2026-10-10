// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.readText

/** Runs the generated deployment bundle in the deployment's OPA image. */
class PensionFundOpaTestResource : QuarkusTestResourceLifecycleManager {
    private var container: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        check(DockerClientFactory.instance().isDockerAvailable) { "Docker is required for enforced OPA" }
        val bundle = Yaml().load<Map<String, Any>>(
            Path.of(System.getProperty("openbank.test.pension-fund-opa-bundle")).readText(),
        )
        val data = bundle.getValue("data") as Map<*, *>
        val deployment = Yaml().loadAll(
            Path.of(System.getProperty("openbank.test.pension-fund-deployment")).readText(),
        ).filterIsInstance<Map<*, *>>().single { it["kind"] == "Deployment" }
        val containers = deployment.child("spec").child("template").child("spec")["containers"] as List<*>
        val image = containers.filterIsInstance<Map<*, *>>().single { it["name"] == "opa" }["image"] as String
        val opa = GenericContainer(DockerImageName.parse(image))
            .withExposedPorts(OPA_PORT)
            .withCommand("run", "--server", "--addr=0.0.0.0:$OPA_PORT", "--bundle", "/bundle")
            .waitingFor(Wait.forHttp("/health?bundles=true").forStatusCode(200))
        mapOf(
            "rest.rego" to "rest.rego",
            "pension_fund_rest_ext.rego" to "pension_fund_rest_ext.rego",
            "agents.rego" to "agents.rego",
            "agents-data.yaml" to "agents/data.yaml",
            "rules-data.yaml" to "rules/data.yaml",
            "manifest.json" to ".manifest",
        ).forEach { (key, target) ->
            val content = requireNotNull(data[key] as? String) { "OPA bundle is missing $key" }
            opa.withCopyToContainer(Transferable.of(content.toByteArray(Charsets.UTF_8)), "/bundle/$target")
        }
        opa.start()
        container = opa
        TestInfrastructureEvidence.record("opa", opa.dockerImageName, "started")
        return mapOf("opa.url" to "http://${opa.host}:${opa.getMappedPort(OPA_PORT)}")
    }

    override fun stop() {
        container?.let {
            it.stop()
            TestInfrastructureEvidence.record("opa", it.dockerImageName, "stopped")
        }
        container = null
    }

    private companion object {
        const val OPA_PORT = 8181
    }
}

private fun Map<*, *>.child(key: String): Map<*, *> = get(key) as Map<*, *>
