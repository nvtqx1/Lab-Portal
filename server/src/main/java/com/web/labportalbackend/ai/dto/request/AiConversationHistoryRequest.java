package com.web.labportalbackend.ai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiConversationHistoryRequest {
    @Schema(description = "Message ID from nextBeforeId; omit to load the newest messages", nullable = true)
    @Positive
    private Long beforeId;

    @Schema(description = "Maximum messages returned, from 1 to 30", defaultValue = "30")
    @Min(1)
    @Max(30)
    private int size = 30;
}
