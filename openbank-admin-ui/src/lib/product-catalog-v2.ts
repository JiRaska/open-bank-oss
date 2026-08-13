// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'
import type {
  CatalogSchema, MarketContext, Offering, OfferingRequest, ProductRevision, PublishRequest,
  RevisionContent, RevisionRequest, SchemaRef, Specification, SpecificationRequest, ValidateCatalogResponse,
} from '@/lib/generated/product-catalog-v2'
import {
  catalogOperationPath, catalogOperations, type CatalogOperationHeaders, type CatalogOperationId,
  type CatalogOperationPathParameters, type CatalogOperationQuery, type CatalogOperationRequest,
  type CatalogOperationResponse,
} from '@/lib/generated/product-catalog-v2'

export type {
  CatalogSchema, MarketContext, Offering, OfferingRequest, ProductRevision, PublishRequest,
  RevisionContent, RevisionRequest, SchemaRef, Specification, SpecificationRequest, ValidateCatalogResponse,
}

export class CatalogV2Error extends Error {
  constructor(public readonly kind: BffFailure, message: string, public readonly status: number) {
    super(message)
  }
}

export interface CatalogV2Transport {
  baseUrl?: string
  fetcher?: typeof fetch
}

type RequiredKeys<T> = { [K in keyof T]-?: object extends Pick<T, K> ? never : K }[keyof T]
type RequiredWhenPresent<T, K extends string> = keyof T extends never
  ? { [P in K]?: never }
  : [RequiredKeys<T>] extends [never]
    ? { [P in K]?: T }
    : { [P in K]: T }
type BodyFor<I extends CatalogOperationId> = CatalogOperationRequest<I> extends undefined
  ? { body?: never }
  : { body: CatalogOperationRequest<I> }
export type CatalogOperationOptions<I extends CatalogOperationId> =
  Omit<RequestInit, 'method' | 'body' | 'headers'> &
  RequiredWhenPresent<CatalogOperationPathParameters<I>, 'pathParameters'> &
  RequiredWhenPresent<CatalogOperationQuery<I>, 'query'> &
  RequiredWhenPresent<CatalogOperationHeaders<I>, 'headers'> &
  BodyFor<I>

async function catalogV2Fetch(
  path: string,
  options: RequestInit = {},
  fetcher: typeof fetch = fetch,
  baseUrl?: string,
): Promise<unknown> {
  const url = baseUrl
    ? `${baseUrl.replace(/\/$/, '')}/api/v2${path}`
    : svcUrl('product-catalog', `/api/v2${path}`)
  const response = await fetcher(url, {
    cache: 'no-store',
    signal: AbortSignal.timeout(8_000),
    ...options,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...options.headers },
  })
  if (!response.ok) {
    const kind = await classifyBffFailure(response.clone())
    const body = await response.json().catch(() => ({})) as { message?: string; error?: string }
    throw new CatalogV2Error(kind, body.message ?? body.error ?? response.statusText, response.status)
  }
  return response.json() as Promise<unknown>
}

export function createCatalogV2Client(transport: CatalogV2Transport) {
  return <I extends CatalogOperationId>(operationId: I, options: CatalogOperationOptions<I>) =>
    catalogV2Operation(operationId, options, transport)
}

export function catalogV2Operation<I extends CatalogOperationId>(
  operationId: I,
  options: CatalogOperationOptions<I>,
  transport: CatalogV2Transport = {},
): Promise<CatalogOperationResponse<I>> {
  const { pathParameters, query, body, ...request } = options as CatalogOperationOptions<I> & {
    pathParameters?: Record<string, string | number>
    query?: Record<string, string | number | undefined>
    body?: CatalogOperationRequest<I>
  }
  return catalogV2Fetch(
    catalogOperationPath(operationId, pathParameters, query),
    {
      ...(request as unknown as RequestInit), method: catalogOperations[operationId].method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    transport.fetcher ?? fetch,
    transport.baseUrl,
  ) as Promise<CatalogOperationResponse<I>>
}

export const catalogPaths = {
  schemas: catalogOperationPath('listProductTypesV2'),
  schema: (id: string, version: number) => catalogOperationPath(
    'getProductTypeVersionV2', { id, version },
  ),
  validate: (id: string, version: number) => catalogOperationPath(
    'validateProductAttributesV2', { id, version },
  ),
  specifications: catalogOperationPath('listSpecificationsV2'),
  offerings: (specificationId?: string) => specificationId
    ? catalogOperationPath('listOfferingsV2', {}, { specificationId })
    : catalogOperationPath('listOfferingsV2'),
  revisions: (offeringId: string) => catalogOperationPath('listOfferingRevisionsV2', { id: offeringId }),
  revision: (offeringId: string, revisionId: string) => catalogOperationPath(
    'getOfferingRevisionV2', { offeringId, revisionId },
  ),
  publish: (offeringId: string, revisionId: string) => catalogOperationPath(
    'publishOfferingRevisionV2', { offeringId, revisionId },
  ),
} as const
