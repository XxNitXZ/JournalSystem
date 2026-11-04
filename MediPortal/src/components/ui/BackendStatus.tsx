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
      // Avbryt ett eventuellt tidigare anrop
      activeController?.abort()
      if (timeoutId !== undefined) clearTimeout(timeoutId)

      const controller = new AbortController()
      activeController = controller
      let timedOut = false

      // Max 5 sekunder per anrop
      timeoutId = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, 5000)

      try {
        const health = await getHealth({ signal: controller.signal })

        if (unmounted || controller.signal.aborted) return

        if (health && health.status === 'UP') {
          setState({ kind: 'online' })
        } else {
          setState({ kind: 'offline', reason: 'unexpected response' })
        }
      } catch (error: unknown) {
        if (unmounted) return

        if (timedOut) {
          setState({ kind: 'offline', reason: 'timeout' })
        } else if (!controller.signal.aborted) {
          setState({
            kind: 'offline',
            reason: describeError(error),
          })
        }
      } finally {
        clearTimeout(timeoutId)

        if (activeController === controller) {
          activeController = null
        }
      }
    }

    // Kontrollera direkt när komponenten öppnas
    void checkBackend()

    // Kontrollera sedan var 30:e sekund
    const intervalId = setInterval(() => {
      void checkBackend()
    }, 30000)

    // Rensa allt när komponenten avmonteras
    return () => {
      unmounted = true
      clearInterval(intervalId)
      if (timeoutId !== undefined) clearTimeout(timeoutId)
      activeController?.abort()
    }
  }, [])

  return (
    <div
      className={`status status--${state.kind}`}
      role="status"
      aria-busy={state.kind === 'loading'}
    >
      {state.kind === 'loading' && 'Backend: checking connection…'}
      {state.kind === 'online' && 'Backend: Online'}
      {state.kind === 'offline' && `Backend: Offline (${state.reason})`}
    </div>
  )
}

export default BackendStatus