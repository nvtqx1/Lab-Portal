package com.web.labportalbackend.face.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FaceCheckinRequest(
        @NotNull
        @Positive
        @Schema(description = "Laboratory time slot selected by the authenticated manager") Long slotId,
        @NotBlank
        @Size(max = 14_000_000)
        @Schema(description = "Base64-encoded JPEG or PNG live face image") String imageBase64,
        @NotBlank
        @Pattern(regexp = "image/(jpeg|png)")
        @Schema(description = "Image media type", allowableValues = {"image/jpeg", "image/png"}) String contentType,
        @NotNull
        @Size(min = 4, max = 8)
        @Schema(description = "Consecutive live-camera frames captured automatically while the face remains stable")
        List<@Valid FaceChallengeFrameRequest> challengeFrames,
        @NotBlank
        @Size(max = 4096)
        @Schema(description = "Signed passive-observation token issued for this camera session")
        String challengeToken
) {
}
