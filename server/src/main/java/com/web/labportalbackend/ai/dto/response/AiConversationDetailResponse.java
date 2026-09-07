package com.web.labportalbackend.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record AiConversationDetailResponse(
        @Schema(description = "Conversation identifier") Long id,
        @Schema(description = "Conversation title") String title,
        @Schema(description = "Saved messages in chronological order for this page") List<AiConversationMessageResponse> messages,
        @Schema(description = "Whether older messages are available") boolean hasMore,
        @Schema(description = "Oldest returned message ID; pass as beforeId to load older messages, null when finished") Long nextBeforeId) {

    public AiConversationDetailResponse(Long id, String title, List<AiConversationMessageResponse> messages) {
        this(id, title, messages, false, null);
    }

    public AiConversationDetailResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
