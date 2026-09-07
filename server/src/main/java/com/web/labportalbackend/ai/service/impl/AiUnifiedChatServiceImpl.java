package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.client.AiGatewayRequest;
import com.web.labportalbackend.ai.client.AiToolPlanningClient;
import com.web.labportalbackend.ai.client.AiToolPlanningDecision;
import com.web.labportalbackend.ai.client.AiToolPlanningResponse;
import com.web.labportalbackend.ai.dto.request.AiAssistantChatRequest;
import com.web.labportalbackend.ai.dto.request.AiUnifiedChatRequest;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.dto.response.AiUnifiedChatResponse;
import com.web.labportalbackend.ai.dto.response.AiActionPreviewResponse;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.enums.AiUnifiedChatResponseType;
import com.web.labportalbackend.ai.service.AiAssistantGatewayService;
import com.web.labportalbackend.ai.service.AiActionSuggestionService;
import com.web.labportalbackend.ai.service.AiConversationHistoryService;
import com.web.labportalbackend.ai.service.AiShiftDialogueState;
import com.web.labportalbackend.ai.service.AiToolCandidate;
import com.web.labportalbackend.ai.service.AiToolCandidateCatalog;
import com.web.labportalbackend.ai.service.AiToolDefinition;
import com.web.labportalbackend.ai.service.AiToolRegistry;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.ai.service.AiUnifiedChatService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AiUnifiedChatServiceImpl implements AiUnifiedChatService {

    private static final String NO_AUTHORIZED_CAPABILITY =
            "I cannot find an available Lab Portal capability for this request.";
    private static final String INVALID_LAB_SHIFT_DRAFT =
            "Tôi chưa thể tạo bản xem trước an toàn. Vui lòng cung cấp ngày, giờ bắt đầu và giờ kết thúc rồi thử lại.";

    private final AiToolCandidateCatalog candidateCatalog;
    private final AiToolPlanningClient planningClient;
    private final AiToolRegistry toolRegistry;
    private final AiAssistantGatewayService assistantGatewayService;
    private final AiActionSuggestionService actionSuggestionService;
    private final AiConversationHistoryService conversationHistoryService;
    private final ObjectMapper objectMapper;
    private final AiShiftDialogueService shiftDialogueService;

    public AiUnifiedChatServiceImpl(AiToolCandidateCatalog candidateCatalog,
                                    AiToolPlanningClient planningClient,
                                    AiToolRegistry toolRegistry,
                                    AiAssistantGatewayService assistantGatewayService,
                                    AiActionSuggestionService actionSuggestionService,
                                    AiConversationHistoryService conversationHistoryService,
                                    ObjectMapper objectMapper,
                                    AiShiftDialogueService shiftDialogueService) {
        this.candidateCatalog = candidateCatalog;
        this.planningClient = planningClient;
        this.toolRegistry = toolRegistry;
        this.assistantGatewayService = assistantGatewayService;
        this.actionSuggestionService = actionSuggestionService;
        this.conversationHistoryService = conversationHistoryService;
        this.objectMapper = objectMapper;
        this.shiftDialogueService = shiftDialogueService;
    }

    @Override
    public AiUnifiedChatResponse chat(AiUnifiedChatRequest request, String requestId) {
        if (request == null || request.getInput() == null || request.getInput().isBlank()) {
            throw new IllegalArgumentException("Unified chat input is required");
        }
        AiConversationHistoryService.PreparedInput prepared = conversationHistoryService.prepareInput(
                request.getConversationId(), request.getInput());
        TurnState turn = new TurnState(prepared.pendingState());
        AiUnifiedChatResponse response = generateResponse(prepared.effectiveInput(), requestId, turn);
        return conversationHistoryService.saveTurn(prepared.conversationId(), request.getInput(), response, turn.pending);
    }

    private AiUnifiedChatResponse generateResponse(String input, String requestId, TurnState turn) {
        String normalizedRequestId = AiGatewayRequest.normalizeRequestId(requestId);
        List<AiToolCandidate> candidates = List.copyOf(candidateCatalog.candidates());
        if (candidates.isEmpty()) {
            return nonTool(AiUnifiedChatResponseType.REFUSED, NO_AUTHORIZED_CAPABILITY, 0, 0);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("input", input);
        ArrayNode candidateNodes = payload.putArray("candidates");
        candidates.forEach(candidate -> candidateNodes.add(candidate.toPlanningCandidate(objectMapper)));
        AiToolPlanningResponse planning = planningClient.plan(new AiGatewayRequest(payload, normalizedRequestId));
        if (planning.decision() == AiToolPlanningDecision.ANSWER) {
            return nonTool(AiUnifiedChatResponseType.ANSWER, planning.message(),
                    planning.promptTokens(), planning.completionTokens());
        }
        if (planning.decision() == AiToolPlanningDecision.CANCEL_PENDING) {
            if (turn.pending == null) {
                return nonTool(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED,
                        "Không có yêu cầu tạo ca đang chờ trong phiên này.", planning.promptTokens(), planning.completionTokens());
            }
            if (turn.pending.suggestionId() != null) actionSuggestionService.cancel(turn.pending.suggestionId());
            turn.pending = null;
            return nonTool(AiUnifiedChatResponseType.ANSWER, "Đã hủy yêu cầu tạo ca đang chờ.",
                    planning.promptTokens(), planning.completionTokens());
        }
        if (planning.decision() != AiToolPlanningDecision.TOOL_REQUEST) {
            return nonTool(planning.decision() == AiToolPlanningDecision.CLARIFICATION
                            ? AiUnifiedChatResponseType.CLARIFICATION_REQUIRED : AiUnifiedChatResponseType.REFUSED,
                    planning.message(), planning.promptTokens(), planning.completionTokens());
        }

        AiToolCandidate selected = candidates.stream()
                .filter(candidate -> matchesCanonicalToolRequest(candidate, planning.toolRequest()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AI planner returned a non-canonical tool request"));
        AiToolDefinition definition = toolRegistry.get(selected.toolId());
        if (definition == null || definition.capability().domain() != selected.assistantKey().domain()
                || !definition.schemaVersion().equals(selected.schemaVersion())) {
            throw new IllegalArgumentException("AI planner selected an unavailable tool");
        }

        AiAssistantChatRequest delegated = new AiAssistantChatRequest();
        delegated.setInput(input);
        delegated.setCapability(definition.capability());
        delegated.setResourceId(selected.resource().resourceId());
        delegated.setParentResourceId(selected.parentResource() == null
                ? null : selected.parentResource().resourceId());
        AiAssistantChatResponse answer = assistantGatewayService.chat(
                selected.assistantKey(), delegated, normalizedRequestId);
        if (definition.capability() == AiCapability.LAB_SHIFT_CREATE_DRAFT) {
            if (hasLabShiftKind(answer.answer(), "LAB_SHIFT_CREATE_INTERPRETATION")) {
                try {
                    var resolved = shiftDialogueService.resolve(selected.resource().resourceId(), answer.answer(), turn.pending);
                    if (resolved.state().labConfirmed() && turn.pending != null && turn.pending.suggestionId() != null) {
                        actionSuggestionService.cancel(turn.pending.suggestionId());
                    }
                    turn.pending = !resolved.state().labConfirmed() && turn.pending != null
                            && turn.pending.suggestionId() != null
                            ? resolved.state().withSuggestion(turn.pending.suggestionId()) : resolved.state();
                    if (resolved.question() != null) {
                        return new AiUnifiedChatResponse(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED,
                                answer.assistantKey(), resolved.question(),
                                Math.addExact(planning.promptTokens(), answer.promptTokens()),
                                Math.addExact(planning.completionTokens(), answer.completionTokens()), answer.citations());
                    }
                    answer = new AiAssistantChatResponse(answer.assistantKey(), resolved.draft().toString(),
                            answer.promptTokens(), answer.completionTokens(), answer.citations());
                } catch (AiSuggestionPayloadValidationException ignored) {
                    return nonTool(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED,
                            "Tôi chưa đọc được thông tin bổ sung. Bạn vui lòng diễn đạt lại; thông tin trước đó vẫn được giữ.",
                            Math.addExact(planning.promptTokens(), answer.promptTokens()),
                            Math.addExact(planning.completionTokens(), answer.completionTokens()));
                }
            }
            String clarification = labShiftClarification(answer.answer());
            if (clarification != null) {
                return new AiUnifiedChatResponse(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED,
                        answer.assistantKey(), clarification,
                        Math.addExact(planning.promptTokens(), answer.promptTokens()),
                        Math.addExact(planning.completionTokens(), answer.completionTokens()),
                        answer.citations());
            }
            if (!hasLabShiftKind(answer.answer(), "LAB_SHIFT_CREATE_DRAFT")) {
                return new AiUnifiedChatResponse(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED,
                        answer.assistantKey(), "Tôi chưa đọc được yêu cầu. Bạn vui lòng diễn đạt lại thông tin mới; dữ liệu đang chờ vẫn được giữ.",
                        Math.addExact(planning.promptTokens(), answer.promptTokens()),
                        Math.addExact(planning.completionTokens(), answer.completionTokens()),
                        answer.citations());
            }
            AiActionPreviewResponse preview;
            try {
                preview = actionSuggestionService.createLabShiftPreview(selected.resource().resourceId(), answer);
            } catch (AiSuggestionPayloadValidationException ignored) {
                return new AiUnifiedChatResponse(AiUnifiedChatResponseType.REFUSED,
                        answer.assistantKey(), INVALID_LAB_SHIFT_DRAFT,
                        Math.addExact(planning.promptTokens(), answer.promptTokens()),
                        Math.addExact(planning.completionTokens(), answer.completionTokens()),
                        answer.citations());
            }
            if (turn.pending != null) {
                turn.pending = turn.pending.withSuggestion(preview.suggestionId());
            }
            return new AiUnifiedChatResponse(null, AiUnifiedChatResponseType.ACTION_PREVIEW, answer.assistantKey(),
                    "Please review and confirm the proposed Lab time slot.",
                    Math.addExact(planning.promptTokens(), answer.promptTokens()),
                    Math.addExact(planning.completionTokens(), answer.completionTokens()), answer.citations(),
                    preview, null);
        }
        turn.pending = null;
        return new AiUnifiedChatResponse(AiUnifiedChatResponseType.ANSWER, answer.assistantKey(), answer.answer(),
                Math.addExact(planning.promptTokens(), answer.promptTokens()),
                Math.addExact(planning.completionTokens(), answer.completionTokens()), answer.citations());
    }

    private static final class TurnState {
        private AiShiftDialogueState pending;
        private TurnState(AiShiftDialogueState pending) { this.pending = pending; }
    }

    private static AiUnifiedChatResponse nonTool(AiUnifiedChatResponseType type,
                                                  String message,
                                                  int promptTokens,
                                                  int completionTokens) {
        return new AiUnifiedChatResponse(type, null, message, promptTokens, completionTokens, List.of());
    }

    private static boolean matchesCanonicalToolRequest(AiToolCandidate candidate, JsonNode request) {
        if (request == null || !request.isObject() || request.size() != 4
                || !matchesText(request, "assistantKey", candidate.assistantKey().name())
                || !matchesText(request, "schemaVersion", candidate.schemaVersion())
                || !matchesText(request, "toolId", candidate.toolId().value())) {
            return false;
        }
        JsonNode arguments = request.get("arguments");
        if (arguments == null || !arguments.isObject()
                || arguments.size() != (candidate.parentResource() == null ? 1 : 2)
                || !matchesResource(arguments.get("resource"), candidate.resource())) {
            return false;
        }
        return candidate.parentResource() == null
                ? !arguments.has("parentResource")
                : matchesResource(arguments.get("parentResource"), candidate.parentResource());
    }

    private static boolean matchesResource(JsonNode actual, AiToolCandidate.ResourceReference expected) {
        if (actual == null || !actual.isObject() || actual.size() != 2
                || !matchesText(actual, "resourceType", expected.resourceType().name())) {
            return false;
        }
        JsonNode resourceId = actual.get("resourceId");
        if (expected.resourceId() == null) {
            return resourceId != null && resourceId.isNull();
        }
        return resourceId != null && resourceId.isIntegralNumber()
                && resourceId.canConvertToLong() && resourceId.longValue() == expected.resourceId();
    }

    private static boolean matchesText(JsonNode object, String name, String expected) {
        JsonNode actual = object.get(name);
        return actual != null && actual.isTextual() && expected.equals(actual.textValue());
    }

    private String labShiftClarification(String answer) {
        if (!hasLabShiftKind(answer, "LAB_SHIFT_CREATE_CLARIFICATION")) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(answer);
            JsonNode question = parsed.get("question");
            return question != null && question.isTextual() && !question.textValue().isBlank()
                    ? question.textValue() : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private boolean hasLabShiftKind(String answer, String expectedKind) {
        try {
            JsonNode parsed = objectMapper.readTree(answer);
            return parsed != null && parsed.isObject()
                    && expectedKind.equals(parsed.path("kind").asText());
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }
}
