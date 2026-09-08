package com.web.labportalbackend.booking.service.impl;

import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.admin.systemconfig.dto.SystemConfigResponse;
import com.web.labportalbackend.admin.systemconfig.service.SystemConfigService;
import com.web.labportalbackend.admin.audit.enums.AuditAction;
import com.web.labportalbackend.admin.audit.enums.AuditModule;
import com.web.labportalbackend.admin.audit.service.AuditLogService;
import com.web.labportalbackend.booking.dto.request.CancelTimeSlotRequest;
import com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest;
import com.web.labportalbackend.booking.dto.response.TimeSlotResponse;
import com.web.labportalbackend.booking.entity.TimeSlot;
import com.web.labportalbackend.booking.event.BookingEmailType;
import com.web.labportalbackend.booking.mapper.TimeSlotMapper;
import com.web.labportalbackend.booking.outbox.BookingOutboxService;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.repository.TimeSlotBookingCounts;
import com.web.labportalbackend.booking.repository.TimeSlotRepository;
import com.web.labportalbackend.booking.service.TimeSlotService;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.LabStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.common.email.BookingEmailData;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.lab.repository.MembershipRepository;
import com.web.labportalbackend.notification.enums.NotificationEventType;
import com.web.labportalbackend.notification.enums.NotificationTargetModule;
import com.web.labportalbackend.notification.service.NotificationEmitter;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TimeSlotServiceImpl implements TimeSlotService {

    private static final List<TimeSlotStatus> HIDDEN_SLOT_STATUSES =
            List.of(TimeSlotStatus.CANCELLED, TimeSlotStatus.CLOSED, TimeSlotStatus.EXPIRED,
                    TimeSlotStatus.INACTIVE, TimeSlotStatus.ARCHIVED);
    private static final List<TimeSlotStatus> TERMINAL_SLOT_STATUSES =
            List.of(TimeSlotStatus.CANCELLED, TimeSlotStatus.CLOSED,
                    TimeSlotStatus.INACTIVE, TimeSlotStatus.ARCHIVED);

    private final TimeSlotRepository timeSlotRepository;
    private final LaboratoryRepository laboratoryRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final BookingOutboxService bookingOutboxService;
    private final SystemConfigService systemConfigService;
    private final AuditLogService auditLogService;
    private final NotificationEmitter notificationEmitter;

    @Override
    @Transactional
    public TimeSlotResponse createSlot(CreateTimeSlotRequest request) {
        User currentUser = getCurrentUser();
        Laboratory lab = laboratoryRepository.findById(request.getLabId())
                .orElseThrow(() -> new EntityNotFoundException("Lab not found: " + request.getLabId()));

        assertCanCreateSlot(currentUser, lab);
        validateCreateRequest(request, lab);

        TimeSlotStatus status = request.getStatus() != null
                ? request.getStatus()
                : TimeSlotStatus.AVAILABLE;

        TimeSlot slot = TimeSlot.builder()
                .lab(lab)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .capacity(request.getCapacity())
                .status(status)
                .build();

        TimeSlot saved = timeSlotRepository.save(slot);
        auditLogService.log(
                currentUser,
                AuditAction.CREATE_SLOT,
                AuditModule.BOOKING,
                "TIME_SLOT",
                saved.getId(),
                "Manager đã tạo ca sử dụng cho " + lab.getLabName() + "."
        );
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getSlotsByLab(Long labId) {
        Laboratory lab = laboratoryRepository.findById(labId)
                .orElseThrow(() -> new EntityNotFoundException("Lab not found: " + labId));

        User currentUser = getCurrentUser();
        assertCanViewSlots(currentUser, lab);

        SystemConfigResponse.BookingConfig bookingConfig = systemConfig().booking();
        Instant cutoff = bookingConfig.hidePastSlots() ? Instant.now() : Instant.EPOCH;
        List<TimeSlotStatus> hiddenStatuses;
        if (currentUser.hasRole("LAB_MANAGER")) {
            hiddenStatuses = bookingConfig.hideCancelledSlots()
                    ? TERMINAL_SLOT_STATUSES
                    : List.of(TimeSlotStatus.CLOSED, TimeSlotStatus.INACTIVE, TimeSlotStatus.ARCHIVED);
        } else {
            hiddenStatuses = bookingConfig.hideCancelledSlots()
                    ? HIDDEN_SLOT_STATUSES
                    : List.of(TimeSlotStatus.CLOSED, TimeSlotStatus.EXPIRED,
                            TimeSlotStatus.INACTIVE, TimeSlotStatus.ARCHIVED);
        }
        List<TimeSlot> slots = timeSlotRepository.findUsableByLabId(labId, cutoff, hiddenStatuses);
        return toResponses(slots);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeSlotResponse> getSlotHistoryByLab(Long labId) {
        Laboratory lab = laboratoryRepository.findById(labId)
                .orElseThrow(() -> new EntityNotFoundException("Lab not found: " + labId));
        User currentUser = getCurrentUser();
        if (!currentUser.hasRole("LAB_MANAGER")) {
            throw new AccessDeniedException("Only lab managers can view time slot history");
        }
        assertManagerOwnsLab(currentUser, lab);
        return toResponses(timeSlotRepository.findHistoryByLabId(
                labId, Instant.now(), TERMINAL_SLOT_STATUSES));
    }

    private List<TimeSlotResponse> toResponses(List<TimeSlot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        List<Long> slotIds = slots.stream().map(TimeSlot::getId).toList();
        Map<Long, TimeSlotBookingCounts> countsBySlotId = bookingRepository
                .findActiveCountsByTimeSlotIds(slotIds)
                .stream()
                .collect(Collectors.toMap(TimeSlotBookingCounts::timeSlotId, Function.identity()));
        return slots.stream()
                .map(slot -> toResponse(slot, countsBySlotId.get(slot.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TimeSlotResponse getSlotById(Long slotId) {
        TimeSlot slot = timeSlotRepository.findActiveById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("Slot not found: " + slotId));
        assertCanViewSlots(getCurrentUser(), slot.getLab());
        return toResponse(slot);
    }

    @Override
    @Transactional
    public TimeSlotResponse updateSlotStatus(Long slotId, String status) {
        TimeSlot slot = timeSlotRepository.findActiveById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("Slot not found: " + slotId));
        assertCanManageSlot(getCurrentUser(), slot.getLab());
        TimeSlotStatus requestedStatus = TimeSlotStatus.valueOf(status.toUpperCase());
        long affectedBookingCount = activeBookingCount(slotId);
        if (requestedStatus != slot.getStatus() && affectedBookingCount > 0) {
            throw new IllegalStateException("Cannot change a slot with active bookings through the generic status operation; use the dedicated cancel or complete operation");
        }
        slot.setStatus(requestedStatus);
        return toResponse(timeSlotRepository.save(slot));
    }

    @Override
    @Transactional
    public TimeSlotResponse cancelSlot(Long slotId, CancelTimeSlotRequest request) {
        TimeSlot slot = timeSlotRepository.findActiveById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("Slot not found: " + slotId));
        User currentUser = getCurrentUser();
        assertCanManageSlot(currentUser, slot.getLab());

        if (!slot.getStartTime().isAfter(java.time.Instant.now())) {
            throw new IllegalStateException("Cannot cancel an expired time slot");
        }

        slot.setStatus(TimeSlotStatus.CANCELLED);
        List<com.web.labportalbackend.booking.entity.Booking> affectedBookings =
                bookingRepository.findBySlotIdAndStatusIn(
                        slotId,
                        List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED)
                );
        affectedBookings.forEach(booking -> booking.setStatus(BookingStatus.CANCELLED_BY_MANAGER));
        bookingRepository.saveAll(affectedBookings);
        TimeSlot savedSlot = timeSlotRepository.save(slot);

        String cancellationReason = request.getReason() == null ? "" : request.getReason().trim();
        affectedBookings.forEach(booking -> {
            notificationEmitter.emit(
                    booking.getUser().getId(),
                    NotificationEventType.BOOKING_CANCELLED,
                    "Ca sử dụng đã bị hủy",
                    "Quản lý đã hủy ca sử dụng tại " + savedSlot.getLab().getLabName()
                            + ". Lý do: " + cancellationReason,
                    NotificationTargetModule.BOOKING,
                    booking.getId(),
                    null
            );
            enqueueBookingEmail(booking, BookingEmailType.SLOT_CANCELLED, cancellationReason);
        });

        auditLogService.log(
                currentUser,
                AuditAction.CANCEL_SLOT,
                AuditModule.BOOKING,
                "TIME_SLOT",
                savedSlot.getId(),
                "Manager đã hủy ca sử dụng của " + savedSlot.getLab().getLabName() + "."
        );

        return toResponse(savedSlot);
    }

    @Override
    @Transactional
    public TimeSlotResponse completeSlot(Long slotId) {
        TimeSlot slot = timeSlotRepository.findActiveById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("Slot not found: " + slotId));
        User currentUser = getCurrentUser();
        assertCanManageSlot(currentUser, slot.getLab());
        Instant now = Instant.now();
        if (now.isBefore(slot.getStartTime()) || now.isAfter(slot.getEndTime())) {
            throw new IllegalStateException("Chỉ có thể kết thúc ca trong thời gian sử dụng.");
        }
        if (slot.getStatus() == TimeSlotStatus.CANCELLED || slot.getStatus() == TimeSlotStatus.CLOSED
                || slot.getStatus() == TimeSlotStatus.INACTIVE || slot.getStatus() == TimeSlotStatus.ARCHIVED) {
            throw new IllegalStateException("Ca sử dụng đã kết thúc hoặc không còn hoạt động.");
        }

        List<com.web.labportalbackend.booking.entity.Booking> activeBookings =
                bookingRepository.findBySlotIdAndStatusIn(slotId,
                        List.of(BookingStatus.CHECKED_IN, BookingStatus.IN_PROGRESS));
        activeBookings.forEach(booking -> booking.setStatus(BookingStatus.COMPLETED));
        bookingRepository.saveAll(activeBookings);
        slot.setStatus(TimeSlotStatus.CLOSED);
        TimeSlot saved = timeSlotRepository.save(slot);
        activeBookings.forEach(booking -> {
            notificationEmitter.emit(
                    booking.getUser().getId(),
                    NotificationEventType.BOOKING_SESSION_COMPLETED,
                    "Ca sử dụng đã kết thúc",
                    "Quản lý đã kết thúc ca sử dụng tại " + saved.getLab().getLabName() + ".",
                    NotificationTargetModule.BOOKING,
                    booking.getId(),
                    null
            );
            enqueueBookingEmail(booking, BookingEmailType.SESSION_COMPLETED, "Quản lý PTN đã kết thúc ca sử dụng.");
        });
        auditLogService.log(currentUser, AuditAction.COMPLETE_LAB_SESSION, AuditModule.BOOKING,
                "TIME_SLOT", saved.getId(), "Manager đã kết thúc ca sử dụng lab.");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteSlot(Long slotId) {
        TimeSlot slot = timeSlotRepository.findActiveById(slotId)
                .orElseThrow(() -> new EntityNotFoundException("Slot not found: " + slotId));
        assertCanManageSlot(getCurrentUser(), slot.getLab());
        long affectedBookingCount = activeBookingCount(slotId);
        if (affectedBookingCount > 0) {
            throw new IllegalStateException("Cannot delete a slot with active bookings; use the dedicated cancel or complete operation");
        }
        slot.setDeleted(true);
        timeSlotRepository.save(slot);
    }

    private long activeBookingCount(Long slotId) {
        return bookingRepository.countActiveByTimeSlotIdAndStatusIn(slotId,
                List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED,
                        BookingStatus.CHECKED_IN, BookingStatus.IN_PROGRESS));
    }

    private void validateCreateRequest(CreateTimeSlotRequest request, Laboratory lab) {
        if (lab.getStatus() != LabStatus.AVAILABLE) {
            throw new IllegalStateException("Lab is not available for time slot creation");
        }

        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        if (request.getCapacity() == null || request.getCapacity() <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }

        boolean hasOverlap = !timeSlotRepository
                .findOverlappingSlots(lab.getId(), request.getStartTime(), request.getEndTime(),
                        TERMINAL_SLOT_STATUSES)
                .isEmpty();
        if (hasOverlap) {
            throw new IllegalStateException("Khung giờ này bị trùng với một ca sử dụng đang hoạt động");
        }
    }

    private void assertCanViewSlots(User currentUser, Laboratory lab) {
        if (currentUser.hasRole("LAB_MANAGER")) {
            assertManagerOwnsLab(currentUser, lab);
            return;
        }

        if (currentUser.hasRole("STUDENT") &&
                membershipRepository.existsByUserIdAndLaboratoryIdAndActiveTrueAndDeletedFalse(
                        currentUser.getId(),
                        lab.getId()
                )) {
            return;
        }

        throw new AccessDeniedException("Cannot view time slots for this lab");
    }

    private void assertCanCreateSlot(User currentUser, Laboratory lab) {
        if (currentUser.hasRole("LAB_MANAGER")) {
            assertManagerOwnsLab(currentUser, lab);
            return;
        }

        throw new AccessDeniedException("Only lab managers can create time slots");
    }

    private void assertCanManageSlot(User currentUser, Laboratory lab) {
        if (currentUser.hasRole("LAB_MANAGER")) {
            assertManagerOwnsLab(currentUser, lab);
            return;
        }

        throw new AccessDeniedException("Only lab managers can manage time slots");
    }

    private void assertManagerOwnsLab(User currentUser, Laboratory lab) {
        Laboratory managedLab = laboratoryRepository.findFirstByManagerIdAndDeletedFalse(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("Lab manager is not assigned to any lab"));

        if (!managedLab.getId().equals(lab.getId())) {
            throw new AccessDeniedException("Cannot manage time slots from another lab");
        }
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("Current user not found"));
    }

    private TimeSlotResponse toResponse(TimeSlot slot) {
        long approvedCount = bookingRepository.countActiveByTimeSlotIdAndStatusIn(
                slot.getId(),
                List.of(BookingStatus.APPROVED, BookingStatus.CHECKED_IN)
        );
        long checkedInCount = bookingRepository.countActiveByTimeSlotIdAndStatus(
                slot.getId(),
                BookingStatus.CHECKED_IN
        );
        long pendingCount = bookingRepository.countActiveByTimeSlotIdAndStatus(
                slot.getId(),
                BookingStatus.PENDING_APPROVAL
        );
        return TimeSlotMapper.toResponse(slot, approvedCount, checkedInCount, pendingCount);
    }

    private TimeSlotResponse toResponse(TimeSlot slot, TimeSlotBookingCounts counts) {
        if (counts == null) {
            return TimeSlotMapper.toResponse(slot, 0L, 0L, 0L);
        }
        return TimeSlotMapper.toResponse(
                slot,
                counts.approvedCount(),
                counts.checkedInCount(),
                counts.pendingCount()
        );
    }

    private SystemConfigResponse systemConfig() {
        return systemConfigService.getConfig();
    }

    private void enqueueBookingEmail(
            com.web.labportalbackend.booking.entity.Booking booking,
            BookingEmailType emailType,
            String note
    ) {
        bookingOutboxService.enqueueEmail(booking.getId(), emailType, booking.getUser().getEmail(),
                BookingEmailData.builder()
                        .studentName(booking.getUser().getFullName())
                        .labName(booking.getLab().getLabName())
                        .startTime(booking.getStartTime())
                        .endTime(booking.getEndTime())
                        .status(booking.getStatus().name())
                        .note(note)
                        .build());
    }
}
