import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router'
import { displayBirthDate, displayName, displayPersonnummer } from './patientProfileFormat.ts'
import type { PatientDto } from './types.ts'

/**
 * One row per matching patient, including patients with identical names: name and personnummer side by side
 * and an explicit "Open profile" action. Nothing is selected automatically, and internal IDs are not shown.
 */
function PatientSearchResults({ patients }: { patients: PatientDto[] }) {
  return (
    <ul className="search-results" aria-label="Matching patients">
      {patients.map((patient) => {
        const name = displayName(patient)
        return (
          <li key={patient.id} className="search-results__item">
            <span className="search-results__name">{name}</span>
            <span className="search-results__personnummer">{displayPersonnummer(patient)}</span>
            <span className="search-results__birth">Born {displayBirthDate(patient)}</span>
            <Link to={`/patients/${encodeURIComponent(patient.id)}`} className="btn btn--secondary btn--sm"
              aria-label={`Open profile for ${name}, ${displayPersonnummer(patient)}`}>
              Open profile<ArrowRight size={16} aria-hidden="true" />
            </Link>
          </li>
        )
      })}
    </ul>
  )
}

export default PatientSearchResults
