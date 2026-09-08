# SPDX-License-Identifier: Apache-2.0
# document-service REST extension for external disclosure export.
#
# `document.disclosure.export` is not a general document read. It transforms an
# internal PDF into a recipient-bound, institutionally sealed external artifact.
# The action is therefore granted to one dedicated client-credentials subject only:
# delegation-service validates grant consent, recipient binding and the one-time link;
# document-service owns only the watermark-and-seal transformation.
#
# Do not widen this to `document.*`, a role, or `service-account-*`. Keycloak currently
# classifies client-credentials principals as HUMAN, and the fleet-wide shared client may
# carry operator-like roles. The exact preferred_username is the security boundary.

package openbank.rest

import rego.v1

external_disclosure_exporter := "service-account-openbank-delegation-disclosure"

allowed_reasons contains "service-delegation-external-disclosure-export" if {
    input.principal.type == "HUMAN"
    input.principal.id == external_disclosure_exporter
    input.action == "document.disclosure.export"
}

# Keep this an explicit veto, not merely absence of an allow reason: an action-matrix or
# future broad document rule cannot accidentally re-admit a human operator, customer edge,
# shared backend identity, or another service account.
prohibited if {
    input.action == "document.disclosure.export"
    input.principal.id != external_disclosure_exporter
}
