import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { queryKeys } from '../../../shared/api';
import {
  chatWithAssistant,
  chatWithUnifiedAssistant,
  getAssistantConversation,
  getAssistantConversations,
  resolveAssistantAction,
} from '../api';
import type { AssistantChatRequest, AssistantKey, UnifiedChatRequest } from '../types';

export function useAssistantChat() {
  return useMutation({
    mutationFn: ({ assistantKey, request }: { assistantKey: AssistantKey; request: AssistantChatRequest }) =>
      chatWithAssistant(assistantKey, request),
  });
}

export function useUnifiedAssistantChat() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UnifiedChatRequest) => chatWithUnifiedAssistant(request),
    onSuccess: (response) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.assistant.conversations });
      void queryClient.invalidateQueries({ queryKey: queryKeys.assistant.conversation(response.conversationId) });
    },
  });
}

export function useAssistantConversations() {
  return useQuery({
    queryKey: queryKeys.assistant.conversations,
    queryFn: getAssistantConversations,
  });
}

export function useAssistantConversation(conversationId: number | null) {
  return useQuery({
    queryKey: queryKeys.assistant.conversation(conversationId),
    queryFn: () => getAssistantConversation(conversationId!),
    enabled: conversationId !== null,
  });
}

export function useResolveAssistantAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ suggestionId, decision }: {
      suggestionId: number;
      decision: 'confirm' | 'cancel';
    }) => resolveAssistantAction(suggestionId, decision),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.assistant.conversations });
      void queryClient.invalidateQueries({ queryKey: ['assistantConversation'] });
    },
  });
}
