import { describe, expect, it } from 'vitest'
import { validatePassword } from './auth'

describe('validatePassword', () => {
  it('returns null when both fields meet length and match', () => {
    expect(validatePassword('correcthorse', 'correcthorse')).toBeNull()
  })

  it('rejects passwords shorter than 8 characters', () => {
    expect(validatePassword('short', 'short')).toBe('Password must be at least 8 characters.')
  })

  it('rejects mismatched confirmation', () => {
    expect(validatePassword('correcthorse', 'wrongbattery')).toBe('Passwords do not match.')
  })

  it('reports the length error before the mismatch error', () => {
    expect(validatePassword('a', 'b')).toBe('Password must be at least 8 characters.')
  })
})
