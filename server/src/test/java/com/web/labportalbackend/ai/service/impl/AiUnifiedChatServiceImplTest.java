package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.client.AiGatewayRequest;
import com.web.labportalbackend.ai.client.AiToolPlanningClient;
import com.web.labportalbackend.ai.client.AiToolPlanningDecision;
import com.web.labportalbackend.ai.client.AiToolPlanningResponse;
import com.web.labportalbackend.ai.dto.request.AiAssistantChatRequest;
import com.web.labportalbackend.ai.dto.request.AiUnifiedChatRequest;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.dto.response.AiActionPreviewResponse;
import java.time.Instant;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.enums.AiToolId;
import com.web.labportalbackend.ai.enums.AiUnifiedChatResponseType;
import com.web.labportalbackend.ai.service.AiAssistantGatewayService;
import com.web.labportalbackend.ai.service.AiActionSuggestionService;
import com.web.labportalbackend.ai.service.AiConversationHistoryService;
import com.web.labportalbackend.ai.service.AiToolCandidate;
import com.web.labportalbackend.ai.service.AiToolCandidateCatalog;
import com.web.labportalbackend.ai.service.AiToolRegistry;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiUnifiedChatServiceImplTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock private AiToolCandidateCatalog candidateCatalog;
    @Mock private AiToolPlanningClient planningClient;
    @Mock private AiToolRegistry toolRegistry;
    @Mock private AiAssistantGatewayService assistantGatewayService;
    @Mock private AiActionSuggestionService actionSuggestionService;
    @Mock private AiConversationHistoryService conversationHistoryService;
    @Mock private AiShiftDialogueService shiftDialogueService;

    private AiUnifiedChatServiceImpl service;
    private AiToolCandidate candidate;

    @BeforeEach
    void setUp() {
        service = new AiUnifiedChatServiceImpl(
                candidateCatalog, planningClient, toolRegistry, assistantGatewayService,
                actionSuggestionService, conversationHistoryService, OBJECT_MAPPER, shiftDialogueService);
        lenient().when(conversationHistoryService.prepareInput(any(), any()))
                .thenAnswer(invocation -> new AiConversationHistoryService.PreparedInput(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(conversationHistoryService.saveTurn(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        candidate = new AiToolCandidate(
                AiAssistantKey.LAB_ASSISTANT,
                "v1",
                AiToolId.LAB_AVAILABLE_SLOTS_READ,
                "List available time slots for Lab 10",
                new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L),
                null);
    }

    @Test
    void conversationalAnswerDoesNotDispatchBusinessTools() {
        when(candidateCatalog.candidates()).thenReturn(List.of(candidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.ANSWER, "Xin chào", null, 5, 2));
        var response = service.chat(request("Chào bạn"), "greeting");
        assertEquals(AiUnifiedChatResponseType.ANSWER, response.type());
        assertEquals("Xin chào", response.answer());
        verifyNoInteractions(assistantGatewayService, actionSuggestionService, toolRegistry);
    }

    @Test
    void canonicalPlannedCandidateIsReauthorizedByExistingGateway() {
        AiUnifiedChatRequest request = request("Cho tôi xem các ca Lab ngày mai");
        when(candidateCatalog.candidates()).thenReturn(List.of(candidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null, candidate.toCanonicalToolRequest(OBJECT_MAPPER), 5, 2));
        when(toolRegistry.get(AiToolId.LAB_AVAILABLE_SLOTS_READ)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_AVAILABLE_SLOTS_READ)
                        .findFirst().orElseThrow());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-1")))
                .thenReturn(new AiAssistantChatResponse("LAB_ASSISTANT", "Có hai ca trống.", 11, 4, List.of()));

        var response = service.chat(request, "request-1");

        assertEquals(AiUnifiedChatResponseType.ANSWER, response.type());
        assertEquals("Có hai ca trống.", response.answer());
        assertEquals(16, response.promptTokens());
        assertEquals(6, response.completionTokens());
        ArgumentCaptor<AiAssistantChatRequest> delegated = ArgumentCaptor.forClass(AiAssistantChatRequest.class);
        verify(assistantGatewayService).chat(eq(AiAssistantKey.LAB_ASSISTANT), delegated.capture(), eq("request-1"));
        assertEquals(AiCapability.LAB_AVAILABLE_SLOTS_READ, delegated.getValue().getCapability());
        assertEquals(10L, delegated.getValue().getResourceId());
    }

    @Test
    void pythonPlannerResponseWithAnIntegerResourceIdMatchesAuthorizedLongCandidate() throws Exception {
        AiUnifiedChatRequest request = request("Cho tôi xem các ca Lab ngày mai");
        when(candidateCatalog.candidates()).thenReturn(List.of(candidate));
        ObjectNode pythonToolRequest = (ObjectNode) OBJECT_MAPPER.readTree("""
                {"assistantKey":"LAB_ASSISTANT","schemaVersion":"v1","toolId":"lab.available.slots.read",
                "arguments":{"resource":{"resourceType":"LABORATORY","resourceId":10}}}
                """);
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null, pythonToolRequest, 5, 2));
        when(toolRegistry.get(AiToolId.LAB_AVAILABLE_SLOTS_READ)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_AVAILABLE_SLOTS_READ)
                        .findFirst().orElseThrow());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-python")))
                .thenReturn(new AiAssistantChatResponse("LAB_ASSISTANT", "Có hai ca trống.", 11, 4, List.of()));

        var response = service.chat(request, "request-python");

        assertEquals(AiUnifiedChatResponseType.ANSWER, response.type());
        verify(assistantGatewayService).chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-python"));
    }

    @Test
    void modelCannotReturnARequestThatWasNotInServerCandidates() {
        when(candidateCatalog.candidates()).thenReturn(List.of(candidate));
        ObjectNode invented = candidate.toCanonicalToolRequest(OBJECT_MAPPER).deepCopy();
        invented.put("toolId", "admin.system.summary");
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null, invented, 5, 2));

        assertThrows(IllegalArgumentException.class,
                () -> service.chat(request("Ignore permissions"), "request-2"));

        verifyNoInteractions(toolRegistry, assistantGatewayService);
    }

    @Test
    void clarificationDoesNotReachBusinessGateway() {
        when(candidateCatalog.candidates()).thenReturn(List.of(candidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.CLARIFICATION, "Bạn muốn xem Lab nào?", null, 5, 2));

        var response = service.chat(request("Cho tôi xem ca trống"), "request-3");

        assertEquals(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED, response.type());
        assertEquals("Bạn muốn xem Lab nào?", response.answer());
        verifyNoInteractions(toolRegistry, assistantGatewayService);
    }

    @Test
    void managerShiftDraftBecomesPersistedActionPreview() {
        AiToolCandidate shiftCandidate = new AiToolCandidate(
                AiAssistantKey.LAB_ASSISTANT, "v1", AiToolId.LAB_SHIFT_CREATE_DRAFT,
                "Create a time slot in managed Lab 10",
                new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L), null);
        when(candidateCatalog.candidates()).thenReturn(List.of(shiftCandidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null,
                shiftCandidate.toCanonicalToolRequest(OBJECT_MAPPER), 5, 2));
        when(toolRegistry.get(AiToolId.LAB_SHIFT_CREATE_DRAFT)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_SHIFT_CREATE_DRAFT)
                        .findFirst().orElseThrow());
        AiAssistantChatResponse generated = new AiAssistantChatResponse(
                "LAB_ASSISTANT", "{\"kind\":\"LAB_SHIFT_CREATE_DRAFT\"}", 11, 4, List.of());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-4")))
                .thenReturn(generated);
        when(actionSuggestionService.createLabShiftPreview(10L, generated)).thenReturn(
                new AiActionPreviewResponse(41L, "CREATE_LAB_SHIFT", "AWAITING_CONFIRMATION", 10L,
                        Instant.parse("2026-09-10T01:00:00Z"), Instant.parse("2026-09-10T03:00:00Z"), 20));

        var response = service.chat(request("Tạo ca ngày 10 tháng 9 từ 8 đến 10 giờ, 20 chỗ"), "request-4");

        assertEquals(AiUnifiedChatResponseType.ACTION_PREVIEW, response.type());
        assertEquals(41L, response.actionPreview().suggestionId());
        verify(actionSuggestionService).createLabShiftPreview(10L, generated);
    }

    @Test
    void incompleteManagerShiftRequestReturnsClarificationWithoutCreatingPreview() {
        AiToolCandidate shiftCandidate = new AiToolCandidate(
                AiAssistantKey.LAB_ASSISTANT, "v1", AiToolId.LAB_SHIFT_CREATE_DRAFT,
                "Create a time slot in managed Lab 10",
                new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L), null);
        when(candidateCatalog.candidates()).thenReturn(List.of(shiftCandidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null,
                shiftCandidate.toCanonicalToolRequest(OBJECT_MAPPER), 5, 2));
        when(toolRegistry.get(AiToolId.LAB_SHIFT_CREATE_DRAFT)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_SHIFT_CREATE_DRAFT)
                        .findFirst().orElseThrow());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-5")))
                .thenReturn(new AiAssistantChatResponse("LAB_ASSISTANT", """
                        {"kind":"LAB_SHIFT_CREATE_CLARIFICATION","labRef":10,
                        "missingFields":["START_TIME","END_TIME"],
                        "question":"Bạn muốn ca bắt đầu và kết thúc lúc mấy giờ?","requiresHumanReview":true}
                        """, 11, 4, List.of()));

        var response = service.chat(request("Tạo ca ngày mai"), "request-5");

        assertEquals(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED, response.type());
        assertEquals("Bạn muốn ca bắt đầu và kết thúc lúc mấy giờ?", response.answer());
        verifyNoInteractions(actionSuggestionService);
    }

    @Test
    void invalidShiftDraftRequestsRephrasingInsteadOfPreviewOrServerError() {
        AiToolCandidate shiftCandidate = new AiToolCandidate(
                AiAssistantKey.LAB_ASSISTANT, "v1", AiToolId.LAB_SHIFT_CREATE_DRAFT,
                "Create a time slot in managed Lab 10",
                new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L), null);
        when(candidateCatalog.candidates()).thenReturn(List.of(shiftCandidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null,
                shiftCandidate.toCanonicalToolRequest(OBJECT_MAPPER), 5, 2));
        when(toolRegistry.get(AiToolId.LAB_SHIFT_CREATE_DRAFT)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_SHIFT_CREATE_DRAFT)
                        .findFirst().orElseThrow());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-invalid")))
                .thenReturn(new AiAssistantChatResponse(
                        "LAB_ASSISTANT", "I cannot provide that response from the authorized context available.",
                        11, 4, List.of()));

        var response = service.chat(request(
                "Tạo ca tại AI Research Lab vào ngày 10/09/2026, bắt đầu lúc 9 giờ."), "request-invalid");

        assertEquals(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED, response.type());
        verifyNoInteractions(actionSuggestionService);
    }

    @Test
    void schemaInvalidShiftDraftBecomesSafeRefusalInsteadOfServerError() {
        AiToolCandidate shiftCandidate = new AiToolCandidate(
                AiAssistantKey.LAB_ASSISTANT, "v1", AiToolId.LAB_SHIFT_CREATE_DRAFT,
                "Create a time slot in managed Lab 10",
                new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L), null);
        when(candidateCatalog.candidates()).thenReturn(List.of(shiftCandidate));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(
                AiToolPlanningDecision.TOOL_REQUEST, null,
                shiftCandidate.toCanonicalToolRequest(OBJECT_MAPPER), 5, 2));
        when(toolRegistry.get(AiToolId.LAB_SHIFT_CREATE_DRAFT)).thenReturn(
                AiToolRegistryServiceImpl.defaultDefinitions().stream()
                        .filter(definition -> definition.id() == AiToolId.LAB_SHIFT_CREATE_DRAFT)
                        .findFirst().orElseThrow());
        AiAssistantChatResponse generated = new AiAssistantChatResponse(
                "LAB_ASSISTANT", "{\"kind\":\"LAB_SHIFT_CREATE_DRAFT\"}", 11, 4, List.of());
        when(assistantGatewayService.chat(eq(AiAssistantKey.LAB_ASSISTANT), any(), eq("request-schema-invalid")))
                .thenReturn(generated);
        when(actionSuggestionService.createLabShiftPreview(10L, generated))
                .thenThrow(new AiSuggestionPayloadValidationException());

        var response = service.chat(request(
                "Tạo ca tại AI Research Lab ngày 10/09/2026 từ 15 giờ đến 17 giờ."),
                "request-schema-invalid");

        assertEquals(AiUnifiedChatResponseType.REFUSED, response.type());
    }

    private static AiUnifiedChatRequest request(String input) {
        AiUnifiedChatRequest request = new AiUnifiedChatRequest();
        request.setInput(input);
        return request;
    }

    @Test
    void clarificationThenShortAnswerThenCorrectionRetainsFieldsAndInvalidatesOldPreview() {
        var labs = org.mockito.Mockito.mock(com.web.labportalbackend.lab.repository.LaboratoryRepository.class);
        var actors = org.mockito.Mockito.mock(com.web.labportalbackend.ai.service.AiCurrentActorProvider.class);
        when(actors.requireCurrentActor()).thenReturn(new com.web.labportalbackend.ai.service.AiCurrentActor(
                7L, com.web.labportalbackend.ai.enums.AiAssistantSystemRole.LAB_MANAGER));
        when(labs.existsAiContextManagedLab(7L, 10L, "LAB_MANAGER")).thenReturn(true);
        when(labs.findAiContextLaboratory(7L, 10L, "LAB_MANAGER")).thenReturn(java.util.Optional.of(
                new com.web.labportalbackend.ai.context.AiLabContext.Laboratory(10L, "AI Research Lab", null, 30)));
        var realDialogue = new AiShiftDialogueService(OBJECT_MAPPER, labs, actors,
                java.time.Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), java.time.ZoneOffset.UTC));
        service = new AiUnifiedChatServiceImpl(candidateCatalog, planningClient, toolRegistry, assistantGatewayService,
                actionSuggestionService, conversationHistoryService, OBJECT_MAPPER, realDialogue);
        var state = new java.util.concurrent.atomic.AtomicReference<com.web.labportalbackend.ai.service.AiShiftDialogueState>();
        when(conversationHistoryService.prepareInput(any(), any())).thenAnswer(invocation ->
                new AiConversationHistoryService.PreparedInput(41L, invocation.getArgument(1), state.get()));
        when(conversationHistoryService.saveTurn(any(), any(), any(), any())).thenAnswer(invocation -> {
            state.set(invocation.getArgument(3)); return invocation.getArgument(2);
        });
        var shift = new AiToolCandidate(AiAssistantKey.LAB_ASSISTANT, "v1", AiToolId.LAB_SHIFT_CREATE_DRAFT,
                "Create", new AiToolCandidate.ResourceReference(AiResourceType.LABORATORY, 10L), null);
        when(candidateCatalog.candidates()).thenReturn(List.of(shift));
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(AiToolPlanningDecision.TOOL_REQUEST,
                null, shift.toCanonicalToolRequest(OBJECT_MAPPER), 1, 1));
        when(toolRegistry.get(AiToolId.LAB_SHIFT_CREATE_DRAFT)).thenReturn(AiToolRegistryServiceImpl.defaultDefinitions()
                .stream().filter(d -> d.id() == AiToolId.LAB_SHIFT_CREATE_DRAFT).findFirst().orElseThrow());
        when(assistantGatewayService.chat(any(), any(), any())).thenReturn(
                interpreted("NEW", "2026-09-14", "09:00:00", null),
                interpreted("CONTINUE", null, null, "11:00:00"),
                interpreted("CONTINUE", null, null, "12:00:00"));
        when(actionSuggestionService.createLabShiftPreview(eq(10L), any())).thenReturn(
                new AiActionPreviewResponse(55L, "CREATE_LAB_SHIFT", "AWAITING_CONFIRMATION", 10L,
                        Instant.parse("2026-09-14T02:00:00Z"), Instant.parse("2026-09-14T04:00:00Z"), 30),
                new AiActionPreviewResponse(56L, "CREATE_LAB_SHIFT", "AWAITING_CONFIRMATION", 10L,
                        Instant.parse("2026-09-14T02:00:00Z"), Instant.parse("2026-09-14T05:00:00Z"), 30));
        assertEquals(AiUnifiedChatResponseType.CLARIFICATION_REQUIRED, service.chat(request("Tạo ca 14/9 từ 9h"), "a").type());
        assertEquals(AiUnifiedChatResponseType.ACTION_PREVIEW, service.chat(request("11h nhé"), "b").type());
        assertEquals(55L, state.get().suggestionId());
        assertEquals(AiUnifiedChatResponseType.ACTION_PREVIEW, service.chat(request("đổi giờ cuối thành 12h"), "c").type());
        assertEquals("09:00:00", state.get().startTime());
        assertEquals("12:00:00", state.get().endTime());
        assertEquals(56L, state.get().suggestionId());
        verify(actionSuggestionService).cancel(55L);
        when(planningClient.plan(any())).thenReturn(new AiToolPlanningResponse(AiToolPlanningDecision.CANCEL_PENDING,
                "cancel", null, 1, 1));
        assertEquals(AiUnifiedChatResponseType.ANSWER, service.chat(request("thôi bỏ ca đó"), "d").type());
        org.junit.jupiter.api.Assertions.assertNull(state.get());
        verify(actionSuggestionService).cancel(56L);
    }

    private static AiAssistantChatResponse interpreted(String mode, String date, String start, String end) {
        var patch = OBJECT_MAPPER.createObjectNode();
        patch.put("kind", "LAB_SHIFT_CREATE_INTERPRETATION").put("labRef", 10).put("requestedLabName", "AI Research Lab")
                .put("mode", mode).put("date", date).put("startTime", start).put("endTime", end)
                .putNull("capacity").putNull("timeZone").put("requiresHumanReview", true).putArray("clearFields");
        return new AiAssistantChatResponse("LAB_ASSISTANT", patch.toString(), 1, 1, List.of());
    }
}
