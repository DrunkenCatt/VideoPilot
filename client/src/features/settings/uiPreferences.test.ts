import { beforeEach, describe, expect, it } from 'vitest'

import {
  clearUiPreferences,
  loadUiPreferences,
  saveUiDensity,
  uiPreferencesKey,
} from './uiPreferences'

const ownerId = 'f97a9f68-b14c-412c-a0f2-1ad20c62f769'

describe('owner-scoped UI preferences', () => {
  beforeEach(() => localStorage.clear())

  it('persists only the supported density value under the current owner', () => {
    expect(saveUiDensity(ownerId, 'compact')).toBe(true)
    expect(loadUiPreferences(ownerId)).toEqual({ density: 'compact', failed: false })
    expect(localStorage.getItem(uiPreferencesKey(ownerId))).toBe('{"density":"compact"}')
  })

  it('falls back safely for malformed or unsupported local data', () => {
    localStorage.setItem(uiPreferencesKey(ownerId), '{"density":"oversized","token":"secret"}')

    expect(loadUiPreferences(ownerId)).toEqual({ density: 'comfortable', failed: false })
  })

  it('reports unavailable storage and clears only the current owner preference', () => {
    const otherOwner = '00000000-0000-0000-0000-000000000002'
    localStorage.setItem(uiPreferencesKey(ownerId), '{"density":"compact"}')
    localStorage.setItem(uiPreferencesKey(otherOwner), '{"density":"compact"}')

    expect(clearUiPreferences(ownerId)).toBe(true)
    expect(localStorage.getItem(uiPreferencesKey(ownerId))).toBeNull()
    expect(localStorage.getItem(uiPreferencesKey(otherOwner))).not.toBeNull()

    const unavailable = { getItem: () => { throw new Error('blocked') } } as unknown as Storage
    expect(loadUiPreferences(ownerId, unavailable)).toEqual({ density: 'comfortable', failed: true })
  })
})
