// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Minimal GitHub REST API wire types for opening a ticket or proposal PR (ADR-0163). */

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

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateIssueRequest(val title: String, val body: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IssueResponse(val number: Int = 0, @JsonProperty("html_url") val htmlUrl: String = "")
