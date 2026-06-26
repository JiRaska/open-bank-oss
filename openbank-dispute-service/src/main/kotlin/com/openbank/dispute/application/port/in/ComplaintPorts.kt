// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.application.port.`in`

import com.openbank.dispute.domain.model.CloseComplaintRequest
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintStatus
import com.openbank.dispute.domain.model.FileComplaintRequest
import com.openbank.dispute.domain.model.InterimReplyRequest
import com.openbank.dispute.domain.model.ResolveComplaintRequest
import io.smallrye.mutiny.Uni
import java.util.UUID

interface FileComplaintUseCase {
    fun file(request: FileComplaintRequest): Uni<Complaint>
}

interface HandleComplaintUseCase {
    fun interimReply(id: UUID, request: InterimReplyRequest): Uni<Complaint>
    fun resolve(id: UUID, request: ResolveComplaintRequest): Uni<Complaint>
    fun close(id: UUID, request: CloseComplaintRequest): Uni<Complaint>
}

interface GetComplaintUseCase {
    fun getComplaint(id: UUID): Uni<Complaint?>
    fun listByStatus(status: ComplaintStatus): Uni<List<Complaint>>
    fun listAll(): Uni<List<Complaint>>
}
