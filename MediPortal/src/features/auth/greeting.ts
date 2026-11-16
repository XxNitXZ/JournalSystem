import { accountFirstName } from './accountName.ts'
import type { UserDto } from './types.ts'

/** "Good morning", "Good afternoon" or "Good evening" for the viewer's local time. */
export function timeOfDayGreeting(now = new Date()): string {
  const hour = now.getHours()
  if (hour >= 5 && hour < 12) return 'Good morning'
  if (hour >= 12 && hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** "Good morning, Anna" with the stored first name; without a stored name just "Good morning". */
export function personalGreeting(user: UserDto | null, now = new Date()): string {
  const firstName = user ? accountFirstName(user) : null
  return firstName ? `${timeOfDayGreeting(now)}, ${firstName}` : timeOfDayGreeting(now)
}
