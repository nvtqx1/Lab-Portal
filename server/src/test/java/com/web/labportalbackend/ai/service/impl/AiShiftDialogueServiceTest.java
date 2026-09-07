package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.context.AiLabContext;
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
            java.time.Clock.fixed(java.time.Instant.parse("2026-09-07T00:00:00Z"), java.time.ZoneOffset.UTC));

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
                .put("requestedLabName", "AI Research Lab").put("mode", mode).putNull("date")
                .putNull("startTime").putNull("endTime").putNull("capacity").putNull("timeZone")
                .put("requiresHumanReview", true).putArray("clearFields");
        return p;
    }

    @Test
    void shortAnswerCompletesOnlyMissingEndTimeAndProducesDraft() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", null, 20, "Asia/Ho_Chi_Minh");
        var p = patch("CONTINUE").put("endTime", "11:00");
        var result = service.resolve(10L, p.toString(), previous);
        assertNull(result.question());
        assertEquals("2026-09-14T09:00:00", result.draft().path("startLocalDateTime").asText());
        assertEquals("2026-09-14T11:00:00", result.draft().path("endLocalDateTime").asText());
        assertEquals(20, result.draft().path("capacity").asInt());
    }

    @Test
    void newRequestDoesNotInheritOldDateOrTime() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", "11:00:00", 20, "Asia/Ho_Chi_Minh");
        var result = service.resolve(10L, patch("NEW").put("startTime", "13:00").put("endTime", "15:00").toString(), previous);
        assertNull(result.draft());
        assertNull(result.state().date());
        assertEquals("Bạn vui lòng bổ sung ngày.", result.question());
        assertEquals(30, result.state().capacity());
    }

    @Test
    void correctionPreservesOtherValues() {
        var previous = new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", "11:00:00", 20, "Asia/Ho_Chi_Minh");
        var result = service.resolve(10L, patch("CONTINUE").put("endTime", "12:00").toString(), previous);
        assertEquals("12:00:00", result.state().endTime());
        assertEquals("09:00:00", result.state().startTime());
    }

    @Test
    void invalidEndTimeIsAskedAgainAndNotStoredAsValid() {
        var p = patch("NEW").put("date", "2026-09-14").put("startTime", "09:00").put("endTime", "08:00");
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
        var result = service.resolve(10L, patch("NEW").put("date", "2024-06-14")
                .put("startTime", "09:00").put("endTime", "11:00").toString(), null);
        assertNull(result.draft());
        assertNull(result.state().date());
        assertEquals("09:00:00", result.state().startTime());
        assertTrue(result.question().contains("đã qua"));
    }

    @Test
    void omittedLabOnNewRequestRequiresResolutionBeforePreview() {
        var result = service.resolve(10L, patch("NEW").putNull("requestedLabName").put("date", "2026-09-14")
                .put("startTime", "09:00").put("endTime", "11:00").toString(), null);
        assertNull(result.draft());
        var resolved = service.resolve(10L, patch("CONTINUE").toString(), result.state());
        assertNotNull(resolved.draft());
        assertEquals("2026-09-14T09:00:00", resolved.draft().path("startLocalDateTime").asText());
    }

    @Test
    void forgedReferenceAndExtraModelFieldsAreRejected() {
        assertThrows(AiSuggestionPayloadValidationException.class, () -> service.resolve(10L,
                patch("NEW").put("labRef", 99).toString(), null));
        assertThrows(AiSuggestionPayloadValidationException.class, () -> service.resolve(10L,
                patch("NEW").put("confidence", 0.99).toString(), null));
    }

    @Test
    void permissionsAreRecheckedEvenWithPreviouslyCollectedState() {
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.resolve(10L, patch("CONTINUE").toString(),
                new AiShiftDialogueState(10L, "2026-09-14", "09:00:00", null, 20, "Asia/Ho_Chi_Minh")));
    }
}
