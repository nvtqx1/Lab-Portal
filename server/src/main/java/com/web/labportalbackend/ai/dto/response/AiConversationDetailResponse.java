package com.web.labportalbackend.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record AiConversationDetailResponse(
        @Schema(description = "Conversation identifier") Long id,
        @Schema(description = "Conversation title") String title,
        @Schema(description = "Saved messages in chronological order") List<AiConversationMessageResponse> messages) {

    public AiConversationDetailResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
