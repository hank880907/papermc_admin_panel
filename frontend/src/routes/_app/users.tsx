import { createFileRoute, redirect } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { authStatusQuery, usersQuery } from '@/lib/auth'

export const Route = createFileRoute('/_app/users')({
  beforeLoad: async ({ context }) => {
    const status = await context.queryClient.ensureQueryData(authStatusQuery())
    if (!status.isAdmin) {
      throw redirect({ to: '/' })
    }
  },
  loader: ({ context }) => context.queryClient.ensureQueryData(usersQuery()),
  component: UsersPage,
})

function UsersPage() {
  const { data, isLoading, error } = useQuery(usersQuery())

  if (isLoading) return <p className="text-muted-foreground text-sm">Loading users…</p>
  if (error) return <p className="text-destructive text-sm">Failed to load users.</p>

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Users</h1>
        <p className="text-muted-foreground text-sm">
          Grant access in-game with <code className="rounded bg-muted px-1 py-0.5">/ap grant &lt;player&gt;</code>.
        </p>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Username</TableHead>
            <TableHead>MC UUID</TableHead>
            <TableHead>Role</TableHead>
            <TableHead>Granted</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data?.map((user) => (
            <TableRow key={user.id}>
              <TableCell className="font-medium">{user.username}</TableCell>
              <TableCell className="font-mono text-xs">{user.mcUuid}</TableCell>
              <TableCell>
                {user.isAdmin ? <Badge>Admin</Badge> : <Badge variant="secondary">Member</Badge>}
              </TableCell>
              <TableCell className="text-muted-foreground text-xs">
                {new Date(user.createdAt).toLocaleString()}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
