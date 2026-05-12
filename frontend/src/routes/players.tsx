import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/players')({
  component: PlayersPage,
})

function PlayersPage() {
  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold">Players</h1>
      <p className="text-muted-foreground text-sm">
        Player ops table will appear here in stage 5.
      </p>
    </div>
  )
}
