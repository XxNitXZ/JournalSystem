import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { MessageRecipient, MessageSentResponse, SendMessageRequest } from './types.ts'

export function getMessageRecipients(options?: ApiRequestOptions): Promise<MessageRecipient[]> {
  return apiGet<MessageRecipient[]>('/api/messages/recipients', options)
}

export function sendMessage(request: SendMessageRequest): Promise<MessageSentResponse> {
  return apiPost<MessageSentResponse>('/api/messages', { recipientId: request.recipientId, content: request.content })
}
