// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { DYNAMIC_ROUTE_CLASSIFICATIONS, readAppPageRoutes, readSidebarDestinations } from './helpers/navigation-inventory'
import { STATIC_ROUTE_INVENTORY } from './helpers/static-route-inventory'

test('every App Router page has an explicit route inventory and fixture disposition', () => {
  const routes = readAppPageRoutes()
  const staticRoutes = routes.filter(route => !route.includes('['))
  const dynamicRoutes = routes.filter(route => route.includes('['))
  const unclassifiedStatic = staticRoutes.filter(route => !STATIC_ROUTE_INVENTORY[route])
  const staleStatic = Object.keys(STATIC_ROUTE_INVENTORY).filter(route => !staticRoutes.includes(route))
  const unclassifiedDynamic = dynamicRoutes.filter(route => !DYNAMIC_ROUTE_CLASSIFICATIONS[route])
  const staleDynamic = Object.keys(DYNAMIC_ROUTE_CLASSIFICATIONS).filter(route => !dynamicRoutes.includes(route))
  const sidebarAppRoutes = readSidebarDestinations().filter(destination => !destination.external).map(destination => destination.href).sort()
  const classifiedSidebarRoutes = Object.entries(STATIC_ROUTE_INVENTORY)
    .filter(([, fixture]) => fixture.disposition === 'sidebar-navigation-smoke')
    .map(([route]) => route).sort()

  expect(unclassifiedStatic, 'Every new static page needs an explicit safe route fixture/disposition').toEqual([])
  expect(staleStatic, 'Remove inventory entries for deleted static pages').toEqual([])
  expect(unclassifiedDynamic, 'Every new dynamic page needs an explicit representative fixture requirement').toEqual([])
  expect(staleDynamic, 'Remove fixture classifications for deleted dynamic pages').toEqual([])
  expect(classifiedSidebarRoutes, 'Static sidebar dispositions must match the source navigation').toEqual(sidebarAppRoutes)
  for (const [route, fixture] of Object.entries(STATIC_ROUTE_INVENTORY)) {
    expect(fixture.path, `${route} must have a direct-load fixture`).toBe(route)
  }
})
