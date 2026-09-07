package com.web.labportalbackend.ai.dto.response;

import com.web.labportalbackend.ai.enums.AiMessageRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AiConversationMessageResponse(
        @Schema(description = "Message identifier") Long id,
        @Schema(description = "Message author role") AiMessageRole role,
        @Schema(description = "User input or assistant answer") String content,
        @Schema(description = "Structured assistant response used to restore previews and response types",
                nullable = true)
        AiUnifiedChatResponse response,
        @Schema(description = "Message creation time") Instant createdAt) {
}
