import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'

export interface AuthStatus {
  authenticated: boolean
  userCount: number
  username?: string
  isAdmin?: boolean
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  username: string
  isAdmin: boolean
}

export interface RegisterValidate {
  username: string
}

export interface UserListItem {
  id: number
  mcUuid: string
  username: string
  isAdmin: boolean
  createdAt: number
}

export const authStatusQuery = () =>
  queryOptions({
    queryKey: ['authStatus'] as const,
    queryFn: () => api.get<AuthStatus>('/api/auth/status'),
  })

export const registerTokenQuery = (token: string) =>
  queryOptions({
    queryKey: ['register', token] as const,
    queryFn: () => api.get<RegisterValidate>(`/api/auth/register/${token}`),
    retry: false,
    staleTime: 0,
  })

export const usersQuery = () =>
  queryOptions({
    queryKey: ['users'] as const,
    queryFn: () => api.get<UserListItem[]>('/api/users'),
  })

export function useAuthStatus() {
  return useQuery(authStatusQuery())
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (req: LoginRequest) => api.post<LoginResponse>('/api/auth/login', req),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['authStatus'] }),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/api/auth/logout'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['authStatus'] }),
  })
}

export function useCompleteRegistration() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (req: { token: string; password: string }) =>
      api.post<void>('/api/auth/register/complete', req),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['authStatus'] }),
  })
}

export function validatePassword(password: string, confirm: string): string | null {
  if (password.length < 8) return 'Password must be at least 8 characters.'
  if (password !== confirm) return 'Passwords do not match.'
  return null
}
