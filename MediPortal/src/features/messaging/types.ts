export interface MessageRecipient {
  id: number
  displayName: string
  role: 'DOCTOR' | 'STAFF'
}

export interface SendMessageRequest {
  recipientId: number
  content: string
}

export interface MessageSentResponse {
  id: number
  createdAt: string
}
