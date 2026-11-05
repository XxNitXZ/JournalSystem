import { useEffect, useState } from 'react'
import { getHealth } from '../../services/healthService'
import { ApiError } from '../../services/apiClient'

type BackendState =
  | { kind: 'loading' }
  | { kind: 'online' }
  | { kind: 'offline'; reason: string }

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return `HTTP ${error.status}`
  }

  return 'not reachable'
}

function BackendStatus() {
  const [state, setState] = useState<BackendState>({ kind: 'loading' })

  useEffect(() => {
    let unmounted = false
    let activeController: AbortController | null = null
    let timeoutId: ReturnType<typeof setTimeout> | undefined

    async function checkBackend() {
      // Avbryt eventuellt tidigare anrop
      activeController?.abort()

      if (timeoutId !== undefined) {
        clearTimeout(timeoutId)
      }

      const controller = new AbortController()
      activeController = controller

      let timedOut = false

      // Avbryt anropet om backend inte svarar inom 5 sekunder
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
          setState({
            kind: 'offline',
            reason: 'unexpected response',
          })
        }
      } catch (error: unknown) {
        if (unmounted) {
          return
        }

        if (timedOut) {
          setState({
            kind: 'offline',
            reason: 'timeout',
          })
        } else if (!controller.signal.aborted) {
          setState({
            kind: 'offline',
            reason: describeError(error),
          })
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

    // Kontrollera backend direkt när komponenten monteras
    void checkBackend()

    // Kontrollera sedan automatiskt var 30:e sekund
    const intervalId = setInterval(() => {
      void checkBackend()
    }, 30000)

    // Cleanup när komponenten avmonteras
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
    <div
      className="status"
      role="status"
      aria-busy={state.kind === 'loading'}
    >
      <span
        className={`status__indicator status__indicator--${state.kind}`}
        aria-hidden="true"
      />

      <span>
        {state.kind === 'loading' && 'Backend: checking connection…'}
        {state.kind === 'online' && 'Backend: Online'}
        {state.kind === 'offline' &&
          `Backend: Offline (${state.reason})`}
      </span>
    </div>
  )
}

export default BackendStatus