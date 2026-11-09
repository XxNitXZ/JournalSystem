import { useId } from 'react'

/**
 * Decorative healthcare artwork: a record card with a heartbeat chart, a shield and a medical cross.
 * Abstract shapes only; no people, names or patient data.
 */
function HeroIllustration() {
  const id = useId()
  const brand = `${id}-brand`
  const soft = `${id}-soft`
  return (
    <svg className="hero__art" viewBox="0 0 460 400" role="img" aria-label="Illustration of a digital patient record">
      <defs>
        <linearGradient id={brand} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#14a394" />
          <stop offset="1" stopColor="#1d5fd1" />
        </linearGradient>
        <linearGradient id={soft} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#14a394" stopOpacity="0.16" />
          <stop offset="1" stopColor="#1d5fd1" stopOpacity="0.12" />
        </linearGradient>
      </defs>
      <circle cx="250" cy="200" r="180" fill={`url(#${soft})`} />
      <circle cx="390" cy="70" r="26" fill="#1d5fd1" opacity="0.12" />
      <circle cx="70" cy="330" r="18" fill="#14a394" opacity="0.18" />

      {/* Record card */}
      <rect x="92" y="70" width="270" height="250" rx="22" fill="var(--color-surface)" stroke="var(--color-border)" strokeWidth="2" />
      <rect x="92" y="70" width="270" height="62" rx="22" fill={`url(#${brand})`} />
      <rect x="92" y="110" width="270" height="22" fill={`url(#${brand})`} />
      <circle cx="132" cy="101" r="17" fill="#ffffff" opacity="0.9" />
      <rect x="160" y="90" width="110" height="9" rx="4.5" fill="#ffffff" opacity="0.9" />
      <rect x="160" y="106" width="70" height="7" rx="3.5" fill="#ffffff" opacity="0.6" />

      {/* Heartbeat chart */}
      <rect x="116" y="152" width="222" height="78" rx="14" fill="var(--color-surface-muted)" stroke="var(--color-border)" />
      <path d="M126 196h40l12-26 18 50 16-38 10 14h106" fill="none" stroke={`url(#${brand})`} strokeWidth="5"
        strokeLinecap="round" strokeLinejoin="round" />

      {/* Detail rows */}
      <rect x="116" y="250" width="96" height="10" rx="5" fill="var(--color-border-strong)" />
      <rect x="226" y="250" width="112" height="10" rx="5" fill="var(--color-border)" />
      <rect x="116" y="274" width="132" height="10" rx="5" fill="var(--color-border-strong)" />
      <rect x="262" y="274" width="76" height="10" rx="5" fill="var(--color-border)" />

      {/* Shield */}
      <g transform="translate(316 228)">
        <path d="M44 4 80 18v28c0 24-16 42-36 50C24 88 8 70 8 46V18z" fill="var(--color-surface)" stroke="#1d5fd1" strokeWidth="3" />
        <path d="M28 50l11 11 22-24" fill="none" stroke="#14a394" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" />
      </g>

      {/* Medical cross */}
      <g transform="translate(46 118)">
        <rect width="64" height="64" rx="18" fill={`url(#${brand})`} />
        <path d="M27 14h10v13h13v10H37v13H27V37H14V27h13z" fill="#ffffff" />
      </g>
    </svg>
  )
}

export default HeroIllustration
