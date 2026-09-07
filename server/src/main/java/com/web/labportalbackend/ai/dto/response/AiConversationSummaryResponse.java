package com.web.labportalbackend.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AiConversationSummaryResponse(
        @Schema(description = "Conversation identifier") Long id,
        @Schema(description = "Title derived from the first user message") String title,
        @Schema(description = "Time of the latest saved turn") Instant updatedAt) {
}
