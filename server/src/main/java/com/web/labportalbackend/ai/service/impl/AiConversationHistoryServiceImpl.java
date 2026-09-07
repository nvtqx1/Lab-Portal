package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.dto.response.AiConversationDetailResponse;
import com.web.labportalbackend.ai.dto.response.AiConversationMessageResponse;
import com.web.labportalbackend.ai.dto.response.AiConversationSummaryResponse;
import com.web.labportalbackend.ai.dto.response.AiUnifiedChatResponse;
import com.web.labportalbackend.ai.entity.AiConversationEntity;
import com.web.labportalbackend.ai.entity.AiMessageEntity;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiMessageRole;
import com.web.labportalbackend.ai.enums.AiUnifiedChatResponseType;
import com.web.labportalbackend.ai.repository.AiConversationRepository;
import com.web.labportalbackend.ai.repository.AiMessageRepository;
import com.web.labportalbackend.ai.repository.AiActionSuggestionRepository;
import com.web.labportalbackend.ai.entity.AiActionSuggestionEntity;
import com.web.labportalbackend.ai.enums.AiActionSuggestionStatus;
import com.web.labportalbackend.ai.dto.response.AiActionResultResponse;
import java.util.Map;
import java.util.stream.Collectors;
import com.web.labportalbackend.ai.service.AiConversationHistoryService;
import com.web.labportalbackend.ai.service.AiShiftDialogueState;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiConversationHistoryServiceImpl implements AiConversationHistoryService {

    private static final int CONVERSATION_LIMIT = 50;
    private static final int MESSAGE_LIMIT = 200;
    private static final int TITLE_LIMIT = 100;

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final AiCurrentActorProvider currentActorProvider;
    private final ObjectMapper objectMapper;
    private final AiActionSuggestionRepository suggestions;

    public AiConversationHistoryServiceImpl(AiConversationRepository conversationRepository,
                                            AiMessageRepository messageRepository,
                                            AiCurrentActorProvider currentActorProvider,
                                            ObjectMapper objectMapper,
                                            AiActionSuggestionRepository suggestions) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.currentActorProvider = currentActorProvider;
        this.objectMapper = objectMapper;
        this.suggestions = suggestions;
    }

    @Override
    @Transactional(readOnly = true)
    public PreparedInput prepareInput(Long conversationId, String input) {
        if (conversationId != null) {
            ownedConversation(conversationId);
        }
        List<AiMessageEntity> recent = conversationId == null ? List.of() : messageRepository
                .findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(
                        conversationId, PageRequest.of(0, 6));
        AiShiftDialogueState state = recent.isEmpty() ? null : readState(recent.getFirst());
        var actions = actionsFor(recent);
        if (state != null && state.suggestionId() != null) {
            var action = actions.get(state.suggestionId());
            if (action == null || action.getStatus() != AiActionSuggestionStatus.PENDING) state = null;
        }
        var envelope = objectMapper.createObjectNode();
        envelope.put("dialogueVersion", 1);
        envelope.put("message", input);
        envelope.set("pendingState", objectMapper.valueToTree(state));
        var history = envelope.putArray("history");
        // Include question + answer pairs in chronological order, with a bounded input budget.
        for (int index = recent.size() - 1; index >= 0; index--) {
            var message = recent.get(index);
            var response = message.getRole() == AiMessageRole.ASSISTANT
                    ? currentResponse(readResponse(message.getContent()), actions) : null;
            String content = response == null ? message.getContent() : response.answer();
            history.addObject().put("role", message.getRole().name())
                    .put("content", content.substring(0, Math.min(content.length(), 1500)));
        }
        return new PreparedInput(conversationId, envelope.toString(), state);
    }

    @Override
    @Transactional
    public AiUnifiedChatResponse saveTurn(Long conversationId, String userInput, AiUnifiedChatResponse response) {
        return saveTurn(conversationId, userInput, response, null);
    }

    @Override
    @Transactional
    public AiUnifiedChatResponse saveTurn(Long conversationId, String userInput, AiUnifiedChatResponse response,
                                         AiShiftDialogueState pendingState) {
        AiCurrentActor actor = currentActorProvider.requireCurrentActor();
        AiConversationEntity conversation = conversationId == null
                ? createConversation(actor, userInput, response)
                : conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(conversationId, actor.id())
                        .orElseThrow(() -> new EntityNotFoundException("AI conversation not found"));
        AiUnifiedChatResponse persistedResponse = response.withConversationId(conversation.getId());
        messageRepository.save(AiMessageEntity.builder()
                .conversationId(conversation.getId())
                .role(AiMessageRole.USER)
                .content(userInput)
                .build());
        messageRepository.save(AiMessageEntity.builder()
                .conversationId(conversation.getId())
                .role(AiMessageRole.ASSISTANT)
                .content(writeResponse(persistedResponse, pendingState))
                .build());
        if (persistedResponse.assistantKey() != null) {
            conversation.setAssistantKey(AiAssistantKey.valueOf(persistedResponse.assistantKey()));
        }
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        return persistedResponse;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiConversationSummaryResponse> listCurrentUserConversations() {
        AiCurrentActor actor = currentActorProvider.requireCurrentActor();
        return conversationRepository.findByUserIdAndActiveTrueAndDeletedFalseOrderByUpdatedAtDescIdDesc(
                        actor.id(), PageRequest.of(0, CONVERSATION_LIMIT)).stream()
                .map(conversation -> new AiConversationSummaryResponse(
                        conversation.getId(), conversation.getTitle(), conversation.getUpdatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AiConversationDetailResponse getCurrentUserConversation(Long conversationId) {
        AiConversationEntity conversation = ownedConversation(conversationId);
        List<AiMessageEntity> messages = new ArrayList<>(messageRepository
                .findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(
                        conversation.getId(), PageRequest.of(0, MESSAGE_LIMIT)));
        Collections.reverse(messages);
        var actions = actionsFor(messages);
        return new AiConversationDetailResponse(conversation.getId(), conversation.getTitle(), messages.stream()
                .map(message -> toMessageResponse(message, actions))
                .toList());
    }

    private AiConversationEntity ownedConversation(Long conversationId) {
        AiCurrentActor actor = currentActorProvider.requireCurrentActor();
        return conversationRepository.findByIdAndUserIdAndActiveTrueAndDeletedFalse(conversationId, actor.id())
                .orElseThrow(() -> new EntityNotFoundException("AI conversation not found"));
    }

    private AiConversationEntity createConversation(AiCurrentActor actor,
                                                    String userInput,
                                                    AiUnifiedChatResponse response) {
        AiAssistantKey assistantKey = response.assistantKey() == null
                ? switch (actor.role()) {
                    case ADMIN -> AiAssistantKey.ADMIN_ASSISTANT;
                    case LAB_MANAGER, STUDENT -> AiAssistantKey.LAB_ASSISTANT;
                }
                : AiAssistantKey.valueOf(response.assistantKey());
        String normalizedTitle = userInput.strip().replaceAll("\\s+", " ");
        String title = normalizedTitle.length() <= TITLE_LIMIT
                ? normalizedTitle : normalizedTitle.substring(0, TITLE_LIMIT);
        return conversationRepository.save(AiConversationEntity.builder()
                .userId(actor.id())
                .assistantKey(assistantKey)
                .moduleContext("UNIFIED_CHAT")
                .title(title)
                .build());
    }

    private AiConversationMessageResponse toMessageResponse(AiMessageEntity message, Map<Long, AiActionSuggestionEntity> actions) {
        AiUnifiedChatResponse response = message.getRole() == AiMessageRole.ASSISTANT
                ? currentResponse(readResponse(message.getContent()), actions) : null;
        String content = response == null ? message.getContent() : response.answer();
        return new AiConversationMessageResponse(
                message.getId(), message.getRole(), content, response, message.getCreatedAt());
    }

    private Map<Long, AiActionSuggestionEntity> actionsFor(List<AiMessageEntity> messages) {
        var ids = new java.util.HashSet<>(messages.stream().filter(message -> message.getRole() == AiMessageRole.ASSISTANT)
                .map(message -> readResponse(message.getContent())).filter(java.util.Objects::nonNull)
                .filter(response -> response.actionPreview() != null)
                .map(response -> response.actionPreview().suggestionId()).toList());
        messages.stream().map(this::readState).filter(java.util.Objects::nonNull)
                .map(AiShiftDialogueState::suggestionId).filter(java.util.Objects::nonNull).forEach(ids::add);
        if (ids.isEmpty()) return Map.of();
        Long actorId = currentActorProvider.requireCurrentActor().id();
        return suggestions.findAllById(ids).stream()
                .filter(action -> actorId.equals(action.getRequestedById())
                        && Boolean.TRUE.equals(action.getActive()) && !Boolean.TRUE.equals(action.getDeleted()))
                .collect(Collectors.toMap(AiActionSuggestionEntity::getId, action -> action));
    }

    private AiUnifiedChatResponse currentResponse(AiUnifiedChatResponse response, Map<Long, AiActionSuggestionEntity> actions) {
        if (response == null || response.actionPreview() == null) return response;
        var action = actions.get(response.actionPreview().suggestionId());
        if (action != null && action.getStatus() == AiActionSuggestionStatus.PENDING) return response;
        boolean executed = action != null && action.getStatus() == AiActionSuggestionStatus.EXECUTED;
        var result = new AiActionResultResponse(response.actionPreview().suggestionId(), "CREATE_LAB_SHIFT",
                executed ? "EXECUTED" : action == null ? "UNAVAILABLE" : "CANCELLED",
                executed ? action.getTargetId() : null);
        return new AiUnifiedChatResponse(response.conversationId(), AiUnifiedChatResponseType.ACTION_RESULT,
                response.assistantKey(), executed ? "Đã tạo ca Lab #" + action.getTargetId() + "."
                        : "Bản xem trước này không còn hiệu lực.", response.promptTokens(), response.completionTokens(),
                response.citations(), null, result);
    }

    private String writeResponse(AiUnifiedChatResponse response, AiShiftDialogueState state) {
        com.fasterxml.jackson.databind.node.ObjectNode stored = objectMapper.valueToTree(response);
        stored.set("_pendingShift", objectMapper.valueToTree(state));
        return stored.toString();
    }

    private AiShiftDialogueState readState(AiMessageEntity message) {
        if (message.getRole() != AiMessageRole.ASSISTANT) {
            return null;
        }
        try {
            var state = objectMapper.readTree(message.getContent()).get("_pendingShift");
            return state == null || state.isNull() ? null : objectMapper.treeToValue(state, AiShiftDialogueState.class);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private AiUnifiedChatResponse readResponse(String content) {
        try {
            var parsed = objectMapper.readTree(content);
            if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.remove("_pendingShift");
            }
            return objectMapper.treeToValue(parsed, AiUnifiedChatResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return null;
        }
    }

}
