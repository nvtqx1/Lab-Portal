package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.entity.AiActionSuggestionEntity;
import com.web.labportalbackend.ai.enums.AiActionConfirmationStatus;
import com.web.labportalbackend.ai.enums.AiActionExecutionStatus;
import com.web.labportalbackend.ai.enums.AiActionSuggestionStatus;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiActionRiskBoundary;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.repository.AiActionSuggestionRepository;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidator;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.booking.dto.response.TimeSlotResponse;
import com.web.labportalbackend.booking.service.TimeSlotService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class AiActionSuggestionServiceImplTest {

    @Mock private AiActionSuggestionRepository repository;
    @Mock private AiCurrentActorProvider currentActorProvider;
    @Mock private AiSuggestionPayloadValidator payloadValidator;
    @Mock private TimeSlotService timeSlotService;

    private AiActionSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AiActionSuggestionServiceImpl(
                repository, currentActorProvider, payloadValidator, timeSlotService, objectMapper,
                Clock.fixed(Instant.parse("2026-09-06T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createLabShiftStoresPreviewWithoutWritingTimeSlot() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        doAnswer(invocation -> {
            AiActionSuggestionEntity entity = invocation.getArgument(0);
            entity.setId(41L);
            return entity;
        }).when(repository).save(any());
        AiAssistantChatResponse generated = new AiAssistantChatResponse(
                "LAB_ASSISTANT",
                "{\"kind\":\"LAB_SHIFT_CREATE_DRAFT\",\"labRef\":10,"
                        + "\"startLocalDateTime\":\"2026-09-07T15:00:00\","
                        + "\"endLocalDateTime\":\"2026-09-07T17:00:00\","
                        + "\"timeZone\":\"Asia/Ho_Chi_Minh\",\"capacity\":20,"
                        + "\"requiresHumanReview\":true}",
                12, 8, List.of());

        var preview = service.createLabShiftPreview(10L, generated);

        assertEquals(41L, preview.suggestionId());
        assertEquals("CREATE_LAB_SHIFT", preview.actionType());
        assertEquals(10L, preview.labId());
        assertEquals(20, preview.capacity());
        assertEquals(Instant.parse("2026-09-07T08:00:00Z"), preview.startTime());
        assertEquals(Instant.parse("2026-09-07T10:00:00Z"), preview.endTime());
        verify(payloadValidator).validate(any());
        verify(timeSlotService, never()).createSlot(any());
    }

    @Test
    void createLabShiftRejectsModelDraftWhoseStartTimeIsAlreadyPast() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        AiAssistantChatResponse generated = new AiAssistantChatResponse(
                "LAB_ASSISTANT",
                "{\"kind\":\"LAB_SHIFT_CREATE_DRAFT\",\"labRef\":10,"
                        + "\"startLocalDateTime\":\"2024-06-14T15:00:00\","
                        + "\"endLocalDateTime\":\"2024-06-14T17:00:00\","
                        + "\"timeZone\":\"Asia/Ho_Chi_Minh\",\"capacity\":20,"
                        + "\"requiresHumanReview\":true}",
                12, 8, List.of());

        assertThrows(AiSuggestionPayloadValidationException.class,
                () -> service.createLabShiftPreview(10L, generated));

        verify(repository, never()).save(any());
        verify(timeSlotService, never()).createSlot(any());
    }

    @Test
    void confirmUsesOnlyStoredPayloadAndMarksSuggestionExecuted() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        AiActionSuggestionEntity suggestion = pendingSuggestion();
        when(repository.findByIdForUpdate(41L)).thenReturn(java.util.Optional.of(suggestion));
        when(timeSlotService.createSlot(any())).thenReturn(TimeSlotResponse.builder().id(99L).labId(10L).build());

        var result = service.confirm(41L);

        assertEquals(99L, result.targetId());
        assertEquals(AiActionSuggestionStatus.EXECUTED, suggestion.getStatus());
        assertEquals(AiActionConfirmationStatus.CONFIRMED, suggestion.getConfirmationStatus());
        assertEquals(AiActionExecutionStatus.EXECUTED, suggestion.getExecutionStatus());
        assertEquals(7L, suggestion.getExecutedById());
        verify(timeSlotService).createSlot(any());
    }

    @Test
    void anotherUserCannotConfirmStoredPreview() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(8L, AiAssistantSystemRole.LAB_MANAGER));
        when(repository.findByIdForUpdate(41L)).thenReturn(java.util.Optional.of(pendingSuggestion()));

        assertThrows(AccessDeniedException.class, () -> service.confirm(41L));

        verify(timeSlotService, never()).createSlot(any());
    }

    @Test
    void alreadyExecutedPreviewCannotCreateAnotherTimeSlot() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        AiActionSuggestionEntity suggestion = pendingSuggestion();
        suggestion.setStatus(AiActionSuggestionStatus.EXECUTED);
        suggestion.setConfirmationStatus(AiActionConfirmationStatus.CONFIRMED);
        suggestion.setExecutionStatus(AiActionExecutionStatus.EXECUTED);
        when(repository.findByIdForUpdate(41L)).thenReturn(java.util.Optional.of(suggestion));

        assertThrows(IllegalStateException.class, () -> service.confirm(41L));

        verify(timeSlotService, never()).createSlot(any());
    }

    @Test
    void confirmRejectsAStoredPreviewThatHasBecomePast() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        AiActionSuggestionEntity suggestion = pendingSuggestion();
        suggestion.setPayloadJson("{\"labId\":10,\"startTime\":\"2024-06-14T08:00:00Z\","
                + "\"endTime\":\"2024-06-14T10:00:00Z\",\"capacity\":20}");
        when(repository.findByIdForUpdate(41L)).thenReturn(java.util.Optional.of(suggestion));

        assertThrows(IllegalStateException.class, () -> service.confirm(41L));

        verify(timeSlotService, never()).createSlot(any());
        assertEquals(AiActionSuggestionStatus.PENDING, suggestion.getStatus());
        assertEquals(AiActionConfirmationStatus.PENDING, suggestion.getConfirmationStatus());
    }

    private static AiActionSuggestionEntity pendingSuggestion() {
        AiActionSuggestionEntity entity = AiActionSuggestionEntity.builder()
                .requestedById(7L)
                .assistantKey(AiAssistantKey.LAB_ASSISTANT)
                .actionType("CREATE_LAB_SHIFT")
                .targetModule("BOOKING")
                .payloadJson("{\"labId\":10,\"startTime\":\"2026-09-10T01:00:00Z\","
                        + "\"endTime\":\"2026-09-10T03:00:00Z\",\"capacity\":20}")
                .status(AiActionSuggestionStatus.PENDING)
                .resourceType(AiResourceType.LABORATORY)
                .resourceId(10L)
                .actionRiskLevel(AiActionRiskBoundary.CONFIRM_REQUIRED)
                .confirmationStatus(AiActionConfirmationStatus.PENDING)
                .executionStatus(AiActionExecutionStatus.NOT_REQUESTED)
                .build();
        entity.setId(41L);
        entity.setCreatedAt(Instant.parse("2026-09-05T00:00:00Z"));
        return entity;
    }
}
