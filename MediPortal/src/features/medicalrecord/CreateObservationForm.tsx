import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { createObservation } from './observationService.ts'
import { emptyObservationFields, submitObservation, type ObservationFields } from './observationForm.ts'

export default function CreateObservationForm({ patientId, onCreated, onCancel }: {
  patientId: string; onCreated: () => void; onCancel: () => void
}) {
  const [fields, setFields] = useState<ObservationFields>({ ...emptyObservationFields })
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const guard = useRef(false)
  const lifecycle = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    lifecycle.current = controller
    return () => controller.abort()
  }, [patientId])
  function change(field: keyof ObservationFields, value: string) {
    setFields((current) => ({ ...current, [field]: value }))
    setError('')
  }
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const signal = lifecycle.current?.signal
    if (!signal || signal.aborted || guard.current) return
    setError(''); setPending(true)
    const failure = await submitObservation(fields, guard, signal, {
      send: (request) => createObservation(patientId, request, { signal }), confirmed: onCreated,
    })
    if (!signal.aborted) { setPending(false); if (failure) setError(failure) }
  }
  return <form className="form encounter-form" onSubmit={submit} aria-busy={pending} noValidate>
    <h3>Add Observation</h3>
    <fieldset disabled={pending}>
      <legend className="visually-hidden">Observation details</legend>
      <FormField htmlFor="observation-type" label="Observation type">
        <input id="observation-type" required value={fields.type} onChange={(event) => change('type', event.target.value)} />
      </FormField>
      <FormField htmlFor="observation-date" label="Effective date">
        <input id="observation-date" type="date" required value={fields.date} onChange={(event) => change('date', event.target.value)} />
      </FormField>
      <FormField htmlFor="observation-result-type" label="Result type">
        <select id="observation-result-type" value={fields.resultType} onChange={(event) => change('resultType', event.target.value)}>
          <option value="quantity">Quantity</option><option value="coded">Codeable concept</option><option value="absent">Data absent reason</option>
        </select>
      </FormField>
      {fields.resultType === 'quantity' ? <>
        <FormField htmlFor="observation-value" label="Numeric value" hintId="observation-value-help" hint="Use a decimal point. Values that would be rounded cannot be saved.">
          <input id="observation-value" type="text" inputMode="decimal" required aria-describedby="observation-value-help"
            value={fields.value} onChange={(event) => change('value', event.target.value)} />
        </FormField>
        <FormField htmlFor="observation-unit" label="Unit (optional)">
          <input id="observation-unit" value={fields.unit} onChange={(event) => change('unit', event.target.value)} />
        </FormField>
        <FormField htmlFor="observation-system" label="Unit code system (optional)">
          <input id="observation-system" value={fields.system} onChange={(event) => change('system', event.target.value)} />
        </FormField>
        <FormField htmlFor="observation-unit-code" label="Unit code (required with code system)">
          <input id="observation-unit-code" value={fields.code} onChange={(event) => change('code', event.target.value)} />
        </FormField>
      </> : <FormField htmlFor="observation-result-text" label={fields.resultType === 'coded' ? 'Result description' : 'Absence reason'}>
        <input id="observation-result-text" required value={fields.resultText} onChange={(event) => change('resultText', event.target.value)} />
      </FormField>}
      <p className="muted">Recorded status: Final</p>
      <div className="page-actions">
        <button type="submit" className="btn btn--primary">{pending ? 'Recording…' : 'Record observation'}</button>
        <button type="button" className="btn btn--secondary" onClick={onCancel}>Cancel</button>
      </div>
    </fieldset>
    <Alert tone="error" role="alert">{error}</Alert>
    <Alert tone="info" role="status">{pending ? 'Recording observation…' : ''}</Alert>
  </form>
}
