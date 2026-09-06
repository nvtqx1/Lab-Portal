package com.web.labportalbackend.ai.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.LabStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiLabContext(
        Laboratory laboratory,
        Slot slot,
        OwnBooking booking,
        ManagedSummary managedSummary,
        LabPolicySnapshot labPolicySnapshot,
        CheckinPolicySnapshot checkinPolicySnapshot,
        boolean draftOnly,
        String policyOrDraftEligibilityLabel) implements AiDomainContext {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Laboratory(Long id, String name, LabStatus status, Integer capacity) {

        public Laboratory(Long id, String name, LabStatus status) {
            this(id, name, status, null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Slot(Long id, Instant startTime, Instant endTime, TimeSlotStatus status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OwnBooking(Long id, BookingStatus status, Slot slot) {
    }

    /** Bounded managed-lab view; no booking or membership rows are represented. */
    public record ManagedSummary(long activeSlotCount,
                                 long activeBookingCount,
                                 AiBoundedList<Slot> futureSlots) {
    }

    /** Public operational policy subset; account, upload, and research settings are excluded. */
    public record LabPolicySnapshot(
            int checkinWindowMinutes,
            int cancelBeforeMinutes,
            boolean hidePastSlots,
            boolean hideCancelledSlots,
            boolean disableBookingForInactiveLab) {
    }

    public record CheckinPolicySnapshot(Instant endInclusive) {
    }
}
