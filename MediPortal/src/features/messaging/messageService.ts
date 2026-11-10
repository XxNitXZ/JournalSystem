import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { MessageRecipient, MessageSentResponse, SendMessageRequest } from './types.ts'

export function getMessageRecipients(options?: ApiRequestOptions): Promise<MessageRecipient[]> {
  return apiGet<MessageRecipient[]>('/api/messages/recipients', options)
}

export function sendMessage(request: SendMessageRequest, options?: ApiRequestOptions): Promise<MessageSentResponse> {
  return apiPost<MessageSentResponse>('/api/messages', { recipientId: request.recipientId, content: request.content }, options)
}

export function getConversations(options?: ApiRequestOptions): Promise<import('./types.ts').MessageConversation[]> {
  return apiGet('/api/messages/conversations', options)
}

export function getConversationMessages(recipientId: number, options?: ApiRequestOptions): Promise<import('./types.ts').MessageHistory[]> {
  return apiGet(`/api/messages/conversations/${encodeURIComponent(recipientId)}`, options)
}

export function getProfessionalInbox(options?: ApiRequestOptions): Promise<import('./types.ts').MessageInboxConversation[]> {
  return apiGet('/api/messages/inbox', options)
}

export function getInboxMessages(patientId: number, options?: ApiRequestOptions): Promise<import('./types.ts').MessageHistory[]> {
  return apiGet(`/api/messages/inbox/${encodeURIComponent(patientId)}`, options)
}

export function sendReply(patientId: number, content: string, options?: ApiRequestOptions): Promise<MessageSentResponse> {
  return apiPost(`/api/messages/inbox/${encodeURIComponent(patientId)}/reply`, { content }, options)
}
