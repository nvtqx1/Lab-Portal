package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.ai.config.AiShiftDefaults;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiShiftDialogueState;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiShiftDialogueServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LaboratoryRepository labs = mock(LaboratoryRepository.class);
    private final AiCurrentActorProvider actors = mock(AiCurrentActorProvider.class);
    private final AiShiftDialogueService service = new AiShiftDialogueService(mapper, labs, actors,
            java.time.Clock.fixed(java.time.Instant.parse("2026-09-07T00:00:00Z"), java.time.ZoneOffset.UTC),
            new AiShiftDefaults("Asia/Ho_Chi_Minh"));

    @BeforeEach
    void setup() {
        when(actors.requireCurrentActor()).thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(true);
        when(labs.findAiContextLaboratory(7L, 10L, "LAB_MANAGER"))
                .thenReturn(Optional.of(new AiLabContext.Laboratory(10L, "AI Research Lab", null, 30)));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode patch(String mode) {
        var p = mapper.createObjectNode();
        p.put("kind", "LAB_SHIFT_CREATE_INTERPRETATION").put("labRef", 10)
                .put("requestedLabName", "AI Research Lab").put("mode", mode).putNull("dateMention")
                .putNull("capacity").putNull("timeZone")
                .put("requiresHumanReview", true).putArray("clearFields");
        p.putArray("timeMentions");
        return p;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode withDate(
            com.fasterxml.jackson.databind.node.ObjectNode patch, int day, int month, Integer year) {
        var date = patch.putObject("dateMention").put("day", day).put("month", month);
        if (year == null) date.putNull("year"); else date.put("year", year);
        return patch;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode withTime(
            com.fasterxml.jackson.databind.node.ObjectNode patch, String role, int hour, int minute) {
        patch.withArray("timeMentions").addObject().put("role", role).put("hour", hour).put("minute", minute);
        return patch;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode completeTimes(
            com.fasterxml.jackson.databind.node.ObjectNode patch, int startHour, int endHour) {
        withTime(patch, "START", startHour, 0);
        return withTime(patch, "END", endHour, 0);
    }

    @Test
    void shortAnswerCompletesOnlyMissingEndTimeAndProducesDraft() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", null, 20, "Asia/Ho_Chi_Minh");
        var p = withTime(patch("CONTINUE"), "END", 11, 0);
        var result = service.resolve(10L, p.toString(), previous);
        assertNull(result.question());
        assertEquals("2026-09-14T09:00:00", result.draft().path("startLocalDateTime").asText());
        assertEquals("2026-09-14T11:00:00", result.draft().path("endLocalDateTime").asText());
        assertEquals(30, result.draft().path("capacity").asInt());
    }

    @Test
    void endOnlyRequestKeepsEndRoleAndAsksForStartTime() {
        var request = withDate(patch("NEW").putNull("requestedLabName"), 30, 9, 2026);
        withTime(request, "END", 11, 0);

        var result = service.resolve(10L, request.toString(), null);

        assertNull(result.draft());
        assertEquals("2026-09-30", result.state().date());
        assertNull(result.state().startTime());
        assertEquals("11:00:00", result.state().endTime());
        assertEquals("Bạn vui lòng bổ sung giờ bắt đầu.", result.question());
    }

    @Test
    void dayMonthWithoutYearUsesNextCalendarOccurrence() {
        var request = completeTimes(withDate(patch("NEW").putNull("requestedLabName"), 4, 10, null), 13, 15);

        var result = service.resolve(10L, request.toString(), null);

        assertNotNull(result.draft());
        assertEquals("2026-10-04", result.state().date());
        assertEquals("2026-10-04T13:00:00", result.draft().path("startLocalDateTime").asText());
        assertEquals("2026-10-04T15:00:00", result.draft().path("endLocalDateTime").asText());
    }

    @Test
    void newRequestDoesNotInheritOldDateOrTime() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", "11:00:00", 20, "Asia/Ho_Chi_Minh");
        var result = service.resolve(10L, completeTimes(patch("NEW"), 13, 15).toString(), previous);
        assertNull(result.draft());
        assertNull(result.state().date());
        assertEquals("Bạn vui lòng bổ sung ngày.", result.question());
        assertEquals(30, result.state().capacity());
    }

    @Test
    void correctionPreservesOtherValues() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", "11:00:00", 20, "Asia/Ho_Chi_Minh");
        var result = service.resolve(10L, withTime(patch("CONTINUE"), "END", 12, 0).toString(), previous);
        assertEquals("12:00:00", result.state().endTime());
        assertEquals("09:00:00", result.state().startTime());
    }

    @Test
    void invalidEndTimeIsAskedAgainAndNotStoredAsValid() {
        var p = completeTimes(withDate(patch("NEW"), 14, 9, 2026), 9, 8);
        var result = service.resolve(10L, p.toString(), null);
        assertNull(result.draft());
        assertNull(result.state().endTime());
        assertTrue(result.question().contains("sau giờ bắt đầu"));
    }

    @Test
    void namedLabCannotBeReplacedWithOnlyAuthorizedLab() {
        var result = service.resolve(10L, patch("NEW").put("requestedLabName", "Robotics Lab").toString(), null);
        assertNull(result.draft());
        assertFalse(result.state().labConfirmed());
        assertTrue(result.question().contains("tên đầy đủ"));
    }

    @Test
    void pastDateAsksForFutureDateWithoutLosingTimes() {
        var result = service.resolve(10L, completeTimes(withDate(patch("NEW"), 14, 6, 2024), 9, 11)
                .toString(), null);
        assertNull(result.draft());
        assertNull(result.state().date());
        assertEquals("09:00:00", result.state().startTime());
        assertTrue(result.question().contains("đã qua"));
    }

    @Test
    void omittedLabOnNewRequestUsesManagersOnlyAuthorizedLabAndDefaultCapacity() {
        var result = service.resolve(10L, completeTimes(withDate(patch("NEW").putNull("requestedLabName"),
                14, 9, 2026), 9, 11).toString(), null);
        assertNotNull(result.draft());
        assertTrue(result.state().labConfirmed());
        assertEquals(30, result.draft().path("capacity").asInt());
        assertEquals("2026-09-14T09:00:00", result.draft().path("startLocalDateTime").asText());
    }

    @Test
    void timeFollowUpCannotResolveAnExplicitLabConflict() {
        var conflictPatch = withDate(patch("NEW").put("requestedLabName", "Robotics Lab"), 14, 9, 2026);
        var conflict = service.resolve(10L, withTime(conflictPatch, "START", 9, 0).toString(), null);
        var followUp = service.resolve(10L, withTime(patch("CONTINUE").putNull("requestedLabName"),
                "END", 11, 0).toString(), conflict.state());
        assertFalse(followUp.state().labConfirmed());
        assertNull(followUp.draft());
        var corrected = service.resolve(10L, patch("CONTINUE").toString(), followUp.state());
        assertNotNull(corrected.draft());
    }

    @Test
    void explicitCapacityOverridesDefaultAndSurvivesFollowUp() {
        var firstPatch = withDate(patch("NEW").putNull("requestedLabName").put("capacity", 15), 14, 9, 2026);
        var first = service.resolve(10L, withTime(firstPatch, "START", 9, 0).toString(), null);
        assertEquals(15, first.state().capacity());
        var second = service.resolve(10L, withTime(patch("CONTINUE").putNull("requestedLabName"),
                "END", 11, 0).toString(), first.state());
        assertNotNull(second.draft());
        assertEquals(15, second.draft().path("capacity").asInt());
        assertEquals(com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource.USER,
                second.state().capacitySource());
    }

    @Test
    void explicitTimezoneOverridesDefaultAndIsUsedByDraft() {
        var result = service.resolve(10L, completeTimes(withDate(
                patch("NEW").put("timeZone", "Asia/Bangkok"), 14, 9, 2026), 9, 11).toString(), null);

        assertNotNull(result.draft());
        assertEquals("Asia/Bangkok", result.state().timeZone());
        assertEquals("Asia/Bangkok", result.draft().path("timeZone").asText());
        assertEquals(com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource.USER,
                result.state().timeZoneSource());
    }

    @Test
    void matchingFixedCapacityAndTimezoneAreAcceptedAsComparisons() {
        var result = service.resolve(10L, completeTimes(withDate(patch("NEW").put("capacity", 30)
                .put("timeZone", "Asia/Ho_Chi_Minh"), 14, 9, 2026), 9, 11).toString(), null);

        assertNotNull(result.draft());
        assertEquals(30, result.draft().path("capacity").asInt());
        assertEquals("Asia/Ho_Chi_Minh", result.draft().path("timeZone").asText());
    }

    @Test
    void invalidCapacityIsRetainedUntilUserChangesOrClearsIt() {
        var invalid = service.resolve(10L, completeTimes(withDate(
                patch("NEW").put("capacity", -5), 14, 9, 2026), 9, 11).toString(), null);
        assertNull(invalid.draft());
        assertEquals(-5, invalid.state().capacity());
        assertEquals(com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource.USER,
                invalid.state().capacitySource());
        var cleared = patch("CONTINUE");
        cleared.withArray("clearFields").add("capacity");
        var result = service.resolve(10L, cleared.toString(), invalid.state());
        assertNotNull(result.draft());
        assertEquals(30, result.draft().path("capacity").asInt());
        assertEquals(com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource.CLEARED,
                result.state().capacitySource());
    }

    @Test
    void invalidTimezoneIsRejectedWithoutReplacingItWithTheDefault() {
        var result = service.resolve(10L, completeTimes(withDate(
                patch("NEW").put("timeZone", "Not/A_Zone"), 14, 9, 2026), 9, 11).toString(), null);

        assertNull(result.draft());
        assertEquals("Not/A_Zone", result.state().timeZone());
        assertEquals(com.web.labportalbackend.ai.service.AiShiftDialogueState.ValueSource.USER,
                result.state().timeZoneSource());
        assertTrue(result.question().contains("múi giờ hợp lệ"));
    }

    @Test
    void forgedReferenceAndExtraModelFieldsAreRejected() {
        assertThrows(AiSuggestionPayloadValidationException.class, () -> service.resolve(10L,
                patch("NEW").put("labRef", 99).toString(), null));
        assertThrows(AiSuggestionPayloadValidationException.class, () -> service.resolve(10L,
                patch("NEW").put("confidence", 0.99).toString(), null));
        var duplicateRole = withTime(withTime(patch("NEW"), "END", 11, 0), "END", 12, 0);
        assertThrows(AiSuggestionPayloadValidationException.class, () -> service.resolve(10L,
                duplicateRole.toString(), null));
    }

    @Test
    void permissionsAreRecheckedEvenWithPreviouslyCollectedState() {
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.resolve(10L, patch("CONTINUE").toString(),
                new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", null, 20, "Asia/Ho_Chi_Minh")));
    }
}
