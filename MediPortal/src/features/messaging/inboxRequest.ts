/** Cleanup aborts transport and ignores late responses even if transport ignores cancellation. */
export function loadInboxRequest<T>(load: (signal: AbortSignal) => Promise<T>,
  loaded: (data: T) => void, failed: (error: unknown) => void): () => void {
  const controller = new AbortController()
  load(controller.signal)
    .then((data) => { if (!controller.signal.aborted) loaded(data) })
    .catch((error: unknown) => { if (!controller.signal.aborted) failed(error) })
  return () => controller.abort()
}
