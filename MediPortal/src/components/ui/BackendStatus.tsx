import { useEffect, useState } from 'react'
import { getHealth } from '../../services/healthService'

type BackendState =
  | { kind: 'loading' }
  | { kind: 'online' }
  | { kind: 'offline' }

/** MediCore backend availability, checked on mount and every 30 seconds (5-second timeout per check). */
function BackendStatus() {
  const [state, setState] = useState<BackendState>({ kind: 'loading' })

  useEffect(() => {
    let unmounted = false
    let activeController: AbortController | null = null
    let timeoutId: ReturnType<typeof setTimeout> | undefined

    async function checkBackend() {
      // Cancel a previous check that is still running.
      activeController?.abort()

      if (timeoutId !== undefined) {
        clearTimeout(timeoutId)
      }

      const controller = new AbortController()
      activeController = controller

      let timedOut = false

      // Give up if the backend does not answer within 5 seconds.
      const currentTimeoutId = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, 5000)

      timeoutId = currentTimeoutId

      try {
        const health = await getHealth({
          signal: controller.signal,
        })

        if (unmounted || controller.signal.aborted) {
          return
        }

        if (health && health.status === 'UP') {
          setState({ kind: 'online' })
        } else {
          setState({ kind: 'offline' })
        }
      } catch {
        if (unmounted) {
          return
        }

        // A timeout aborts the request too; any other abort means a newer check replaced this one.
        if (timedOut || !controller.signal.aborted) {
          setState({ kind: 'offline' })
        }
      } finally {
        clearTimeout(currentTimeoutId)

        if (timeoutId === currentTimeoutId) {
          timeoutId = undefined
        }

        if (activeController === controller) {
          activeController = null
        }
      }
    }

    // Check immediately, then every 30 seconds.
    void checkBackend()

    const intervalId = setInterval(() => {
      void checkBackend()
    }, 30000)

    return () => {
      unmounted = true

      clearInterval(intervalId)

      if (timeoutId !== undefined) {
        clearTimeout(timeoutId)
      }

      activeController?.abort()
    }
  }, [])

  return (
    <p
      className="status"
      role="status"
      aria-busy={state.kind === 'loading'}
    >
      <span
        className={`status__indicator status__indicator--${state.kind}`}
        aria-hidden="true"
      />

      <span>
        {state.kind === 'loading' && 'Checking whether MediCore is available…'}
        {state.kind === 'online' && 'MediCore is available.'}
        {state.kind === 'offline' && 'MediCore is temporarily unavailable. Please try again later.'}
      </span>
    </p>
  )
}

export default BackendStatus
