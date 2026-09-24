"use client";

import { Button, Card } from "@qms/ui";

/** Route-level error boundary: a recoverable failure shows a retry instead of a blank page. */
export default function RouteError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="mx-auto w-full max-w-md p-4">
      <Card header="Something went wrong">
        <p className="text-fg-muted">This page hit an unexpected problem. Try again, and reload the page if it keeps happening.</p>
        <Button onClick={reset}>Try again</Button>
      </Card>
    </div>
  );
}
