import { useEffect, useState } from 'react'
import { useLocation } from 'react-router'
import { getPendingJournalAccessCount, JOURNAL_ACCESS_CHANGED } from './journalAccessService.ts'

/**
 * The number of journal access requests awaiting review, for DOCTOR accounts; null while unknown or when not
 * enabled. Refreshed on navigation and after every decision. A failure simply hides the count.
 */
export function usePendingJournalAccessCount(enabled: boolean): number | null {
  const { pathname } = useLocation()
  const [count, setCount] = useState<number | null>(null)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    if (!enabled) return undefined
    const refresh = () => setVersion((value) => value + 1)
    window.addEventListener(JOURNAL_ACCESS_CHANGED, refresh)
    return () => window.removeEventListener(JOURNAL_ACCESS_CHANGED, refresh)
  }, [enabled])

  useEffect(() => {
    if (!enabled) return undefined
    const controller = new AbortController()
    getPendingJournalAccessCount({ signal: controller.signal })
      .then((pending) => { if (!controller.signal.aborted) setCount(pending) })
      .catch(() => { if (!controller.signal.aborted) setCount(null) })
    return () => controller.abort()
  }, [enabled, pathname, version])

  return enabled ? count : null
}
