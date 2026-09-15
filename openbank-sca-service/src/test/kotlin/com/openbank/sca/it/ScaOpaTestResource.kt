// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.it

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

/** Runs the generated deployment policy with the deployment's OPA image and mount layout. */
class ScaOpaTestResource : QuarkusTestResourceLifecycleManager {
    private var container: GenericContainer<*>? = null

    private var started = false

    override fun start(): Map<String, String> {
        check(DockerClientFactory.instance().isDockerAvailable) { "Docker is required for the enforced OPA flow" }
        val bundle = Yaml().load<Map<String, Any>>(Path.of(System.getProperty("openbank.test.opa-bundle")).readText())
        val data = bundle.getValue("data") as Map<*, *>
        val opa = GenericContainer(DockerImageName.parse(imageFromDeployment()))
            .withExposedPorts(OPA_PORT)
            .withCommand("run", "--server", "--addr=0.0.0.0:$OPA_PORT", "--bundle", "/bundle")
            .waitingFor(Wait.forHttp("/health?bundles=true").forStatusCode(200))
        val mounts = mapOf(
            "rest.rego" to "rest.rego",
            "sca_rest_ext.rego" to "sca_rest_ext.rego",
            "agents.rego" to "agents.rego",
            "agents-data.yaml" to "agents/data.yaml",
            "rules-data.yaml" to "rules/data.yaml",
            "manifest.json" to ".manifest",
        )
        mounts.forEach { (key, target) ->
            val content = requireNotNull(data[key] as? String) { "OPA bundle is missing $key" }
            opa.withCopyToContainer(Transferable.of(content.toByteArray(Charsets.UTF_8)), "/bundle/$target")
        }
        container = opa
        opa.start()
        started = true
        TestInfrastructureEvidence.record("opa", opa.dockerImageName, "started")
        return mapOf("opa.url" to "http://${opa.host}:${opa.getMappedPort(OPA_PORT)}", "opa.timeout-ms" to "5000")
    }

    override fun stop() {
        container?.let {
            it.stop()
            if (started) TestInfrastructureEvidence.record("opa", it.dockerImageName, "stopped")
        }
        started = false
        container = null
    }

    private fun imageFromDeployment(): String {
        val yaml = Path.of(System.getProperty("openbank.test.sca-deployment")).readText()
        val rollout = Yaml().loadAll(yaml).filterIsInstance<Map<*, *>>().single { it["kind"] == "Rollout" }
        val containers = rollout.child("spec").child("template").child("spec")["containers"] as List<*>
        return containers.filterIsInstance<Map<*, *>>().single { it["name"] == "opa" }["image"] as String
    }

    private companion object {
        const val OPA_PORT = 8181
    }
}

private fun Map<*, *>.child(key: String): Map<*, *> = get(key) as Map<*, *>
