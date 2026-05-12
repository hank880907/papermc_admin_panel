import { Link, Outlet } from '@tanstack/react-router'
import { Server, Terminal, Users } from 'lucide-react'
import { ThemeToggle } from '@/components/ThemeToggle'
import { cn } from '@/lib/utils'

const navItems = [
  { to: '/', label: 'Servers', icon: Server },
  { to: '/console', label: 'Console', icon: Terminal },
  { to: '/players', label: 'Players', icon: Users },
] as const

export function AppShell() {
  return (
    <div className="grid min-h-svh grid-cols-[14rem_1fr] bg-background text-foreground">
      <aside className="border-r border-border bg-sidebar text-sidebar-foreground">
        <div className="flex h-14 items-center border-b border-sidebar-border px-4 font-semibold">
          Admin Panel
        </div>
        <nav className="flex flex-col gap-1 p-2">
          {navItems.map((item) => (
            <Link
              key={item.to}
              to={item.to}
              className={cn(
                'flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors',
                'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
              )}
              activeProps={{ className: 'bg-sidebar-accent text-sidebar-accent-foreground' }}
            >
              <item.icon className="h-4 w-4" />
              {item.label}
            </Link>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-col">
        <header className="flex h-14 items-center justify-end gap-2 border-b border-border px-4">
          <ThemeToggle />
        </header>
        <main className="min-w-0 flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
