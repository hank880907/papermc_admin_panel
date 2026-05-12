import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/lib/api'
import { registerTokenQuery, useCompleteRegistration, validatePassword } from '@/lib/auth'

export const Route = createFileRoute('/register/$token')({
  loader: ({ context, params }) =>
    context.queryClient.ensureQueryData(registerTokenQuery(params.token)),
  component: RegisterPage,
  errorComponent: InvalidTokenPage,
})

function RegisterPage() {
  const { token } = Route.useParams()
  const validate = useQuery(registerTokenQuery(token))
  const complete = useCompleteRegistration()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [clientError, setClientError] = useState<string | null>(null)

  if (validate.isLoading) {
    return <Centered><p className="text-muted-foreground text-sm">Validating link…</p></Centered>
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const err = validatePassword(password, confirm)
    if (err) {
      setClientError(err)
      return
    }
    setClientError(null)
    complete.mutate(
      { token, password },
      { onSuccess: () => navigate({ to: '/login' }) },
    )
  }

  const serverError =
    complete.error instanceof ApiError
      ? complete.error.body || 'Registration failed.'
      : complete.error
        ? 'Registration failed. Please try again.'
        : null

  return (
    <Centered>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Set your password</CardTitle>
          <CardDescription>
            Signing up <span className="font-medium">{validate.data?.username}</span>.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={onSubmit}>
            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                autoComplete="new-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <p className="text-muted-foreground text-xs">At least 8 characters.</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirm">Confirm password</Label>
              <Input
                id="confirm"
                type="password"
                autoComplete="new-password"
                required
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
              />
            </div>
            {(clientError ?? serverError) && (
              <p className="text-destructive text-sm">{clientError ?? serverError}</p>
            )}
            <Button type="submit" className="w-full" disabled={complete.isPending}>
              {complete.isPending ? 'Setting password…' : 'Set password'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </Centered>
  )
}

function InvalidTokenPage() {
  return (
    <Centered>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Link expired</CardTitle>
          <CardDescription>
            This registration link is no longer valid. Ask an admin to issue a new one,
            or run <code className="rounded bg-muted px-1 py-0.5">/ap register</code> in-game again.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild className="w-full">
            <Link to="/login">Back to sign in</Link>
          </Button>
        </CardContent>
      </Card>
    </Centered>
  )
}

function Centered({ children }: { children: React.ReactNode }) {
  return <div className="grid min-h-svh place-items-center bg-background p-6">{children}</div>
}
