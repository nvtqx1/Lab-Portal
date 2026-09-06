package com.web.labportalbackend.ai.context.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.web.labportalbackend.ai.context.*;
import com.web.labportalbackend.ai.enums.*;
import com.web.labportalbackend.ai.service.AiCapabilityDecision;
import com.web.labportalbackend.admin.systemconfig.dto.SystemConfigResponse;
import com.web.labportalbackend.admin.systemconfig.service.SystemConfigService;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.repository.TimeSlotRepository;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import java.util.List;

class AiLabAssistantContextBuilderTest {
    @Test void availableSlotsAreBoundedAndReadWithFreshAuthorizedScope() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        TimeSlotRepository slots = mock(TimeSlotRepository.class);
        Instant readAt = Instant.parse("2026-09-05T08:00:00Z");
        when(labs.findAiContextLaboratory(7L, 10L, "STUDENT"))
                .thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "Lab", null)));
        List<AiLabContext.Slot> overfetch = java.util.stream.LongStream.rangeClosed(1, 51)
                .mapToObj(id -> new AiLabContext.Slot(id, readAt.plusSeconds(id * 3600),
                        readAt.plusSeconds((id + 1) * 3600),
                        com.web.labportalbackend.common.enums.TimeSlotStatus.AVAILABLE))
                .toList();
        when(slots.findAiContextAvailableSlots(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(readAt),
                org.mockito.ArgumentMatchers.eq("STUDENT"), any())).thenReturn(overfetch);
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(
                labs, slots, mock(BookingRepository.class), mock(SystemConfigService.class));

        AiLabAvailableSlotsContext context = (AiLabAvailableSlotsContext) builder.build(availableSlotsInput(readAt));

        assertEquals(50, context.availableSlots().returnedCount());
        assertEquals(true, context.availableSlots().truncated());
        assertEquals(readAt, context.evaluatedAt());
    }
    @Test void currentAcceptedActorIsBoundToLabProjection() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        when(labs.findAiContextLaboratory(7L, 10L, "STUDENT")).thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "Lab", null, 24)));
        SystemConfigService configs = policyConfig();
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(labs, mock(TimeSlotRepository.class), mock(BookingRepository.class), configs);
        AiLabContext context = (AiLabContext) builder.build(input());
        assertEquals(10L, context.laboratory().id());
        assertEquals(24, context.laboratory().capacity());
        assertEquals("POLICY_INFORMATION_ONLY", context.policyOrDraftEligibilityLabel());
        assertEquals(10, context.labPolicySnapshot().checkinWindowMinutes());
        assertEquals(30, context.labPolicySnapshot().cancelBeforeMinutes());
        verify(labs).findAiContextLaboratory(7L, 10L, "STUDENT");
    }

    @Test void checkinGuidanceUsesOnlyTheFinalResolverDerivedInclusiveEnd() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        Instant readAt = Instant.parse("2026-08-07T09:00:00Z");
        Instant endInclusive = Instant.parse("2026-08-07T09:30:00Z");
        when(labs.findAiContextLaboratory(7L, 10L, "STUDENT")).thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "Lab", null)));
        when(bookings.findAiContextCheckinBooking(7L, 10L, 30L, readAt, endInclusive, "STUDENT"))
                .thenReturn(Optional.of(new AiLabContext.OwnBooking(30L,
                        com.web.labportalbackend.common.enums.BookingStatus.APPROVED, null)));
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(labs, mock(TimeSlotRepository.class), bookings, mock(SystemConfigService.class));

        AiLabContext context = (AiLabContext) builder.build(checkinInput(readAt, endInclusive));

        assertEquals(30L, context.booking().id());
        assertEquals(endInclusive, context.checkinPolicySnapshot().endInclusive());
        verify(bookings).findAiContextCheckinBooking(7L, 10L, 30L, readAt, endInclusive, "STUDENT");
    }
    @Test void staleManagedLabFailsClosedBeforeAggregateCounts() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        TimeSlotRepository slots = mock(TimeSlotRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        when(labs.findAiContextLaboratory(7L, 10L, "LAB_MANAGER"))
                .thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "Lab", null)));
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(false);
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(labs, slots, bookings, mock(SystemConfigService.class));

        assertThrows(AiContextReadDeniedException.class, () -> builder.build(managedSummaryInput()));

        verify(labs).existsAiContextManagedLab(7L, 10L, "LAB_MANAGER");
        verify(slots, never()).countAiContextManagedSlots(7L, 10L, "LAB_MANAGER");
        verify(bookings, never()).countAiContextManagedBookings(7L, 10L, "LAB_MANAGER");
    }

    @Test void managedSummaryIncludesBoundedFutureSlotsFromTheAuthorizedLab() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        TimeSlotRepository slots = mock(TimeSlotRepository.class);
        BookingRepository bookings = mock(BookingRepository.class);
        Instant readAt = Instant.parse("2026-09-06T08:00:00Z");
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(true);
        when(labs.findAiContextLaboratory(7L, 10L, "LAB_MANAGER"))
                .thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "AI Research Lab", null, 30)));
        when(slots.countAiContextManagedSlots(7L, 10L, "LAB_MANAGER")).thenReturn(4L);
        when(bookings.countAiContextManagedBookings(7L, 10L, "LAB_MANAGER")).thenReturn(2L);
        List<AiLabContext.Slot> futureSlots = List.of(new AiLabContext.Slot(
                31L, Instant.parse("2026-09-13T03:00:00Z"),
                Instant.parse("2026-09-13T05:00:00Z"),
                com.web.labportalbackend.common.enums.TimeSlotStatus.AVAILABLE));
        when(slots.findAiContextManagedFutureSlots(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(readAt), org.mockito.ArgumentMatchers.eq("LAB_MANAGER"),
                any())).thenReturn(futureSlots);
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(
                labs, slots, bookings, mock(SystemConfigService.class));

        AiLabContext context = (AiLabContext) builder.build(managedSummaryInput(readAt));

        assertEquals(4L, context.managedSummary().activeSlotCount());
        assertEquals(2L, context.managedSummary().activeBookingCount());
        assertEquals(List.of(31L), context.managedSummary().futureSlots().values().stream()
                .map(AiLabContext.Slot::id).toList());
        assertEquals(false, context.managedSummary().futureSlots().truncated());
    }

    @Test void bookingDraftExposesOnlyTheFixedDraftEligibilityLabel() {
        LaboratoryRepository labs = mock(LaboratoryRepository.class);
        TimeSlotRepository slots = mock(TimeSlotRepository.class);
        when(labs.findAiContextLaboratory(7L, 10L, "STUDENT"))
                .thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "Lab", null)));
        when(slots.findAiContextSlot(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(20L), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("STUDENT")))
                .thenReturn(Optional.of(new AiLabContext.Slot(20L, Instant.now(), Instant.now(), null)));
        AiLabAssistantContextBuilder builder = new AiLabAssistantContextBuilder(labs, slots, mock(BookingRepository.class), mock(SystemConfigService.class));
        AiCapabilityDecision decision = new AiCapabilityDecision(true, 7L, com.web.labportalbackend.ai.enums.AiAssistantSystemRole.STUDENT, AiAssistantKey.LAB_ASSISTANT,
                AiAssistantDomain.LAB, AiCapability.LAB_BOOKING_DRAFT,
                new AiCapabilityDecision.ResolvedResource(AiResourceType.TIME_SLOT, 20L, 10L,
                        null, null, null, AiResourceScope.SELF),
                AiCapabilityDecisionReason.ALLOWED_BY_EFFECTIVE_PERMISSION, null,
                AiActionRiskBoundary.DRAFT_ONLY, Set.of(), null);

        AiLabContext context = (AiLabContext) builder.build(new TrustedContextInput(decision, 7L, null, Instant.now()));

        assertEquals("DRAFT_ONLY_NO_BOOKING_WRITE", context.policyOrDraftEligibilityLabel());
        assertEquals(true, context.draftOnly());
    }
    private static TrustedContextInput input() {
        AiCapabilityDecision d = new AiCapabilityDecision(true, 7L, com.web.labportalbackend.ai.enums.AiAssistantSystemRole.STUDENT, AiAssistantKey.LAB_ASSISTANT,
                AiAssistantDomain.LAB, AiCapability.LAB_POLICY_READ,
                new AiCapabilityDecision.ResolvedResource(AiResourceType.LABORATORY, 10L, 10L, null, null, null, AiResourceScope.EXISTING_BUSINESS_PERMISSION),
                AiCapabilityDecisionReason.ALLOWED_BY_EFFECTIVE_PERMISSION, null, AiActionRiskBoundary.READ_ONLY, Set.of(), null);
        return new TrustedContextInput(d, 7L, null, Instant.now());
    }

    private static SystemConfigService policyConfig() {
        SystemConfigService service = mock(SystemConfigService.class);
        when(service.getConfig()).thenReturn(new SystemConfigResponse(
                null,
                new SystemConfigResponse.LabConfig(true, true, true, true),
                new SystemConfigResponse.BookingConfig(10, 30, true, true),
                null,
                null));
        return service;
    }

    private static TrustedContextInput checkinInput(Instant readAt, Instant endInclusive) {
        AiCapabilityDecision d = new AiCapabilityDecision(true, 7L, com.web.labportalbackend.ai.enums.AiAssistantSystemRole.STUDENT, AiAssistantKey.LAB_ASSISTANT,
                AiAssistantDomain.LAB, AiCapability.LAB_CHECKIN_GUIDANCE,
                new AiCapabilityDecision.ResolvedResource(AiResourceType.BOOKING, 30L, 10L,
                        null, null, null, AiResourceScope.SELF),
                AiCapabilityDecisionReason.ALLOWED_BY_EFFECTIVE_PERMISSION, null, AiActionRiskBoundary.READ_ONLY,
                Set.of(), new AiCapabilityDecision.CheckinGuidancePolicySnapshot(endInclusive));
        return new TrustedContextInput(d, 7L, null, readAt);
    }

    private static TrustedContextInput managedSummaryInput() {
        return managedSummaryInput(Instant.now());
    }

    private static TrustedContextInput managedSummaryInput(Instant readAt) {
        AiCapabilityDecision d = new AiCapabilityDecision(true, 7L, com.web.labportalbackend.ai.enums.AiAssistantSystemRole.LAB_MANAGER, AiAssistantKey.LAB_ASSISTANT,
                AiAssistantDomain.LAB, AiCapability.LAB_MANAGED_SUMMARY,
                new AiCapabilityDecision.ResolvedResource(AiResourceType.LABORATORY, 10L, 10L,
                        null, null, null, AiResourceScope.MANAGED_LAB),
                AiCapabilityDecisionReason.ALLOWED_BY_EFFECTIVE_PERMISSION, null,
                AiActionRiskBoundary.READ_ONLY, Set.of(), null);
        return new TrustedContextInput(d, 7L, null, readAt);
    }

    private static TrustedContextInput availableSlotsInput(Instant readAt) {
        AiCapabilityDecision d = new AiCapabilityDecision(true, 7L, AiAssistantSystemRole.STUDENT,
                AiAssistantKey.LAB_ASSISTANT, AiAssistantDomain.LAB, AiCapability.LAB_AVAILABLE_SLOTS_READ,
                new AiCapabilityDecision.ResolvedResource(AiResourceType.LABORATORY, 10L, 10L,
                        null, null, null, AiResourceScope.LAB_MEMBER),
                AiCapabilityDecisionReason.ALLOWED_BY_EFFECTIVE_PERMISSION, null,
                AiActionRiskBoundary.READ_ONLY, Set.of(), null);
        return new TrustedContextInput(d, 7L, null, readAt);
    }
}
