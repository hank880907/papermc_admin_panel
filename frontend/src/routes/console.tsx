import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/console')({
  component: ConsolePage,
})

function ConsolePage() {
  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold">Console</h1>
      <p className="text-muted-foreground text-sm">
        Live console will appear here in stage 4.
      </p>
    </div>
  )
}
