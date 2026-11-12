import { ApiError } from '../../services/apiClient.ts'
import type { NewObservationRequest, ObservationConcept, ObservationQuantity, ObservationValue } from './types.ts'

export interface ObservationFields {
  type: string; date: string; resultType: 'quantity' | 'coded' | 'absent'; value: string
  unit: string; system: string; code: string; resultText: string
}
export const emptyObservationFields: ObservationFields = {
  type: '', date: '', resultType: 'quantity', value: '', unit: '', system: '', code: '', resultText: '',
}

// Canonical decimal text, including exponent notation emitted by JSON.stringify(Number).
function canonical(value: string): string {
  const [base, exponent = '0'] = value.toLowerCase().split('e')
  const negative = base.startsWith('-')
  const [whole, fraction = ''] = base.replace(/^[+-]/, '').split('.')
  const digits = whole + fraction
  const point = whole.length + Number(exponent)
  const expanded = point <= 0 ? `0.${'0'.repeat(-point)}${digits}` : point >= digits.length
    ? digits + '0'.repeat(point - digits.length) : `${digits.slice(0, point)}.${digits.slice(point)}`
  const [integer, decimal = ''] = expanded.split('.')
  const normalized = `${integer.replace(/^0+(?=\d)/, '')}${decimal.replace(/0+$/, '') ? `.${decimal.replace(/0+$/, '')}` : ''}`
  return negative && normalized !== '0' ? `-${normalized}` : normalized
}

/** Reject values that would change when serialized as a JS number; never silently round. */
export function safeObservationNumber(text: string): number | null {
  const value = text.trim()
  if (!/^[+-]?\d+(\.\d+)?$/.test(value) || value.length > 100) return null
  const number = Number(value)
  if (!Number.isFinite(number) || Math.abs(number) > Number.MAX_SAFE_INTEGER) return null
  return canonical(value) === canonical(JSON.stringify(number)) ? number : null
}

export function observationRequest(fields: ObservationFields): { request: NewObservationRequest } | { error: string } {
  if (!fields.type.trim()) return { error: 'Enter an observation type.' }
  const date = fields.date
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || date.startsWith('0000') || Number.isNaN(Date.parse(date))
    || new Date(date).toISOString().slice(0, 10) !== date) return { error: 'Enter a valid effective date.' }
  const request: NewObservationRequest = { status: 'FINAL', code: { text: fields.type.trim() }, effectiveDateTime: date }
  if (fields.resultType === 'quantity') {
    const value = safeObservationNumber(fields.value)
    if (value === null) return { error: 'Enter a decimal value that can be saved without rounding. Use fewer digits if needed.' }
    const system = fields.system.trim(), code = fields.code.trim()
    if (Boolean(system) !== Boolean(code)) return { error: 'Provide both the unit code system and unit code, or leave both empty.' }
    if (system) {
      try { new URL(system) } catch { return { error: 'Enter an absolute unit code system URI.' } }
    }
    request.valueQuantity = { value, ...(fields.unit.trim() ? { unit: fields.unit.trim() } : {}), ...(system ? { system, code } : {}) }
  } else if (fields.resultType === 'coded' || fields.resultType === 'absent') {
    if (!fields.resultText.trim()) return { error: fields.resultType === 'coded' ? 'Enter a result description.' : 'Enter an absence reason.' }
    if (fields.resultType === 'coded') request.valueCodeableConcept = { text: fields.resultText.trim() }
    else request.dataAbsentReason = { text: fields.resultText.trim() }
  } else return { error: 'Choose a result type.' }
  return { request }
}

export function observationError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 400 || error.status === 422) return 'The observation request was rejected. Check the entered values.'
    if (error.status === 401) return 'Your session has expired. Please sign in again.'
    if (error.status === 403) return 'You do not have permission to access these observations.'
    if (error.status === 404) return 'The patient record is not available or has not been linked.'
  }
  return 'The request could not be confirmed. If recording an observation, check the list before trying again.'
}

export function observationConcept(concept: ObservationConcept | null): string {
  if (!concept) return 'Not recorded'
  return concept.text || concept.coding.map((item) => item.display || [item.system, item.code].filter(Boolean).join(' | ')).filter(Boolean).join('; ') || 'Not recorded'
}
function quantity(value: ObservationQuantity | null): string {
  if (!value) return 'Not recorded'
  return [value.comparator, value.value ?? 'Not recorded', value.unit || value.code].filter((item) => item !== null && item !== '').join(' ')
}
export function observationValue(value: ObservationValue | null): string {
  if (!value) return 'Not recorded'
  switch (value.type) {
    case 'QUANTITY': return quantity(value.data)
    case 'CODEABLE_CONCEPT': return observationConcept(value.data)
    case 'STRING': case 'TIME': case 'DATE_TIME': return value.data.value ?? 'Not recorded'
    case 'BOOLEAN': case 'INTEGER': return value.data.value === null ? 'Not recorded' : String(value.data.value)
    case 'RANGE': return `${quantity(value.data.low)} – ${quantity(value.data.high)}`
    case 'RATIO': return `${quantity(value.data.numerator)} / ${quantity(value.data.denominator)}`
    case 'PERIOD': return `${value.data.start ?? 'Not recorded'} – ${value.data.end ?? 'Ongoing'}`
    default: return 'Unsupported result type'
  }
}

export async function submitObservation(fields: ObservationFields, guard: { current: boolean }, signal: AbortSignal,
  deps: { send: (request: NewObservationRequest) => Promise<unknown>; confirmed: () => void }): Promise<string | null> {
  if (guard.current || signal.aborted) return null
  const checked = observationRequest(fields)
  if ('error' in checked) return checked.error
  guard.current = true
  try {
    await deps.send(checked.request)
    if (!signal.aborted) deps.confirmed()
    return null
  } catch (error: unknown) { return signal.aborted ? null : observationError(error) }
  finally { guard.current = false }
}
