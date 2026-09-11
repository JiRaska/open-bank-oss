// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.rest

import com.openbank.communication.domain.PersonaNotFoundException
import com.openbank.communication.domain.PlaybookVersionConflictException
import com.openbank.communication.domain.PlaybookVersionNotFoundException
import com.openbank.communication.domain.PlaybookVersionValidationException
import com.openbank.communication.domain.StyleLintRejectedException
import com.openbank.communication.domain.StyleVersionConflictException
import com.openbank.communication.domain.StyleVersionNotFoundException
import com.openbank.communication.domain.StyleVersionValidationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

private fun status(s: Response.Status, e: Exception) = Response.status(s).entity(
    mapOf("error" to (e.message ?: s.reasonPhrase)),
).build()

@Provider class PersonaNotFoundMapper : ExceptionMapper<PersonaNotFoundException> {
    override fun toResponse(e: PersonaNotFoundException) = status(Response.Status.NOT_FOUND, e)
}

@Provider class StyleVersionNotFoundMapper : ExceptionMapper<StyleVersionNotFoundException> {
    override fun toResponse(e: StyleVersionNotFoundException) = status(Response.Status.NOT_FOUND, e)
}

@Provider class StyleVersionConflictMapper : ExceptionMapper<StyleVersionConflictException> {
    override fun toResponse(e: StyleVersionConflictException) = status(Response.Status.CONFLICT, e)
}

@Provider class StyleVersionValidationMapper : ExceptionMapper<StyleVersionValidationException> {
    override fun toResponse(e: StyleVersionValidationException) = status(Response.Status.BAD_REQUEST, e)
}

/** D3's lint rejection carries every violation, not just a generic 400 message. */
@Provider class StyleLintRejectedMapper : ExceptionMapper<StyleLintRejectedException> {
    override fun toResponse(e: StyleLintRejectedException) = Response.status(Response.Status.BAD_REQUEST).entity(
        mapOf("error" to "style text rejected by lint", "violations" to e.violations),
    ).build()
}

@Provider class PlaybookVersionNotFoundMapper : ExceptionMapper<PlaybookVersionNotFoundException> {
    override fun toResponse(e: PlaybookVersionNotFoundException) = status(Response.Status.NOT_FOUND, e)
}

@Provider class PlaybookVersionConflictMapper : ExceptionMapper<PlaybookVersionConflictException> {
    override fun toResponse(e: PlaybookVersionConflictException) = status(Response.Status.CONFLICT, e)
}

@Provider class PlaybookVersionValidationMapper : ExceptionMapper<PlaybookVersionValidationException> {
    override fun toResponse(e: PlaybookVersionValidationException) = status(Response.Status.BAD_REQUEST, e)
}
