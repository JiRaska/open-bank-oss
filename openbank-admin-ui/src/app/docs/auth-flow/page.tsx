// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// ---------------------------------------------------------------------------
// Auth Flow — the flagship "one process, three lenses" page. Now manifest-driven:
// this server component loads + validates src/content/processes/auth-flow.yaml
// (a malformed/drifted manifest fails `next build` — the CI gate) and hands it to
// the client ProcessView. Adding a process = adding a YAML manifest, not a page
// (ADR-0019 / ADR-0029; supersedes the old hardcoded-in-page approach).
// ---------------------------------------------------------------------------

import { loadProcess } from '@/lib/docs/process/load'
import { ProcessView } from '@/components/docs/ProcessView'

export default function AuthFlowPage() {
  const proc = loadProcess('auth-flow')
  return <ProcessView proc={proc} />
}
