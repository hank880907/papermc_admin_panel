import { createFileRoute, redirect } from '@tanstack/react-router'
import { AppShell } from '@/components/AppShell'
import { authStatusQuery } from '@/lib/auth'

export const Route = createFileRoute('/_app')({
  beforeLoad: async ({ context, location }) => {
    const status = await context.queryClient.ensureQueryData(authStatusQuery())
    if (!status.authenticated) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: AppShell,
})
