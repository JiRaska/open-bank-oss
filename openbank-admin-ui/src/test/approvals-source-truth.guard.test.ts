// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const routeSource = fs.readFileSync(path.join(process.cwd(), 'src/app/api/approvals/pending/route.ts'), 'utf8')
const pageSource = fs.readFileSync(path.join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

describe('approval inbox source truthfulness', () => {
  it('has no known approval source left silently unwired', () => {
    expect(routeSource).not.toContain('NOT_CONFIGURED_SOURCES')
    expect(routeSource).toContain('consentPending(headers)')
    expect(routeSource).toContain('consent: consent.state')
  })

  it('fails visibly when the federated queue itself does not answer', () => {
    expect(pageSource).toContain("if (!inboxRes.ok) throw new Error('queue')")
    expect(pageSource).toContain('Do not interpret an empty screen as no pending decisions.')
  })

  it('names the screen for the whole bank workflow rather than only the AI subsection', () => {
    expect(pageSource).toContain("t('Centrum schvalování', 'Approval centre')")
    expect(pageSource).toContain('The domain queue is an overview only')
  })

  it('renders the proposer identity supplied by the BFF rather than assuming every proposer is a bot', () => {
    expect(pageSource).toContain("const aiGenerated = p.agent ? p.agent.icon === 'bot'")
    expect(pageSource).toContain('const ProposerIcon = aiGenerated ? Bot : UserRound')
  })
})
