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

export interface MessageConversation {
  recipientId: number
  displayName: string
  role: 'DOCTOR' | 'STAFF'
  messageCount: number
  lastMessageAt: string
}

export interface MessageHistory {
  id: number
  senderId: number
  recipientId: number
  content: string
  createdAt: string
}

export interface MessageInboxConversation {
  patientId: number
  displayName: string
  messageCount: number
  lastMessageAt: string
}
