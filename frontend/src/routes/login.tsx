import { createFileRoute, redirect, useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/lib/api'
import { authStatusQuery, useLogin } from '@/lib/auth'

interface LoginSearch {
  redirect?: string
}

export const Route = createFileRoute('/login')({
  validateSearch: (search: Record<string, unknown>): LoginSearch => ({
    redirect: typeof search.redirect === 'string' ? search.redirect : undefined,
  }),
  beforeLoad: async ({ context }) => {
    const status = await context.queryClient.ensureQueryData(authStatusQuery())
    if (status.authenticated) {
      throw redirect({ to: '/' })
    }
  },
  component: LoginPage,
})

function LoginPage() {
  const status = useQuery(authStatusQuery())
  const search = useSearch({ from: '/login' })
  const navigate = useNavigate()
  const login = useLogin()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  if (status.data?.userCount === 0) {
    return <FirstRunHint />
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    login.mutate(
      { username, password },
      {
        onSuccess: () => navigate({ to: search.redirect ?? '/' }),
      },
    )
  }

  const error =
    login.error instanceof ApiError && login.error.status === 401
      ? 'Invalid username or password.'
      : login.error
        ? 'Login failed. Please try again.'
        : null

  return (
    <CenteredCard>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in</CardTitle>
          <CardDescription>Use your Admin Panel credentials.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={onSubmit}>
            <div className="space-y-2">
              <Label htmlFor="username">Username</Label>
              <Input
                id="username"
                name="username"
                autoComplete="username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            {error && <p className="text-destructive text-sm">{error}</p>}
            <Button type="submit" className="w-full" disabled={login.isPending}>
              {login.isPending ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </CenteredCard>
  )
}

function FirstRunHint() {
  return (
    <CenteredCard>
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Bootstrap the first admin</CardTitle>
          <CardDescription>
            No users exist yet. The first server operator to run the in-game
            command below becomes the admin.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <p>
            On any connected Paper server, run <code className="rounded bg-muted px-1 py-0.5">/ap register</code>
            {' '}as an op.
          </p>
          <p className="text-muted-foreground">
            The server will respond with a one-time link. Open it to set your password,
            then return here to sign in.
          </p>
        </CardContent>
      </Card>
    </CenteredCard>
  )
}

function CenteredCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid min-h-svh place-items-center bg-background p-6">
      {children}
    </div>
  )
}
