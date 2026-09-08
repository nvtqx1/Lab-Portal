package com.web.labportalbackend.face.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.admin.systemconfig.dto.SystemConfigResponse;
import com.web.labportalbackend.admin.systemconfig.service.SystemConfigService;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.booking.entity.Booking;
import com.web.labportalbackend.booking.entity.TimeSlot;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.service.CheckinWindowPolicy;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.face.client.FaceMatchRequest;
import com.web.labportalbackend.face.client.FaceMatchResponse;
import com.web.labportalbackend.face.client.FaceProcessingClient;
import com.web.labportalbackend.face.dto.request.FaceCheckinRequest;
import com.web.labportalbackend.face.dto.request.FaceChallengeFrameRequest;
import com.web.labportalbackend.face.entity.FaceConsentLogEntity;
import com.web.labportalbackend.face.entity.FaceProfileEntity;
import com.web.labportalbackend.face.entity.FaceSecurityConfigEntity;
import com.web.labportalbackend.face.enums.FaceConsentStatus;
import com.web.labportalbackend.face.enums.FaceProfileStatus;
import com.web.labportalbackend.face.repository.FaceConsentLogRepository;
import com.web.labportalbackend.face.repository.FaceProfileRepository;
import com.web.labportalbackend.face.repository.FaceSecurityConfigRepository;
import com.web.labportalbackend.face.security.FaceEmbeddingCipher;
import com.web.labportalbackend.face.service.impl.FaceCheckinServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class FaceCheckinServiceImplTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final FaceConsentLogRepository consentRepository = mock(FaceConsentLogRepository.class);
    private final FaceProfileRepository profileRepository = mock(FaceProfileRepository.class);
    private final FaceSecurityConfigRepository configRepository = mock(FaceSecurityConfigRepository.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final FaceProcessingClient processingClient = mock(FaceProcessingClient.class);
    private final FaceEmbeddingCipher cipher = new FaceEmbeddingCipher(KEY);
    private final FaceCheckinWriter writer = mock(FaceCheckinWriter.class);
    private final User manager = mock(User.class);
    private final User student = mock(User.class);
    private final Booking booking = mock(Booking.class);
    private FaceCheckinServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FaceCheckinServiceImpl(userRepository, bookingRepository, consentRepository,
                profileRepository, configRepository, new CheckinWindowPolicy(systemConfigService), processingClient, cipher,
                writer, new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));
        when(userRepository.findByEmailOrUsername("manager", "manager")).thenReturn(Optional.of(manager));
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        when(student.getId()).thenReturn(7L);
        when(student.getFullName()).thenReturn("Trung Nguyễn");
        when(booking.getId()).thenReturn(11L);
        when(booking.getUser()).thenReturn(student);
        when(booking.getStatus()).thenReturn(BookingStatus.APPROVED);
        when(booking.getStartTime()).thenReturn(Instant.now().minusSeconds(60));
        TimeSlot slot = mock(TimeSlot.class);
        when(slot.getId()).thenReturn(21L);
        when(slot.getStatus()).thenReturn(TimeSlotStatus.AVAILABLE);
        when(booking.getTimeSlot()).thenReturn(slot);
        when(systemConfigService.getConfig()).thenReturn(systemConfig(10));
        when(consentRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(
                FaceConsentLogEntity.builder().consentStatus(FaceConsentStatus.GRANTED).build()));
        FaceProfileEntity profile = mock(FaceProfileEntity.class);
        when(profile.getUser()).thenReturn(student);
        when(profile.getEncryptedEmbedding()).thenReturn(
                cipher.encrypt("[[0.1,0.2],[0.3,0.4],[0.5,0.6]]"));
        when(bookingRepository.findManagerFaceCheckinBookingsBySlot(eq(3L), eq(21L),
                any(Instant.class), any(Instant.class))).thenReturn(List.of(booking));
        when(profileRepository.findAllByUserIdInAndProfileStatusAndActiveTrueAndDeletedFalse(
                List.of(7L), FaceProfileStatus.ACTIVE)).thenReturn(List.of(profile));
        when(configRepository.findAllByActiveTrueAndDeletedFalse()).thenReturn(List.of(config()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingManagedApprovedBookingInsideWindowCommitsCheckin() {
        when(processingClient.match(any())).thenReturn(
                new FaceMatchResponse("MATCH", 0.91, 0.0003, false, true, null));
        when(writer.complete(7L, 3L, 11L, 0.91, 0.0003))
                .thenReturn(Instant.parse("2026-08-31T00:00:00Z"));

        var response = service.checkIn(request());

        assertTrue(response.checkedIn());
        assertTrue(response.userId().equals(7L));
        assertTrue(response.studentName().equals("Trung Nguyễn"));
        ArgumentCaptor<FaceMatchRequest> forwarded = ArgumentCaptor.forClass(FaceMatchRequest.class);
        verify(processingClient).match(forwarded.capture());
        assertTrue(forwarded.getValue().referenceEmbeddings().equals(List.of(
                List.of(0.1, 0.2), List.of(0.3, 0.4), List.of(0.5, 0.6))));
        assertTrue(forwarded.getValue().challengeFrames().size() == 4);
        verify(writer).complete(7L, 3L, 11L, 0.91, 0.0003);
    }

    @Test
    void identifiesTheMatchingStudentOnlyAmongBookingsInTheSelectedSlot() {
        User otherStudent = mock(User.class);
        Booking otherBooking = mock(Booking.class);
        FaceProfileEntity firstProfile = mock(FaceProfileEntity.class);
        FaceProfileEntity otherProfile = mock(FaceProfileEntity.class);
        when(otherStudent.getId()).thenReturn(8L);
        when(otherStudent.getFullName()).thenReturn("Sinh viên khác");
        when(otherBooking.getId()).thenReturn(12L);
        when(otherBooking.getUser()).thenReturn(otherStudent);
        when(otherBooking.getStatus()).thenReturn(BookingStatus.APPROVED);
        when(otherBooking.getStartTime()).thenReturn(Instant.now().minusSeconds(60));
        TimeSlot otherSlot = mock(TimeSlot.class);
        when(otherSlot.getStatus()).thenReturn(TimeSlotStatus.AVAILABLE);
        when(otherBooking.getTimeSlot()).thenReturn(otherSlot);
        when(firstProfile.getUser()).thenReturn(student);
        when(firstProfile.getEncryptedEmbedding()).thenReturn(cipher.encrypt("[[0.1,0.2]]"));
        when(otherProfile.getUser()).thenReturn(otherStudent);
        when(otherProfile.getEncryptedEmbedding()).thenReturn(cipher.encrypt("[[0.7,0.8]]"));
        when(bookingRepository.findManagerFaceCheckinBookingsBySlot(eq(3L), eq(21L),
                any(Instant.class), any(Instant.class))).thenReturn(List.of(booking, otherBooking));
        when(profileRepository.findAllByUserIdInAndProfileStatusAndActiveTrueAndDeletedFalse(
                List.of(7L, 8L), FaceProfileStatus.ACTIVE)).thenReturn(List.of(firstProfile, otherProfile));
        when(processingClient.match(any()))
                .thenReturn(new FaceMatchResponse("NO_MATCH", 0.98, 0.88, false, true, "NO_MATCH"))
                .thenReturn(new FaceMatchResponse("MATCH", 0.93, 0.89, false, true, null));
        when(writer.complete(8L, 3L, 12L, 0.93, 0.89))
                .thenReturn(Instant.parse("2026-08-31T00:00:00Z"));

        var response = service.checkIn(request());

        assertTrue(response.checkedIn());
        assertTrue(response.bookingId().equals(12L));
        assertTrue(response.studentName().equals("Sinh viên khác"));
        verify(writer).complete(8L, 3L, 12L, 0.93, 0.89);
    }

    @Test
    void belowThresholdResultDoesNotAttributeTheUnknownFaceToAStudent() {
        when(processingClient.match(any())).thenReturn(
                new FaceMatchResponse("MATCH", 0.7, 0.88, false, true, null));

        var response = service.checkIn(request());

        assertFalse(response.checkedIn());
        assertTrue(response.bookingId() == null);
        assertTrue(response.userId() == null);
        verify(writer, never()).recordFailure(any(), any(), any(), any(), any(), any());
        verify(writer, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void bookingOutsideManagedLabsIsDeniedBeforeBiometricProcessing() {
        when(bookingRepository.findManagerFaceCheckinBookingsBySlot(eq(3L), eq(21L),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        assertThrows(AccessDeniedException.class, () -> service.checkIn(request()));

        verify(processingClient, never()).match(any());
    }

    @Test
    void candidatesOnlyQueriesBookingsInsideConfiguredCheckinWindow() {
        service.candidates();

        verify(bookingRepository).findFaceCheckinCandidatesForManager(
                eq(3L), any(Instant.class), any(Instant.class));
    }

    private FaceCheckinRequest request() {
        String image = Base64.getEncoder().encodeToString("image".getBytes());
        return new FaceCheckinRequest(21L,
                image, "image/jpeg",
                List.of(
                        new FaceChallengeFrameRequest(image, "image/jpeg"),
                        new FaceChallengeFrameRequest(image, "image/jpeg"),
                        new FaceChallengeFrameRequest(image, "image/jpeg"),
                        new FaceChallengeFrameRequest(image, "image/jpeg")),
                "signed-challenge");
    }

    private FaceSecurityConfigEntity config() {
        return FaceSecurityConfigEntity.builder()
                .configKey("test")
                .faceEnabled(true)
                .confidenceThreshold(new BigDecimal("0.8500"))
                .livenessThreshold(new BigDecimal("0.7000"))
                .livenessRequired(true)
                .qrWhenFaceDisabled(true)
                .qrWhenServiceUnavailable(true)
                .qrWhenProfileUnavailable(true)
                .manualOverrideEnabled(true)
                .build();
    }

    private SystemConfigResponse systemConfig(int checkinWindowMinutes) {
        return new SystemConfigResponse(null, null,
                new SystemConfigResponse.BookingConfig(checkinWindowMinutes, 0, false, false),
                null, null);
    }
}
