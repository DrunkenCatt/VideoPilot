export type UiDensity = 'comfortable' | 'compact'

interface UiPreferences {
  density: UiDensity
}

export interface UiPreferencesLoadResult extends UiPreferences {
  failed: boolean
}

const keyPrefix = 'videopilot:ui-preferences:'
const defaultPreferences: UiPreferences = { density: 'comfortable' }

export function uiPreferencesKey(ownerId: string): string {
  return `${keyPrefix}${ownerId}`
}

export function loadUiPreferences(ownerId: string, storage?: Storage): UiPreferencesLoadResult {
  try {
    const raw = (storage ?? globalThis.localStorage).getItem(uiPreferencesKey(ownerId))
    if (!raw) return { ...defaultPreferences, failed: false }
    const parsed = JSON.parse(raw) as { density?: unknown }
    return {
      density: parsed.density === 'compact' || parsed.density === 'comfortable'
        ? parsed.density
        : defaultPreferences.density,
      failed: false,
    }
  } catch {
    return { ...defaultPreferences, failed: true }
  }
}

export function saveUiDensity(ownerId: string, density: UiDensity, storage?: Storage): boolean {
  try {
    (storage ?? globalThis.localStorage).setItem(uiPreferencesKey(ownerId), JSON.stringify({ density }))
    return true
  } catch {
    return false
  }
}

export function clearUiPreferences(ownerId: string, storage?: Storage): boolean {
  try {
    (storage ?? globalThis.localStorage).removeItem(uiPreferencesKey(ownerId))
    return true
  } catch {
    return false
  }
}
