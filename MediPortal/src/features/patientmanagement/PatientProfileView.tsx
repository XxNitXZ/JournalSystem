import type { ReactNode } from 'react'
import { UserRound } from 'lucide-react'
import Badge from '../../components/ui/Badge.tsx'
import {
  displayBirthDate,
  displayEmail,
  displayGender,
  displayName,
  displayPhone,
  displayPersonnummer,
  NOT_RECORDED,
} from './patientProfileFormat.ts'
import type { PatientProfileDto } from './types.ts'

/** Initials of a displayed name, or null when no name is recorded. */
function initials(name: string): string | null {
  if (name === NOT_RECORDED) return null
  const parts = name.split(/\s+/).filter(Boolean)
  const letters = (parts.length > 1 ? [parts[0], parts[parts.length - 1]] : parts).map((part) => part[0])
  return letters.join('').toUpperCase() || null
}

interface PatientProfileViewProps {
  patient: PatientProfileDto
  /** Label under the name; "Registered patient" for clinicians, "Your medical record" for the patient. */
  label?: string
  /** Optional actions beside the name, e.g. "Edit Patient" for doctors and staff. */
  actions?: ReactNode
}

/** Read-only presentation of one Patient's profile as returned by MediCore. Never shows the FHIR ID or version. */
function PatientProfileView({ patient, label = 'Registered patient', actions }: PatientProfileViewProps) {
  const name = displayName(patient)
  const avatar = initials(name)

  return (
    <div className="card">
      <div className="patient-summary">
        <span className="patient-summary__avatar" aria-hidden="true">{avatar ?? <UserRound size={28} />}</span>
        <div className="patient-summary__identity">
          <h2>{name}</h2>
          <div className="patient-summary__meta">
            <Badge tone="primary">{label}</Badge>
          </div>
        </div>
        {actions && <div className="patient-summary__actions">{actions}</div>}
      </div>
      <dl className="details">
        <dt>Name</dt>
        <dd>{name}</dd>
        <dt>Personnummer</dt>
        <dd>{displayPersonnummer(patient)}</dd>
        <dt>Date of birth</dt>
        <dd>{displayBirthDate(patient)}</dd>
        <dt>Gender</dt>
        <dd>{displayGender(patient)}</dd>
        <dt>Email</dt>
        <dd>{displayEmail(patient)}</dd>
        <dt>Phone</dt>
        <dd>{displayPhone(patient)}</dd>
      </dl>
    </div>
  )
}

export default PatientProfileView
