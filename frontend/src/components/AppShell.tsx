import { Link, Outlet, useNavigate } from '@tanstack/react-router'
import { LogOut, Server, Terminal, UserCog, Users } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ThemeToggle } from '@/components/ThemeToggle'
import { useAuthStatus, useLogout } from '@/lib/auth'
import { cn } from '@/lib/utils'

const baseNav = [
  { to: '/', label: 'Servers', icon: Server },
  { to: '/console', label: 'Console', icon: Terminal },
  { to: '/players', label: 'Players', icon: Users },
] as const

export function AppShell() {
  const status = useAuthStatus()
  const logout = useLogout()
  const navigate = useNavigate()
  const isAdmin = status.data?.isAdmin === true

  function onLogout() {
    logout.mutate(undefined, {
      onSuccess: () => navigate({ to: '/login' }),
    })
  }

  return (
    <div className="grid min-h-svh grid-cols-[14rem_1fr] bg-background text-foreground">
      <aside className="border-r border-border bg-sidebar text-sidebar-foreground">
        <div className="flex h-14 items-center border-b border-sidebar-border px-4 font-semibold">
          Admin Panel
        </div>
        <nav className="flex flex-col gap-1 p-2">
          {baseNav.map((item) => (
            <NavLink key={item.to} {...item} />
          ))}
          {isAdmin && <NavLink to="/users" label="Users" icon={UserCog} />}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-col">
        <header className="flex h-14 items-center justify-end gap-2 border-b border-border px-4">
          {status.data?.username && (
            <span className="text-muted-foreground mr-2 text-sm">
              {status.data.username}
            </span>
          )}
          <ThemeToggle />
          <Button
            variant="ghost"
            size="icon"
            aria-label="Sign out"
            onClick={onLogout}
            disabled={logout.isPending}
          >
            <LogOut className="h-4 w-4" />
          </Button>
        </header>
        <main className="min-w-0 flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function NavLink({
  to,
  label,
  icon: Icon,
}: {
  to: '/' | '/console' | '/players' | '/users'
  label: string
  icon: typeof Server
}) {
  return (
    <Link
      to={to}
      className={cn(
        'flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors',
        'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
      )}
      activeProps={{ className: 'bg-sidebar-accent text-sidebar-accent-foreground' }}
    >
      <Icon className="h-4 w-4" />
      {label}
    </Link>
  )
}
