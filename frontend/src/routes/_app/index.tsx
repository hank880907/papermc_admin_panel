import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/_app/')({
  component: ServersPage,
})

function ServersPage() {
  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold">Servers</h1>
      <p className="text-muted-foreground text-sm">
        Server registry will appear here in stage 3.
      </p>
    </div>
  )
}
