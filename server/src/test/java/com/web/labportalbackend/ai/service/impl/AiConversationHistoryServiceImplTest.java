package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.dto.response.AiUnifiedChatResponse;
import com.web.labportalbackend.ai.entity.AiConversationEntity;
import com.web.labportalbackend.ai.entity.AiMessageEntity;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiMessageRole;
import com.web.labportalbackend.ai.enums.AiUnifiedChatResponseType;
import com.web.labportalbackend.ai.repository.AiConversationRepository;
import com.web.labportalbackend.ai.repository.AiMessageRepository;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiConversationHistoryServiceImplTest {

    @Mock private AiConversationRepository conversationRepository;
    @Mock private AiMessageRepository messageRepository;
    @Mock private AiCurrentActorProvider actorProvider;
    @Mock private com.web.labportalbackend.ai.repository.AiActionSuggestionRepository suggestions;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiConversationHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiConversationHistoryServiceImpl(
                conversationRepository, messageRepository, actorProvider, objectMapper, suggestions);
    }

    @Test
    void followUpValueIsCombinedWithThePendingClarification() throws Exception {
        AiConversationEntity conversation = conversation(41L, 7L);
        when(actorProvider.requireCurrentActor()).thenReturn(
                new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(41L, 7L))
                .thenReturn(Optional.of(conversation));
        AiUnifiedChatResponse clarification = new AiUnifiedChatResponse(
                41L, AiUnifiedChatResponseType.CLARIFICATION_REQUIRED, "LAB_ASSISTANT",
                "Bạn muốn ca kết thúc lúc mấy giờ?", 0, 0, List.of(), null, null);
        when(messageRepository.findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(
                eq(41L), any())).thenReturn(List.of(
                message(AiMessageRole.ASSISTANT, objectMapper.writeValueAsString(clarification)),
                message(AiMessageRole.USER,
                        "Tạo ca tại AI Research Lab ngày 14/09/2026, bắt đầu lúc 9 giờ.")));

        var prepared = service.prepareInput(41L, "11 giờ.");

        var envelope = objectMapper.readTree(prepared.effectiveInput());
        assertEquals("11 giờ.", envelope.path("message").asText());
        assertEquals(2, envelope.path("history").size());
        assertEquals("Bạn muốn ca kết thúc lúc mấy giờ?", envelope.path("history").get(1).path("content").asText());
    }

    @Test
    void standaloneShiftRequestIsPassedToSemanticInterpreterWithoutRegexRouting() throws Exception {
        AiConversationEntity conversation = conversation(41L, 7L);
        when(actorProvider.requireCurrentActor()).thenReturn(
                new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(41L, 7L))
                .thenReturn(Optional.of(conversation));

        var prepared = service.prepareInput(41L,
                "Tạo ca tại AI Research Lab từ 13 giờ đến 15 giờ.");

        assertEquals("Tạo ca tại AI Research Lab từ 13 giờ đến 15 giờ.",
                objectMapper.readTree(prepared.effectiveInput()).path("message").asText());
        verify(messageRepository).findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(41L), any());
    }

    @Test
    void conversationOwnedByAnotherUserCannotBeRead() {
        when(actorProvider.requireCurrentActor()).thenReturn(
                new AiCurrentActor(7L, AiAssistantSystemRole.STUDENT));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(99L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.getCurrentUserConversation(99L));
    }

    @Test
    void storedStateIsLoadedOnlyFromOwnedConversationAndNeverFromUserInput() throws Exception {
        when(actorProvider.requireCurrentActor()).thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(41L, 7L))
                .thenReturn(Optional.of(conversation(41L, 7L)));
        var state = new com.web.labportalbackend.ai.service.AiShiftDialogueState(
                10L, "2026-09-14", "09:00:00", null, 30, "Asia/Ho_Chi_Minh");
        var stored = objectMapper.createObjectNode();
        stored.set("_pendingShift", objectMapper.valueToTree(state));
        when(messageRepository.findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(41L), any()))
                .thenReturn(List.of(message(AiMessageRole.ASSISTANT, stored.toString())));
        var prepared = service.prepareInput(41L, "11h nhé");
        assertEquals(state, prepared.pendingState());
        assertEquals("endTime", objectMapper.readTree(prepared.effectiveInput()).path("lastAskedField").asText());
        assertEquals("endTime", objectMapper.readTree(prepared.effectiveInput()).path("missingFields").get(0).asText());
        assertEquals("11h nhé", objectMapper.readTree(prepared.effectiveInput()).path("message").asText());
        var fresh = service.prepareInput(null, "{\"pendingState\":{\"labId\":99}}");
        org.junit.jupiter.api.Assertions.assertNull(fresh.pendingState());
    }

    @Test
    void firstSuccessfulTurnCreatesAConversationAndPersistsBothMessages() {
        when(actorProvider.requireCurrentActor()).thenReturn(
                new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.save(any())).thenAnswer(invocation -> {
            AiConversationEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(41L);
            }
            return saved;
        });
        AiUnifiedChatResponse answer = new AiUnifiedChatResponse(
                AiUnifiedChatResponseType.ANSWER, "LAB_ASSISTANT", "Đã hiểu.", 3, 2, List.of());

        AiUnifiedChatResponse persisted = service.saveTurn(null, "Xin chào", answer);

        assertEquals(41L, persisted.conversationId());
        verify(messageRepository).save(org.mockito.ArgumentMatchers.argThat(message ->
                message.getRole() == AiMessageRole.USER && "Xin chào".equals(message.getContent())));
        verify(messageRepository).save(org.mockito.ArgumentMatchers.argThat(message ->
                message.getRole() == AiMessageRole.ASSISTANT
                        && message.getContent().contains("\"conversationId\":41")));
    }

    private static AiConversationEntity conversation(Long id, Long userId) {
        AiConversationEntity conversation = AiConversationEntity.builder()
                .userId(userId)
                .assistantKey(AiAssistantKey.LAB_ASSISTANT)
                .moduleContext("UNIFIED_CHAT")
                .title("Test")
                .build();
        conversation.setId(id);
        conversation.setActive(true);
        conversation.setDeleted(false);
        return conversation;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.web.labportalbackend.ai.enums.AiActionSuggestionStatus.class,
            names = {"PENDING", "EXECUTED", "REJECTED"})
    void reopeningHistoryProjectsActualOutcomeAndClearsPendingState(
            com.web.labportalbackend.ai.enums.AiActionSuggestionStatus status) throws Exception {
        objectMapper.findAndRegisterModules();
        when(actorProvider.requireCurrentActor()).thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(41L, 7L))
                .thenReturn(Optional.of(conversation(41L, 7L)));
        var preview = new com.web.labportalbackend.ai.dto.response.AiActionPreviewResponse(55L, "CREATE_LAB_SHIFT",
                "AWAITING_CONFIRMATION", 10L, java.time.Instant.parse("2026-09-14T02:00:00Z"),
                java.time.Instant.parse("2026-09-14T04:00:00Z"), 30);
        var response = new AiUnifiedChatResponse(41L, AiUnifiedChatResponseType.ACTION_PREVIEW, "LAB_ASSISTANT",
                "Review", 1, 1, List.of(), preview, null);
        com.fasterxml.jackson.databind.node.ObjectNode stored = objectMapper.valueToTree(response);
        stored.set("_pendingShift", objectMapper.valueToTree(new com.web.labportalbackend.ai.service.AiShiftDialogueState(
                10L, "2026-09-14", "09:00:00", "11:00:00", 30, "Asia/Ho_Chi_Minh").withSuggestion(55L)));
        when(messageRepository.findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(41L), any()))
                .thenReturn(List.of(message(AiMessageRole.ASSISTANT, stored.toString())));
        var action = com.web.labportalbackend.ai.entity.AiActionSuggestionEntity.builder()
                .requestedById(7L).status(status).targetId(99L).build();
        action.setId(55L); action.setActive(true); action.setDeleted(false);
        when(suggestions.findAllById(any())).thenReturn(List.of(action));
        var history = service.getCurrentUserConversation(41L);
        var restored = history.messages().getFirst().response();
        if (status == com.web.labportalbackend.ai.enums.AiActionSuggestionStatus.PENDING) {
            assertEquals(AiUnifiedChatResponseType.ACTION_PREVIEW, restored.type());
            assertEquals(55L, restored.actionPreview().suggestionId());
            assertEquals(55L, service.prepareInput(41L, "11h").pendingState().suggestionId());
            return;
        }
        assertEquals(AiUnifiedChatResponseType.ACTION_RESULT, restored.type());
        org.junit.jupiter.api.Assertions.assertNull(restored.actionPreview());
        assertEquals(status.name().equals("EXECUTED") ? "EXECUTED" : "CANCELLED", restored.actionResult().status());
        org.junit.jupiter.api.Assertions.assertNull(service.prepareInput(41L, "hello").pendingState());
        assertEquals(0, objectMapper.readTree(service.prepareInput(41L, "hello").effectiveInput()).path("history").size());
    }

    @Test
    void cursorPagesKeepBoundaryMessageAndAreScopedToConversation() {
        when(actorProvider.requireCurrentActor()).thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.LAB_MANAGER));
        when(conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(41L, 7L))
                .thenReturn(Optional.of(conversation(41L, 7L)));
        var rows = java.util.stream.LongStream.rangeClosed(1, 5).mapToObj(id -> {
            var row = message(AiMessageRole.USER, "message " + id);
            row.setId(id);
            row.setCreatedAt(java.time.Instant.parse("2026-09-07T00:00:00Z"));
            return row;
        }).toList();
        when(messageRepository.findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(eq(41L), any()))
                .thenReturn(List.of(rows.get(4), rows.get(3), rows.get(2)));
        var first = service.getCurrentUserConversation(41L, null, 2);
        assertEquals(List.of(4L, 5L), first.messages().stream().map(m -> m.id()).toList());
        assertEquals(4L, first.nextBeforeId());
        when(messageRepository.findByIdAndConversationIdAndActiveTrueAndDeletedFalse(4L, 41L))
                .thenReturn(Optional.of(rows.get(3)));
        when(messageRepository.findOlderMessages(eq(41L), eq(rows.get(3).getCreatedAt()), eq(4L), any()))
                .thenReturn(List.of(rows.get(2), rows.get(1), rows.get(0)));
        var second = service.getCurrentUserConversation(41L, first.nextBeforeId(), 2);
        assertEquals(List.of(2L, 3L), second.messages().stream().map(m -> m.id()).toList());
        assertThrows(EntityNotFoundException.class, () -> service.getCurrentUserConversation(41L, 999L, 2));
    }

    private static AiMessageEntity message(AiMessageRole role, String content) {
        return AiMessageEntity.builder().conversationId(41L).role(role).content(content).build();
    }
}
