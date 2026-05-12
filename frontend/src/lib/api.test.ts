import { describe, expect, it } from 'vitest'
import { ApiError } from './api'

describe('ApiError', () => {
  it('captures status and body', () => {
    const err = new ApiError(404, 'not found')
    expect(err.status).toBe(404)
    expect(err.body).toBe('not found')
    expect(err.message).toBe('api 404: not found')
  })
})
