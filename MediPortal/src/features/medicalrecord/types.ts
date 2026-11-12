/** One visit (FHIR Encounter) as returned by MediCore: readable values only, no resource references. */
export interface EncounterSummary {
  /** Logical ID, used as a list key only (never shown). */
  id: string
  /** ISO 8601 start as stored, possibly a date only; null if unknown. */
  start: string | null
  end: string | null
  /** FHIR R4 Encounter.status code, e.g. "finished". */
  status: string
  /** Visit type, e.g. "Consultation"; null if not recorded. */
  type: string | null
  practitioners: string[]
  organization: string | null
  location: string | null
}

export const visitTypes = ['CONSULTATION', 'FOLLOW_UP', 'CHECK_UP', 'TELEPHONE'] as const
export type VisitType = (typeof visitTypes)[number]

export const visitTypeLabels: Record<VisitType, string> = {
  CONSULTATION: 'Consultation',
  FOLLOW_UP: 'Follow-up',
  CHECK_UP: 'Check-up',
  TELEPHONE: 'Telephone',
}

/** Statuses offered for a newly documented visit (FHIR R4 codes finished, in-progress, planned). */
export const newVisitStatuses = ['FINISHED', 'IN_PROGRESS', 'PLANNED'] as const
export type NewVisitStatus = (typeof newVisitStatuses)[number]

export const newVisitStatusLabels: Record<NewVisitStatus, string> = {
  FINISHED: 'Finished',
  IN_PROGRESS: 'In progress',
  PLANNED: 'Planned',
}

/**
 * Body of POST /api/patients/{patientId}/encounters. The patient is the one in the URL; no patient field exists.
 * startTime is ISO 8601 with the browser's offset.
 */
export interface NewEncounterRequest {
  organizationId?: string
  startTime: string
  practitionerId: string
  type: VisitType
  status: NewVisitStatus
}

/** A practitioner that can be chosen; the ID is only the selected value, never shown. */
export interface PractitionerOption {
  id: string
  name: string
  /** The practitioner linked to the signed-in account (preselected). */
  own: boolean
}

/** The visit a clinical note belongs to: readable start and type; the ID only selects the visit (never shown). */
export interface NoteEncounter {
  id: string
  start: string | null
  type: string | null
}

/** One clinical note (FHIR DocumentReference) as returned by MediCore: decoded plain text, readable values only. */
export interface ClinicalNote {
  /** Logical ID, used as a list key only (never shown). */
  id: string
  patientId: string
  /** The visit, or null if it is not available. */
  encounter: NoteEncounter | null
  /** The author's name, or null if not recorded. */
  author: string | null
  /** ISO 8601 creation time as stored, or null. */
  createdAt: string | null
  /** FHIR R4 DocumentReference.status code, e.g. "current". */
  status: string | null
  type: string | null
  /** The note text (plain text, never HTML); null when contentAvailable is false. */
  content: string | null
  contentAvailable: boolean
}

/**
 * Body of POST /api/patients/{patientId}/notes. The patient is the one in the URL and the author is the signed-in
 * clinician; neither is a field.
 */
export interface NewClinicalNoteRequest {
  encounterId: string
  content: string
}

/** A diagnosis code; systemName is "SNOMED CT" or "ICD-10" when known, otherwise null. */
export interface DiagnosisCode {
  system: string | null
  systemName: string | null
  code: string
  display: string | null
}

/**
 * One diagnosis (FHIR Condition) as returned by MediCore: readable values only. Conditions recorded outside MediCore
 * (for example for imported patients) may lack any optional value.
 */
export interface Diagnosis {
  /** Logical ID, used as a list key only (never shown). */
  id: string
  patientId: string
  diagnosis: string | null
  code: DiagnosisCode | null
  /** FHIR R4 clinical status code, e.g. "active". */
  clinicalStatus: string | null
  /** FHIR R4 verification status code, e.g. "confirmed". */
  verificationStatus: string | null
  /** FHIR R4 category code, e.g. "encounter-diagnosis". */
  category: string | null
  recordedDate: string | null
  onset: string | null
  encounter: NoteEncounter | null
  recorder: string | null
}

export const diagnosisCodeSystems = ['SNOMED_CT', 'ICD_10'] as const
export type DiagnosisCodeSystem = (typeof diagnosisCodeSystems)[number]
export const diagnosisCodeSystemLabels: Record<DiagnosisCodeSystem, string> = { SNOMED_CT: 'SNOMED CT', ICD_10: 'ICD-10' }

/** FHIR R4 clinical statuses (condition-clinical). */
export const clinicalStatuses = ['ACTIVE', 'RECURRENCE', 'RELAPSE', 'INACTIVE', 'REMISSION', 'RESOLVED'] as const
export type ClinicalStatus = (typeof clinicalStatuses)[number]

/** Verification statuses offered for a new diagnosis (FHIR R4 condition-ver-status). */
export const newVerificationStatuses = ['CONFIRMED', 'PROVISIONAL', 'DIFFERENTIAL', 'UNCONFIRMED'] as const
export type NewVerificationStatus = (typeof newVerificationStatuses)[number]

/**
 * Body of POST /api/patients/{patientId}/conditions. The patient is the one in the URL and the recorder is the
 * signed-in clinician; neither is a field.
 */
export interface NewDiagnosisRequest {
  diagnosis: string
  code?: string
  codeSystem?: DiagnosisCodeSystem
  clinicalStatus: ClinicalStatus
  verificationStatus: NewVerificationStatus
  onsetDate?: string
  encounterId?: string
}

export interface ObservationConcept {
  text: string | null
  coding: { system: string | null; version?: string | null; code: string | null; display: string | null }[]
}
export interface ObservationQuantity { value: number | null; comparator: string | null; unit: string | null; system: string | null; code: string | null }
export type ObservationValue =
  | { type: 'QUANTITY'; data: ObservationQuantity }
  | { type: 'CODEABLE_CONCEPT'; data: ObservationConcept }
  | { type: 'STRING' | 'TIME' | 'DATE_TIME'; data: { value: string | null } }
  | { type: 'BOOLEAN'; data: { value: boolean | null } }
  | { type: 'INTEGER'; data: { value: number | null } }
  | { type: 'RANGE'; data: { low: ObservationQuantity | null; high: ObservationQuantity | null } }
  | { type: 'RATIO'; data: { numerator: ObservationQuantity | null; denominator: ObservationQuantity | null } }
  | { type: 'PERIOD'; data: { start: string | null; end: string | null } }
export interface ObservationComponent { code: ObservationConcept; value: ObservationValue | null; dataAbsentReason: ObservationConcept | null }
export interface PatientObservation extends ObservationComponent {
  id: string
  status: string
  subject: { reference: string | null } | null
  components: ObservationComponent[]
  effective: ObservationValue | null
  issued: string | null
}
export interface NewObservationRequest {
  status: 'FINAL'
  code: { text: string }
  effectiveDateTime: string
  valueQuantity?: { value: number; unit?: string; system?: string; code?: string }
  valueCodeableConcept?: { text: string }
  dataAbsentReason?: { text: string }
}

/** Explicit completeness metadata from fully traversed Observation GET searches. */
export interface ObservationSearchResult {
  observations: PatientObservation[]
  complete: boolean
  excludedCount: number
}

export interface EncounterListResult {
  encounters: EncounterSummary[]
  complete: boolean
  excludedCount: number
}

/** An active named Organization available as an optional healthcare provider. */
export interface OrganizationOption {
  id: string
  name: string
}
