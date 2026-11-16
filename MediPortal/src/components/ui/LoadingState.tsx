import { LoaderCircle } from 'lucide-react'

/** Spinner with a visible text, announced politely. */
function LoadingState({ label }: { label: string }) {
  return (
    <p role="status" className="loading-state">
      <LoaderCircle size={20} aria-hidden="true" />
      <span>{label}</span>
    </p>
  )
}

export default LoadingState
