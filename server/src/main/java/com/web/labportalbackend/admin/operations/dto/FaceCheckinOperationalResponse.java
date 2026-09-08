package com.web.labportalbackend.admin.operations.dto;

import com.web.labportalbackend.face.enums.FaceCheckinMethod;
import com.web.labportalbackend.face.enums.FaceCheckinResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record FaceCheckinOperationalResponse(
        @Schema(description = "Unique face check-in log identifier") Long id,
        @Schema(description = "Booking associated with the check-in") Long bookingId,
        @Schema(description = "User whose booking was checked in") Long userId,
        @Schema(description = "Display name of the user whose booking was checked in") String studentName,
        @Schema(description = "Laboratory where the check-in occurred") Long labId,
        @Schema(description = "Mechanism used for check-in") FaceCheckinMethod method,
        @Schema(description = "Recorded check-in result") FaceCheckinResult result,
        @Schema(description = "Bounded failure reason code when a failure occurred") String failureReason,
        @Schema(description = "Time when the check-in attempt was recorded") Instant createdAt
) {
}
