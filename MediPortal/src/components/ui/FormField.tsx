import type { ReactNode } from 'react'

interface FormFieldProps {
  /** ID of the control inside, used by the label. */
  htmlFor: string
  label: string
  /** Optional help text; give the control aria-describedby={hintId}. */
  hint?: ReactNode
  hintId?: string
  children: ReactNode
}

/** Label, control and optional hint with consistent spacing. */
function FormField({ htmlFor, label, hint, hintId, children }: FormFieldProps) {
  return (
    <div className="field">
      <label htmlFor={htmlFor} className="field__label">{label}</label>
      {children}
      {hint && <p id={hintId} className="field__hint">{hint}</p>}
    </div>
  )
}

export default FormField
