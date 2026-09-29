// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The specialist inbox projections must stay aligned with the providers' published read
// contracts. UI mocks alone cannot notice a renamed identity or lifecycle field.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import YAML from 'yaml'
import { describe, expect, it } from 'vitest'

function contract(service: string) {
  return YAML.parse(readFileSync(resolve('..', service, 'src/main/resources/openapi.yaml'), 'utf8'))
}

function listSchema(api: ReturnType<typeof contract>, path: string) {
  const response = api.paths[path].get.responses['200'].content['application/json'].schema
  expect(response.type).toBe('array')
  return response.items.$ref
}

describe('specialist approval inbox provider contracts', () => {
  it('reads Treasury pending deals with their submitted actor and transition time', () => {
    const api = contract('openbank-treasury-service')
    expect(listSchema(api, '/api/v1/treasury/deals')).toBe('#/components/schemas/Deal')
    const states = api.components.schemas.DealState
    expect(states.enum).toContain('PENDING_APPROVAL')
    const fields = api.components.schemas.Deal.properties
    expect(fields).toHaveProperty('dealId')
    expect(fields).toHaveProperty('submittedBy')
    expect(fields.history.items.$ref).toBe('#/components/schemas/Transition')
    const transition = api.components.schemas.Transition.properties
    expect(transition).toHaveProperty('to')
    expect(transition.at.format).toBe('date-time')
  })

  it('reads bounded lending backfill history with its proposer and nullable timestamp', () => {
    const api = contract('openbank-lending-service')
    const list = api.paths['/api/v1/lending/ledger-backfill/requests'].get
    const limit = list.parameters.find((parameter: { name: string }) => parameter.name === 'limit')
    expect(limit.schema.maximum).toBe(100)
    expect(list.responses['200'].content['application/json'].schema.properties.requests.items.$ref)
      .toBe('#/components/schemas/LedgerBackfillRequestView')
    const fields = api.components.schemas.LedgerBackfillRequestView.properties
    expect(fields.state.enum).toContain('PROPOSED')
    expect(fields).toHaveProperty('proposedBy')
    expect(fields.proposedAt.nullable).toBe(true)
  })

  it('reads the lending compliance-pack proposal actor and nullable proposal time', () => {
    const api = contract('openbank-lending-service')
    expect(listSchema(api, '/api/v1/lending/compliance-packs/proposals/pending'))
      .toBe('#/components/schemas/PackActivationView')
    const fields = api.components.schemas.PackActivationView.properties
    expect(fields).toHaveProperty('id')
    expect(fields.state.enum).toContain('PROPOSED')
    expect(fields).toHaveProperty('proposedBy')
    expect(fields.proposedAt.nullable).toBe(true)
  })

  it('reads campaign maker and submission-state update time', () => {
    const api = contract('openbank-campaign-service')
    expect(listSchema(api, '/api/v1/campaigns')).toBe('#/components/schemas/Campaign')
    const fields = api.components.schemas.Campaign.properties
    expect(fields).toHaveProperty('id')
    expect(fields.state.enum).toContain('PENDING_APPROVAL')
    expect(fields).toHaveProperty('createdBy')
    expect(fields.updatedAt.format).toBe('date-time')
  })

  it('does not invent an audience proposal timestamp absent from the summary', () => {
    const api = contract('openbank-campaign-service')
    expect(listSchema(api, '/api/v1/audiences')).toBe('#/components/schemas/AudienceSummary')
    const fields = api.components.schemas.AudienceSummary.properties
    expect(fields.state.enum).toContain('PENDING_APPROVAL')
    expect(fields).toHaveProperty('createdBy')
    expect(fields).not.toHaveProperty('proposedAt')
    expect(fields).not.toHaveProperty('updatedAt')
  })

  it('projects only the first recorded checker from an identity case, not applicant data', () => {
    const api = contract('openbank-pid-service')
    expect(listSchema(api, '/api/v1/parties/cases')).toBe('#/components/schemas/VerificationCaseResponse')
    const fields = api.components.schemas.VerificationCaseResponse.properties
    expect(fields.status.enum).toContain('AWAITING_SECOND_APPROVAL')
    expect(fields).toHaveProperty('firstApprover')
    expect(fields.firstAt.format).toBe('date-time')
    expect(fields).toHaveProperty('applicant')
  })
})
