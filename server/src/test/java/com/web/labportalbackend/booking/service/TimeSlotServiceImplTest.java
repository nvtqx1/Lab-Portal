package com.web.labportalbackend.booking.service;

import com.web.labportalbackend.admin.audit.enums.AuditAction;
import com.web.labportalbackend.admin.audit.enums.AuditModule;
import com.web.labportalbackend.admin.audit.service.AuditLogService;
import com.web.labportalbackend.admin.systemconfig.service.SystemConfigService;
import com.web.labportalbackend.admin.systemconfig.dto.SystemConfigResponse;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.booking.entity.Booking;
import com.web.labportalbackend.booking.entity.TimeSlot;
import com.web.labportalbackend.booking.outbox.BookingOutboxService;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.repository.TimeSlotBookingCounts;
import com.web.labportalbackend.booking.repository.TimeSlotRepository;
import com.web.labportalbackend.booking.service.impl.TimeSlotServiceImpl;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.lab.repository.MembershipRepository;
import com.web.labportalbackend.notification.enums.NotificationEventType;
import com.web.labportalbackend.notification.enums.NotificationTargetModule;
import com.web.labportalbackend.notification.service.NotificationEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TimeSlotServiceImplTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerCompletesRunningSlotAndCheckedInBookings() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        NotificationEmitter notificationEmitter = mock(NotificationEmitter.class);
        BookingOutboxService bookingOutboxService = mock(BookingOutboxService.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, bookingRepository, bookingOutboxService,
                mock(SystemConfigService.class), auditLogService, notificationEmitter);

        User manager = mock(User.class);
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        lab.setLabName("PTN Vật lý");
        TimeSlot slot = new TimeSlot();
        slot.setId(9L);
        slot.setLab(lab);
        slot.setStatus(TimeSlotStatus.EXPIRED);
        slot.setCapacity(10);
        slot.setStartTime(Instant.now().minusSeconds(300));
        slot.setEndTime(Instant.now().plusSeconds(600));
        Booking booking = new Booking();
        User student = new User();
        student.setId(7L);
        student.setEmail("student@example.com");
        student.setFullName("Student A");
        booking.setId(11L);
        booking.setUser(student);
        booking.setLab(lab);
        booking.setStartTime(slot.getStartTime());
        booking.setEndTime(slot.getEndTime());
        booking.setStatus(BookingStatus.CHECKED_IN);

        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(labRepository.findFirstByManagerIdAndDeletedFalse(3L)).thenReturn(Optional.of(lab));
        when(slotRepository.findActiveById(9L)).thenReturn(Optional.of(slot));
        when(bookingRepository.findBySlotIdAndStatusIn(9L,
                List.of(BookingStatus.CHECKED_IN, BookingStatus.IN_PROGRESS))).thenReturn(List.of(booking));
        when(slotRepository.save(slot)).thenReturn(slot);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));

        service.completeSlot(9L);

        assertEquals(TimeSlotStatus.CLOSED, slot.getStatus());
        assertEquals(BookingStatus.COMPLETED, booking.getStatus());
        verify(bookingRepository).saveAll(List.of(booking));
        verify(auditLogService).log(manager, AuditAction.COMPLETE_LAB_SESSION, AuditModule.BOOKING,
                "TIME_SLOT", 9L, "Manager đã kết thúc ca sử dụng lab.");
        verify(notificationEmitter).emit(7L, NotificationEventType.BOOKING_SESSION_COMPLETED,
                "Ca sử dụng đã kết thúc", "Quản lý đã kết thúc ca sử dụng tại PTN Vật lý.",
                NotificationTargetModule.BOOKING, 11L, null);
        verify(bookingOutboxService).enqueueEmail(eq(11L), eq(com.web.labportalbackend.booking.event.BookingEmailType.SESSION_COMPLETED),
                eq("student@example.com"), any());
    }

    @Test
    void managerCannotUseGenericStatusEndpointToChangeASlotWithActiveBookings() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, bookingRepository, mock(BookingOutboxService.class),
                mock(SystemConfigService.class), mock(AuditLogService.class), mock(NotificationEmitter.class));

        User manager = mock(User.class);
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        TimeSlot slot = new TimeSlot();
        slot.setId(9L);
        slot.setLab(lab);
        slot.setStatus(TimeSlotStatus.AVAILABLE);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(labRepository.findFirstByManagerIdAndDeletedFalse(3L)).thenReturn(Optional.of(lab));
        when(slotRepository.findActiveById(9L)).thenReturn(Optional.of(slot));
        when(bookingRepository.countActiveByTimeSlotIdAndStatusIn(9L,
                List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED,
                        BookingStatus.CHECKED_IN, BookingStatus.IN_PROGRESS))).thenReturn(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));

        assertThrows(IllegalStateException.class, () -> service.updateSlotStatus(9L, "MAINTENANCE"));

        verify(slotRepository, never()).save(any());
    }

    @Test
    void managerListingKeepsRunningSlotAfterCheckinWindowExpires() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, bookingRepository, mock(BookingOutboxService.class),
                systemConfigService, mock(AuditLogService.class), mock(NotificationEmitter.class));

        User manager = mock(User.class);
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        TimeSlot runningSlot = new TimeSlot();
        runningSlot.setId(9L);
        runningSlot.setLab(lab);
        runningSlot.setStatus(TimeSlotStatus.EXPIRED);
        runningSlot.setCapacity(10);
        runningSlot.setStartTime(Instant.now().minusSeconds(900));
        runningSlot.setEndTime(Instant.now().plusSeconds(900));

        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(labRepository.findById(5L)).thenReturn(Optional.of(lab));
        when(labRepository.findFirstByManagerIdAndDeletedFalse(3L)).thenReturn(Optional.of(lab));
        when(systemConfigService.getConfig()).thenReturn(new SystemConfigResponse(
                null, null, new SystemConfigResponse.BookingConfig(10, 30, true, true), null, null));
        when(slotRepository.findUsableByLabId(eq(5L), any(Instant.class), anyList()))
                .thenReturn(List.of(runningSlot));
        when(bookingRepository.findActiveCountsByTimeSlotIds(List.of(9L)))
                .thenReturn(List.of(new TimeSlotBookingCounts(9L, 4L, 2L, 1L)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));

        var result = service.getSlotsByLab(5L);

        assertEquals(1, result.size());
        assertEquals(TimeSlotStatus.EXPIRED, result.getFirst().getStatus());
        assertEquals(4L, result.getFirst().getApprovedCount());
        assertEquals(2L, result.getFirst().getCheckedInCount());
        assertEquals(1L, result.getFirst().getPendingCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeSlotStatus>> hiddenStatuses = ArgumentCaptor.forClass(List.class);
        verify(slotRepository).findUsableByLabId(eq(5L), any(Instant.class), hiddenStatuses.capture());
        assertFalse(hiddenStatuses.getValue().contains(TimeSlotStatus.EXPIRED));
        verify(bookingRepository).findActiveCountsByTimeSlotIds(List.of(9L));
        verify(bookingRepository, never()).countActiveByTimeSlotIdAndStatus(anyLong(), any());
        verify(bookingRepository, never()).countActiveByTimeSlotIdAndStatusIn(anyLong(), anyList());
    }

    @Test
    void managerCanReviewPastAndTerminalSlotsFromTheManagedLab() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, bookingRepository, mock(BookingOutboxService.class),
                mock(SystemConfigService.class), mock(AuditLogService.class), mock(NotificationEmitter.class));

        User manager = mock(User.class);
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        TimeSlot closedSlot = new TimeSlot();
        closedSlot.setId(9L);
        closedSlot.setLab(lab);
        closedSlot.setStatus(TimeSlotStatus.CLOSED);
        closedSlot.setCapacity(10);
        closedSlot.setStartTime(Instant.now().minusSeconds(3600));
        closedSlot.setEndTime(Instant.now().minusSeconds(1800));

        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(labRepository.findById(5L)).thenReturn(Optional.of(lab));
        when(labRepository.findFirstByManagerIdAndDeletedFalse(3L)).thenReturn(Optional.of(lab));
        when(slotRepository.findHistoryByLabId(eq(5L), any(Instant.class), anyList()))
                .thenReturn(List.of(closedSlot));
        when(bookingRepository.findActiveCountsByTimeSlotIds(List.of(9L)))
                .thenReturn(List.of(new TimeSlotBookingCounts(9L, 6L, 5L, 0L)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));

        var result = service.getSlotHistoryByLab(5L);

        assertEquals(1, result.size());
        assertEquals(TimeSlotStatus.CLOSED, result.getFirst().getStatus());
        assertEquals(6L, result.getFirst().getApprovedCount());
        assertEquals(5L, result.getFirst().getCheckedInCount());
        verify(slotRepository).findHistoryByLabId(eq(5L), any(Instant.class), argThat(statuses ->
                statuses.contains(TimeSlotStatus.CANCELLED)
                        && statuses.contains(TimeSlotStatus.CLOSED)
                        && statuses.contains(TimeSlotStatus.ARCHIVED)));
    }

    @Test
    void studentCannotRequestManagerSlotHistory() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, mock(BookingRepository.class), mock(BookingOutboxService.class),
                mock(SystemConfigService.class), mock(AuditLogService.class), mock(NotificationEmitter.class));

        User student = mock(User.class);
        when(student.hasRole("LAB_MANAGER")).thenReturn(false);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        when(userRepository.findByUsername("student")).thenReturn(Optional.of(student));
        when(labRepository.findById(5L)).thenReturn(Optional.of(lab));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("student", "n/a", List.of()));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.getSlotHistoryByLab(5L));
        verify(slotRepository, never()).findHistoryByLabId(anyLong(), any(), anyList());
    }

    @Test
    void terminalSlotsDoNotBlockCreatingANewSlotAtTheSameTime() {
        TimeSlotRepository slotRepository = mock(TimeSlotRepository.class);
        LaboratoryRepository labRepository = mock(LaboratoryRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        TimeSlotServiceImpl service = new TimeSlotServiceImpl(slotRepository, labRepository,
                membershipRepository, userRepository, mock(BookingRepository.class), mock(BookingOutboxService.class),
                mock(SystemConfigService.class), mock(AuditLogService.class), mock(NotificationEmitter.class));

        User manager = mock(User.class);
        when(manager.getId()).thenReturn(3L);
        when(manager.hasRole("LAB_MANAGER")).thenReturn(true);
        Laboratory lab = new Laboratory();
        lab.setId(5L);
        lab.setStatus(com.web.labportalbackend.common.enums.LabStatus.AVAILABLE);
        Instant start = Instant.parse("2026-09-09T07:45:00Z");
        Instant end = Instant.parse("2026-09-09T08:00:00Z");
        com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest request =
                com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest.builder()
                        .labId(5L)
                        .startTime(start)
                        .endTime(end)
                        .capacity(30)
                        .status(TimeSlotStatus.AVAILABLE)
                        .build();

        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(labRepository.findById(5L)).thenReturn(Optional.of(lab));
        when(labRepository.findFirstByManagerIdAndDeletedFalse(3L)).thenReturn(Optional.of(lab));
        when(slotRepository.findOverlappingSlots(eq(5L), eq(start), eq(end), anyList()))
                .thenReturn(List.of());
        when(slotRepository.save(any(TimeSlot.class))).thenAnswer(invocation -> {
            TimeSlot saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "n/a", List.of()));

        var result = service.createSlot(request);

        assertEquals(12L, result.getId());
        verify(slotRepository).findOverlappingSlots(eq(5L), eq(start), eq(end), argThat(statuses ->
                statuses.contains(TimeSlotStatus.CANCELLED)
                        && statuses.contains(TimeSlotStatus.CLOSED)
                        && statuses.contains(TimeSlotStatus.INACTIVE)
                        && statuses.contains(TimeSlotStatus.ARCHIVED)));
    }
}
