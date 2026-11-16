/**
 * A small count indicator, e.g. requests awaiting review. Renders nothing for zero or an unknown count. The
 * visible number is decorative; the label gives screen readers the full meaning.
 */
function CountBadge({ count, label }: { count: number | null; label: (count: number) => string }) {
  if (count === null || count <= 0) return null
  return (
    <span className="count-badge">
      <span aria-hidden="true">{count > 99 ? '99+' : count}</span>
      <span className="visually-hidden">{label(count)}</span>
    </span>
  )
}

export default CountBadge
