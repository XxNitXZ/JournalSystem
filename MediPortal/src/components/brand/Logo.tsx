import { useId } from 'react'
import { Link } from 'react-router'

/** The MediCore mark: a rounded shield-square with a medical cross and a heartbeat line. */
export function LogoMark({ className = 'logo__mark' }: { className?: string }) {
  const gradient = useId()
  return (
    <svg className={className} viewBox="0 0 48 48" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={gradient} x1="0" y1="0" x2="48" y2="48" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#14a394" />
          <stop offset="1" stopColor="#1d5fd1" />
        </linearGradient>
      </defs>
      <rect x="2" y="2" width="44" height="44" rx="13" fill={`url(#${gradient})`} />
      <path d="M20 11h8v9h9v8h-9v9h-8v-9h-9v-8h9z" fill="#ffffff" opacity="0.95" />
      <path d="M8 31h8l3-6 4 11 4-14 3 9h10" fill="none" stroke="#0f2537" strokeOpacity="0.28"
        strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** MediCore logo with wordmark; links to the home page. */
function Logo({ inverse = false, tagline = true }: { inverse?: boolean; tagline?: boolean }) {
  return (
    <Link to="/" className={inverse ? 'logo logo--inverse' : 'logo'} aria-label="MediCore home">
      <LogoMark />
      <span className="logo__text" aria-hidden="true">
        <span className="logo__name">Medi<span>Core</span></span>
        {tagline && <span className="logo__tagline">Healthcare records platform</span>}
      </span>
    </Link>
  )
}

export default Logo
