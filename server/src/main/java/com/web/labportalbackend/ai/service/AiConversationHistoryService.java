package com.web.labportalbackend.ai.service;

import com.web.labportalbackend.ai.dto.response.AiConversationDetailResponse;
import com.web.labportalbackend.ai.dto.response.AiConversationSummaryResponse;
import com.web.labportalbackend.ai.dto.response.AiUnifiedChatResponse;
import java.util.List;

public interface AiConversationHistoryService {

    PreparedInput prepareInput(Long conversationId, String input);

    AiUnifiedChatResponse saveTurn(Long conversationId, String userInput, AiUnifiedChatResponse response);

    AiUnifiedChatResponse saveTurn(Long conversationId, String userInput, AiUnifiedChatResponse response,
                                  AiShiftDialogueState pendingState);

    List<AiConversationSummaryResponse> listCurrentUserConversations();

    default AiConversationDetailResponse getCurrentUserConversation(Long conversationId) {
        return getCurrentUserConversation(conversationId, null, 30);
    }

    AiConversationDetailResponse getCurrentUserConversation(Long conversationId, Long beforeId, int size);

    record PreparedInput(Long conversationId, String effectiveInput, AiShiftDialogueState pendingState) {
        public PreparedInput(Long conversationId, String effectiveInput) {
            this(conversationId, effectiveInput, null);
        }
    }
}
