package com.web.labportalbackend.ai.context.impl;

import com.web.labportalbackend.ai.context.AiContextReadDeniedException;
import com.web.labportalbackend.ai.context.AiDomainContext;
import com.web.labportalbackend.ai.context.AiDomainContextBuilder;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.ai.context.AiLabAvailableSlotsContext;
import com.web.labportalbackend.ai.context.AiBoundedList;
import com.web.labportalbackend.ai.context.TrustedContextInput;
import com.web.labportalbackend.ai.enums.AiAssistantDomain;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.enums.AiResourceScope;
import com.web.labportalbackend.admin.systemconfig.service.SystemConfigService;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.repository.TimeSlotRepository;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;

@Component
public class AiLabAssistantContextBuilder implements AiDomainContextBuilder {

    private static final int AVAILABLE_SLOT_LIMIT = 50;
    private static final int MANAGED_SLOT_LIMIT = 50;

    private final LaboratoryRepository laboratoryRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final BookingRepository bookingRepository;
    private final SystemConfigService systemConfigService;

    public AiLabAssistantContextBuilder(LaboratoryRepository laboratoryRepository,
                                        TimeSlotRepository timeSlotRepository,
                                        BookingRepository bookingRepository,
                                        SystemConfigService systemConfigService) {
        this.laboratoryRepository = laboratoryRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.bookingRepository = bookingRepository;
        this.systemConfigService = systemConfigService;
    }

    @Override public AiAssistantDomain domain() { return AiAssistantDomain.LAB; }

    @Override
    public AiDomainContext build(TrustedContextInput input) {
        if (input.decision().domain() != domain() || input.decision().resolvedResource().labId() == null) {
            throw new AiContextReadDeniedException();
        }
        AiCapability capability = input.decision().capability();
        String selectedRoleName = input.decision().selectedSystemRole().name();
        Long labId = input.decision().resolvedResource().labId();
        boolean managedScope = input.decision().resolvedResource().effectiveScope() == AiResourceScope.MANAGED_LAB;
        if (managedScope && !laboratoryRepository.existsAiContextManagedLab(input.actorId(), labId, selectedRoleName)) {
            throw new AiContextReadDeniedException();
        }
        AiLabContext.Laboratory lab = laboratoryRepository.findAiContextLaboratory(input.actorId(), labId, selectedRoleName)
                .orElseThrow(AiContextReadDeniedException::new);
        AiLabContext.Slot slot = null;
        AiLabContext.OwnBooking booking = null;
        AiLabContext.ManagedSummary managedSummary = null;
        AiLabContext.LabPolicySnapshot labPolicySnapshot = null;
        AiLabContext.CheckinPolicySnapshot checkinPolicySnapshot = null;
        if (capability == AiCapability.LAB_AVAILABLE_SLOTS_READ) {
            var slots = timeSlotRepository.findAiContextAvailableSlots(
                    input.actorId(), labId, managedScope, input.builtAt(), selectedRoleName,
                    PageRequest.of(0, AVAILABLE_SLOT_LIMIT + 1));
            return new AiLabAvailableSlotsContext(lab,
                    AiBoundedList.fromOverfetch(slots, AVAILABLE_SLOT_LIMIT), input.builtAt());
        } else if (capability == AiCapability.LAB_POLICY_READ) {
            var config = systemConfigService.getConfig();
            labPolicySnapshot = new AiLabContext.LabPolicySnapshot(
                    config.booking().checkinWindowMinutes(), config.booking().cancelBeforeMinutes(),
                    config.booking().hidePastSlots(), config.booking().hideCancelledSlots(),
                    config.lab().disableBookingForInactiveLab());
        } else if (Set.of(AiCapability.LAB_SLOT_READ, AiCapability.LAB_BOOKING_DRAFT).contains(capability)) {
            boolean draft = capability == AiCapability.LAB_BOOKING_DRAFT;
            slot = timeSlotRepository.findAiContextSlot(input.actorId(), labId,
                    input.decision().resolvedResource().id(), managedScope, draft, input.builtAt(), selectedRoleName)
                    .orElseThrow(AiContextReadDeniedException::new);
        } else if (capability == AiCapability.LAB_OWN_BOOKING_READ) {
            booking = bookingRepository.findAiContextOwnBooking(input.actorId(), labId,
                    input.decision().resolvedResource().id(), selectedRoleName)
                    .orElseThrow(AiContextReadDeniedException::new);
        } else if (capability == AiCapability.LAB_CHECKIN_GUIDANCE) {
            var snapshot = input.decision().checkinGuidancePolicySnapshot();
            if (snapshot == null) {
                throw new AiContextReadDeniedException();
            }
            booking = bookingRepository.findAiContextCheckinBooking(input.actorId(), labId,
                    input.decision().resolvedResource().id(), input.builtAt(), snapshot.endInclusive(), selectedRoleName)
                    .orElseThrow(AiContextReadDeniedException::new);
            checkinPolicySnapshot = new AiLabContext.CheckinPolicySnapshot(snapshot.endInclusive());
        } else if (capability == AiCapability.LAB_MANAGED_SUMMARY) {
            if (!managedScope) {
                throw new AiContextReadDeniedException();
            }
            managedSummary = new AiLabContext.ManagedSummary(
                    timeSlotRepository.countAiContextManagedSlots(input.actorId(), labId, selectedRoleName),
                    bookingRepository.countAiContextManagedBookings(input.actorId(), labId, selectedRoleName),
                    AiBoundedList.fromOverfetch(timeSlotRepository.findAiContextManagedFutureSlots(
                            input.actorId(), labId, input.builtAt(), selectedRoleName,
                            PageRequest.of(0, MANAGED_SLOT_LIMIT + 1)), MANAGED_SLOT_LIMIT));
        }
        return new AiLabContext(lab, slot, booking, managedSummary, labPolicySnapshot, checkinPolicySnapshot,
                capability.action().name().equals("DRAFT"), policyOrDraftEligibilityLabel(capability));
    }

    private static String policyOrDraftEligibilityLabel(AiCapability capability) {
        return switch (capability) {
            case LAB_POLICY_READ -> "POLICY_INFORMATION_ONLY";
            case LAB_BOOKING_DRAFT -> "DRAFT_ONLY_NO_BOOKING_WRITE";
            case LAB_SHIFT_CREATE_DRAFT -> "DRAFT_ONLY_NO_SHIFT_WRITE";
            default -> null;
        };
    }
}
