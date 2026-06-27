// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Minimal GitHub REST API wire types for opening a remediation-proposal PR (ADR-0119). */

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitRefResponse(@JsonProperty("object") val obj: GitRefObject = GitRefObject())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitRefObject(val sha: String = "")

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateRefRequest(val ref: String, val sha: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PutContentRequest(val message: String, val content: String, val branch: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreatePrRequest(val title: String, val head: String, val base: String, val body: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreatePrResponse(@JsonProperty("html_url") val htmlUrl: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class WorkflowRunsResponse(@JsonProperty("workflow_runs") val workflowRuns: List<WorkflowRun> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class WorkflowRun(val conclusion: String? = null)

/** GitHub Issues API returns PRs too; `pull_request` is non-null for a PR, so we filter those out. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class IssueItem(@JsonProperty("pull_request") val pullRequest: Map<String, Any>? = null)
