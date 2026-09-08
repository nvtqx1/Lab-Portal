package com.web.labportalbackend.face.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.booking.entity.Booking;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.service.CheckinWindowPolicy;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.face.client.FaceMatchRequest;
import com.web.labportalbackend.face.client.FaceMatchResponse;
import com.web.labportalbackend.face.client.FaceGuidanceImageRequest;
import com.web.labportalbackend.face.client.FaceGuidanceResult;
import com.web.labportalbackend.face.client.FaceProcessingClient;
import com.web.labportalbackend.face.dto.request.FaceCheckinRequest;
import com.web.labportalbackend.face.dto.request.FaceGuidanceRequest;
import com.web.labportalbackend.face.dto.response.FaceChallengeResponse;
import com.web.labportalbackend.face.dto.response.FaceCheckinResponse;
import com.web.labportalbackend.face.dto.response.FaceCheckinCandidateResponse;
import com.web.labportalbackend.face.dto.response.FaceGuidanceResponse;
import com.web.labportalbackend.face.entity.FaceConsentLogEntity;
import com.web.labportalbackend.face.entity.FaceProfileEntity;
import com.web.labportalbackend.face.entity.FaceSecurityConfigEntity;
import com.web.labportalbackend.face.enums.FaceProfileStatus;
import com.web.labportalbackend.face.repository.FaceConsentLogRepository;
import com.web.labportalbackend.face.repository.FaceProfileRepository;
import com.web.labportalbackend.face.repository.FaceSecurityConfigRepository;
import com.web.labportalbackend.face.security.FaceEmbeddingCipher;
import com.web.labportalbackend.face.service.FaceCheckinService;
import com.web.labportalbackend.face.service.FaceCheckinWriter;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FaceCheckinServiceImpl implements FaceCheckinService {

    private static final Set<String> FAILURE_RESULTS = Set.of(
            "NO_FACE", "MULTIPLE_FACES", "LOW_QUALITY", "NO_MATCH", "SPOOF_DETECTED",
            "CHALLENGE_MISSING", "CHALLENGE_INVALID", "CHALLENGE_FACE_INVALID",
            "CHALLENGE_START_NOT_FRONTAL", "CHALLENGE_TURN_NOT_DETECTED",
            "OBSERVATION_TOO_SHORT", "OBSERVATION_FACE_INVALID", "OBSERVATION_NOT_FRONTAL",
            "OBSERVATION_NOT_LIVE", "SERVICE_ERROR");

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final FaceConsentLogRepository consentRepository;
    private final FaceProfileRepository profileRepository;
    private final FaceSecurityConfigRepository securityConfigRepository;
    private final CheckinWindowPolicy checkinWindowPolicy;
    private final FaceProcessingClient processingClient;
    private final FaceEmbeddingCipher embeddingCipher;
    private final FaceCheckinWriter writer;
    private final ObjectMapper objectMapper;

    @Override
    public List<FaceCheckinCandidateResponse> candidates() {
        User manager = currentManager();
        Instant now = Instant.now();
        CheckinWindowPolicy.CandidateWindow window = checkinWindowPolicy.candidateWindow(now);
        return bookingRepository.findFaceCheckinCandidatesForManager(
                manager.getId(), window.earliestStart(), window.latestStart());
    }

    @Override
    public FaceGuidanceResponse guidance(FaceGuidanceRequest request) {
        currentManager();
        validateImage(request.imageBase64());
        FaceGuidanceResult result = processingClient.guidance(new FaceGuidanceImageRequest(
                request.imageBase64(), request.contentType(), false));
        if (result == null || result.detectedFaces() < 0) {
            throw new IllegalStateException("Face service returned an invalid guidance result");
        }
        return new FaceGuidanceResponse(
                result.detectedFaces(), result.singleFace(), result.faceInGuide(),
                result.facingForward(), result.landmarksVisible(), result.lightingGood(),
                result.sharpnessGood(), result.centerX(), result.centerY(),
                result.faceWidthRatio(), result.faceHeightRatio(), result.failureReason());
    }

    @Override
    public FaceChallengeResponse startPassiveSession() {
        currentManager();
        var challenge = processingClient.startPassiveSession();
        if (challenge == null || challenge.challengeToken() == null || challenge.challengeToken().isBlank()
                || !"OBSERVE".equals(challenge.action())) {
            throw new IllegalStateException("Face service returned an invalid passive observation session");
        }
        return new FaceChallengeResponse(
                challenge.challengeToken(), challenge.action(), challenge.expiresAt());
    }

    @Override
    public FaceCheckinResponse checkIn(FaceCheckinRequest request) {
        User manager = currentManager();
        Instant now = Instant.now();
        CheckinWindowPolicy.CandidateWindow window = checkinWindowPolicy.candidateWindow(now);
        List<Booking> bookings = bookingRepository.findManagerFaceCheckinBookingsBySlot(
                manager.getId(), request.slotId(), window.earliestStart(), window.latestStart());
        if (bookings.isEmpty()) {
            throw new AccessDeniedException("Ca đã chọn không thuộc PTN đang quản lý hoặc không có lượt đặt hợp lệ");
        }
        bookings.forEach(booking -> validateBookingAndWindow(booking, now));
        Map<Long, FaceProfileEntity> profilesByUserId = profileRepository
                .findAllByUserIdInAndProfileStatusAndActiveTrueAndDeletedFalse(
                        bookings.stream().map(booking -> booking.getUser().getId()).distinct().toList(),
                        FaceProfileStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(profile -> profile.getUser().getId(), Function.identity()));
        List<RecognizableBooking> recognizableBookings = bookings.stream()
                .filter(booking -> profilesByUserId.containsKey(booking.getUser().getId()))
                .map(booking -> new RecognizableBooking(booking, profilesByUserId.get(booking.getUser().getId())))
                .toList();
        if (recognizableBookings.isEmpty()) {
            throw new IllegalStateException("Không có người đăng ký nào trong ca đã có hồ sơ khuôn mặt hoạt động");
        }
        FaceSecurityConfigEntity config = securityConfig();
        if (!Boolean.TRUE.equals(config.getFaceEnabled())) {
            throw new IllegalStateException("Face check-in is disabled");
        }
        validateImage(request.imageBase64());

        List<com.web.labportalbackend.face.client.FaceChallengeFrame> frames = request.challengeFrames().stream()
                .map(frame -> new com.web.labportalbackend.face.client.FaceChallengeFrame(
                        frame.imageBase64(), frame.contentType()))
                .toList();
        List<MatchCandidate> matches = recognizableBookings.stream()
                .map(candidate -> new MatchCandidate(candidate.booking(), match(request, frames, candidate.profile(), config)))
                .toList();
        MatchCandidate best = matches.stream()
                .filter(candidate -> isSuccessfulMatch(candidate.match(), config))
                .max(Comparator.comparingDouble(candidate -> candidate.match().confidenceScore()))
                .orElse(null);
        if (best == null) {
            FaceMatchResponse failure = matches.stream()
                    .map(MatchCandidate::match)
                    .filter(match -> match != null && match.confidenceScore() != null)
                    .max(Comparator.comparingDouble(FaceMatchResponse::confidenceScore))
                    .orElse(matches.getFirst().match());
            boolean confidencePassed = failure != null && failure.confidenceScore() != null
                    && failure.confidenceScore() >= config.getConfidenceThreshold().doubleValue();
            boolean livenessPassed = failure != null && (!Boolean.TRUE.equals(config.getLivenessRequired())
                    || failure.passiveLivenessPassed());
            String failureReason = failure == null ? "SERVICE_ERROR"
                    : failureReason(failure, confidencePassed, livenessPassed);
            return new FaceCheckinResponse(null, null, null, false, failureReason,
                    failure == null ? null : failure.confidenceScore(),
                    failure == null ? null : failure.livenessScore(), failureReason, null);
        }
        return decideAndPersist(best.booking(), manager.getId(), best.match(), config);
    }

    private boolean isSuccessfulMatch(FaceMatchResponse match, FaceSecurityConfigEntity config) {
        return match != null
                && "MATCH".equals(match.result())
                && validScore(match.confidenceScore())
                && validScore(match.livenessScore())
                && match.confidenceScore() >= config.getConfidenceThreshold().doubleValue()
                && (!Boolean.TRUE.equals(config.getLivenessRequired()) || match.passiveLivenessPassed());
    }

    private FaceMatchResponse match(FaceCheckinRequest request,
                                    List<com.web.labportalbackend.face.client.FaceChallengeFrame> frames,
                                    FaceProfileEntity profile,
                                    FaceSecurityConfigEntity config) {
        return processingClient.match(new FaceMatchRequest(
                request.imageBase64(), request.contentType(), decryptEmbedding(profile.getEncryptedEmbedding()),
                config.getConfidenceThreshold().doubleValue(), config.getLivenessThreshold().doubleValue(),
                Boolean.TRUE.equals(config.getLivenessRequired()), frames, request.challengeToken()));
    }

    private FaceCheckinResponse decideAndPersist(
            Booking booking,
            Long managerId,
            FaceMatchResponse match,
            FaceSecurityConfigEntity config
    ) {
        if (match == null || match.result() == null || !validScore(match.confidenceScore())
                || !validScore(match.livenessScore())) {
            throw new IllegalStateException("Face service returned an invalid match result");
        }
        boolean confidencePassed = match.confidenceScore() != null
                && match.confidenceScore() >= config.getConfidenceThreshold().doubleValue();
        boolean livenessPassed = !Boolean.TRUE.equals(config.getLivenessRequired())
                || match.passiveLivenessPassed();
        if ("MATCH".equals(match.result()) && confidencePassed && livenessPassed) {
            User student = booking.getUser();
            Instant checkedInAt = writer.complete(student.getId(), managerId, booking.getId(),
                    match.confidenceScore(), match.livenessScore());
            return new FaceCheckinResponse(booking.getId(), student.getId(), student.getFullName(), true, "MATCH", match.confidenceScore(),
                    match.livenessScore(), null, checkedInAt);
        }

        String failureReason = failureReason(match, confidencePassed, livenessPassed);
        return new FaceCheckinResponse(null, null, null, false, failureReason, match.confidenceScore(),
                match.livenessScore(), failureReason, null);
    }

    private String failureReason(FaceMatchResponse match, boolean confidencePassed, boolean livenessPassed) {
        if (match.failureReason() != null && FAILURE_RESULTS.contains(match.failureReason())) {
            return match.failureReason();
        }
        if (FAILURE_RESULTS.contains(match.result())) {
            return match.result();
        }
        if (!livenessPassed) {
            return "SPOOF_DETECTED";
        }
        return !confidencePassed ? "NO_MATCH" : "SERVICE_ERROR";
    }

    private User currentManager() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        User actor = userRepository.findByEmailOrUsername(authentication.getName(), authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
        if (!actor.hasRole("LAB_MANAGER")) {
            throw new AccessDeniedException("Face check-in operation is limited to laboratory managers");
        }
        return actor;
    }

    private void validateBookingAndWindow(Booking booking, Instant now) {
        if (booking.getStatus() != BookingStatus.APPROVED) {
            throw new IllegalStateException("Only an approved booking can be checked in");
        }
        if (booking.getTimeSlot() == null || booking.getTimeSlot().getStatus() == TimeSlotStatus.CANCELLED) {
            throw new IllegalStateException("Booking time slot is not available");
        }
        checkinWindowPolicy.validate(booking, now);
    }

    private void requireGrantedConsent(Long userId) {
        boolean granted = consentRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map(FaceConsentLogEntity::getConsentStatus)
                .map(status -> status.allowsActiveProfile())
                .orElse(false);
        if (!granted) {
            throw new IllegalStateException("Granted face consent is required");
        }
    }

    private FaceSecurityConfigEntity securityConfig() {
        List<FaceSecurityConfigEntity> configs = securityConfigRepository.findAllByActiveTrueAndDeletedFalse();
        if (configs.size() != 1) {
            throw new IllegalStateException("Exactly one active face security configuration is required");
        }
        return configs.getFirst();
    }

    private List<List<Double>> decryptEmbedding(String encryptedEmbedding) {
        try {
            var root = objectMapper.readTree(embeddingCipher.decrypt(encryptedEmbedding));
            if (!root.isArray() || root.isEmpty()) {
                throw new IllegalStateException("Stored face embedding is invalid");
            }
            List<List<Double>> embeddings;
            if (root.get(0).isNumber()) {
                embeddings = List.of(objectMapper.convertValue(root, new TypeReference<List<Double>>() { }));
            } else {
                embeddings = objectMapper.convertValue(root, new TypeReference<List<List<Double>>>() { });
            }
            if (embeddings.isEmpty() || embeddings.size() > 3
                    || embeddings.stream().anyMatch(embedding -> embedding == null || embedding.isEmpty()
                    || embedding.size() > 4096
                    || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value)))) {
                throw new IllegalStateException("Stored face embedding is invalid");
            }
            int dimension = embeddings.getFirst().size();
            if (embeddings.stream().anyMatch(embedding -> embedding.size() != dimension)) {
                throw new IllegalStateException("Stored face embeddings have inconsistent dimensions");
            }
            return embeddings;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored face embedding is invalid", exception);
        }
    }

    private void validateImage(String imageBase64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(imageBase64);
            if (decoded.length == 0 || decoded.length > 10_000_000) {
                throw new IllegalArgumentException("Face image size is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Face image must be valid base64", exception);
        }
    }

    private boolean validScore(Double score) {
        return score == null || (Double.isFinite(score) && score >= 0 && score <= 1);
    }

    private record RecognizableBooking(Booking booking, FaceProfileEntity profile) { }

    private record MatchCandidate(Booking booking, FaceMatchResponse match) { }
}
