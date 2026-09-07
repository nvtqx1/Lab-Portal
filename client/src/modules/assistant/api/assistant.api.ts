import { apiClient } from '../../../shared/api';
import type { Response } from '../../../shared/types';
import type {
  AssistantChatRequest,
  AssistantChatResponse,
  AssistantKey,
  UnifiedChatRequest,
  UnifiedChatResponse,
  AssistantActionResult,
  AssistantConversationDetail,
  AssistantConversationSummary,
} from '../types';

export async function chatWithAssistant(
  assistantKey: AssistantKey,
  request: AssistantChatRequest,
): Promise<AssistantChatResponse> {
  const requestId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}`;
  const response = await apiClient.post<Response<AssistantChatResponse>>(
    `/api/ai/assistants/${assistantKey}/chat`,
    request,
    { headers: { 'X-Request-Id': requestId } },
  );
  return response.data.data;
}

export async function getAssistantConversations(): Promise<AssistantConversationSummary[]> {
  const response = await apiClient.get<Response<AssistantConversationSummary[]>>('/api/ai/conversations');
  return response.data.data;
}

export async function getAssistantConversation(
  conversationId: number,
  beforeId: number | null = null,
  size = 30,
): Promise<AssistantConversationDetail> {
  const response = await apiClient.get<Response<AssistantConversationDetail>>(
    `/api/ai/conversations/${conversationId}`,
    { params: { beforeId: beforeId ?? undefined, size } },
  );
  return response.data.data;
}

export async function chatWithUnifiedAssistant(request: UnifiedChatRequest): Promise<UnifiedChatResponse> {
  const requestId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}`;
  const response = await apiClient.post<Response<UnifiedChatResponse>>(
    '/api/ai/chat',
    request,
    { headers: { 'X-Request-Id': requestId } },
  );
  return response.data.data;
}

export async function resolveAssistantAction(
  suggestionId: number,
  decision: 'confirm' | 'cancel',
): Promise<AssistantActionResult> {
  const response = await apiClient.post<Response<AssistantActionResult>>(
    `/api/ai/actions/${suggestionId}/${decision}`,
  );
  return response.data.data;
}
