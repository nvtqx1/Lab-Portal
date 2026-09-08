package com.web.labportalbackend.face.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record FaceCheckinResponse(
        @Schema(description = "Booking identified within the selected slot, or null when no student matched") Long bookingId,
        @Schema(description = "Recognized student identifier, or null when no registered student matched") Long userId,
        @Schema(description = "Recognized student display name, or null when no registered student matched") String studentName,
        @Schema(description = "Whether the recognized student's check-in was successfully recorded") boolean checkedIn,
        @Schema(description = "MATCH or the machine-readable failure result") String result,
        @Schema(description = "Face-match confidence from zero to one") Double confidenceScore,
        @Schema(description = "Liveness score from zero to one") Double livenessScore,
        @Schema(description = "Machine-readable failure reason") String failureReason,
        @Schema(description = "Committed check-in time, or null when check-in failed") Instant checkedInAt
) {
}
