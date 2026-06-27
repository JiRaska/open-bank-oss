// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.copilot.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.copilot.domain.model.ToolSpec
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Registry of money-path ACTION tools (ADR-0089 D2). Discovered via CDI; offered to the model
 * alongside READ tools but dispatched separately — they [propose], never execute.
 */
@ApplicationScoped
class ActionToolRegistry(private val tools: Instance<ActionProposalTool>) {

    fun specs(): List<ToolSpec> = tools.map { ToolSpec(it.name, it.description, it.inputSchema) }

    fun handles(name: String): Boolean = tools.any { it.name == name }

    fun capabilityOf(name: String): String? = tools.firstOrNull { it.name == name }?.capability

    fun propose(name: String, arguments: JsonNode): ProposalResult =
        tools.firstOrNull { it.name == name }?.propose(arguments)
            ?: ProposalResult(error = "Unknown action '$name'.")
}
