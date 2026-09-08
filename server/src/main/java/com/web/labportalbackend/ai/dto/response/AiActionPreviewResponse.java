package com.web.labportalbackend.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record AiActionPreviewResponse(
        @Schema(description = "Server-owned identifier used to confirm or cancel this immutable preview")
        Long suggestionId,
        @Schema(description = "Domain action represented by this preview")
        String actionType,
        @Schema(description = "Current confirmation state")
        String status,
        @Schema(description = "Managed laboratory that will receive the new time slot")
        Long labId,
        @Schema(description = "Current server-owned name of the managed laboratory that will receive the new time slot")
        String labName,
        @Schema(description = "UTC start instant of the proposed time slot")
        Instant startTime,
        @Schema(description = "UTC end instant of the proposed time slot")
        Instant endTime,
        @Schema(description = "Maximum number of bookings for the proposed time slot")
        Integer capacity) {
}
