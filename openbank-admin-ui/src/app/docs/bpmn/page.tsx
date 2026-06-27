// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ---------------------------------------------------------------------------
// Business Process Diagrams (BPMN 2.0). Now manifest-driven: this server
// component loads + validates every src/content/bpmn/<slug>.yaml (a malformed or
// drifted manifest fails `next build` — the CI gate) and hands them to the
// client BpmnView. Adding/changing a process = editing a YAML manifest, not this
// page (ADR-0019 / ADR-0029; supersedes the old hardcoded-in-page array).
// ---------------------------------------------------------------------------

import { loadAllBpmnProcesses } from '@/lib/docs/bpmn/load'
import { BpmnView } from '@/components/docs/BpmnView'

export default function BpmnPage() {
  const processes = loadAllBpmnProcesses()
  return <BpmnView processes={processes} />
}
