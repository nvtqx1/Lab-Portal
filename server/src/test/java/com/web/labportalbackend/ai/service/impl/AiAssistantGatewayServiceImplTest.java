package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.client.AiChatResponse;
import com.web.labportalbackend.ai.client.AiGatewayClient;
import com.web.labportalbackend.ai.client.AiGatewayRequest;
import com.web.labportalbackend.ai.context.AiAdminContext;
import com.web.labportalbackend.ai.context.AiAuthorizedContext;
import com.web.labportalbackend.ai.context.AiBoundedList;
import com.web.labportalbackend.ai.context.AiContextBuildRequest;
import com.web.labportalbackend.ai.context.AiContextFacade;
import com.web.labportalbackend.ai.context.AiContextReadDeniedException;
import com.web.labportalbackend.ai.context.AiDomainContext;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.ai.context.AiPythonAuthorizedContext;
import com.web.labportalbackend.ai.context.AiResearchAssistantContext;
import com.web.labportalbackend.ai.dto.request.AiAssistantChatRequest;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.enums.AiActionRiskBoundary;
import com.web.labportalbackend.ai.enums.AiAssistantDomain;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiAssistantToolGroup;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.enums.AiCapabilityDecisionReason;
import com.web.labportalbackend.ai.enums.AiCapabilityDenialReason;
import com.web.labportalbackend.ai.enums.AiQuotaPolicyReference;
import com.web.labportalbackend.ai.enums.AiResourceScope;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.rag.service.AiAuthorizedRetrieval;
import com.web.labportalbackend.ai.rag.service.AiRagRetrievalService;
import com.web.labportalbackend.ai.service.AiAssistantAvailability;
import com.web.labportalbackend.ai.service.AiAssistantAvailabilityException;
import com.web.labportalbackend.ai.service.AiAssistantAvailabilityFailure;
import com.web.labportalbackend.ai.service.AiAssistantAvailabilityService;
import com.web.labportalbackend.ai.service.AiAssistantAuditEvent;
import com.web.labportalbackend.ai.service.AiAssistantProfile;
import com.web.labportalbackend.ai.service.AiAuditExecutionResult;
import com.web.labportalbackend.ai.service.AiAuditFailureCode;
import com.web.labportalbackend.ai.service.AiAuditGateStatus;
import com.web.labportalbackend.ai.service.AiAuditUsageService;
import com.web.labportalbackend.ai.service.AiAuthorizedToolPolicy;
import com.web.labportalbackend.ai.service.AiCapabilityDecision;
import com.web.labportalbackend.ai.service.AiCapabilityDeniedException;
import com.web.labportalbackend.ai.service.AiCapabilityRequest;
import com.web.labportalbackend.ai.service.AiResearchContext;
import com.web.labportalbackend.research.enums.ProjectStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AiAssistantGatewayServiceImplTest {

    private final AiAssistantAvailabilityService availabilityService = mock(AiAssistantAvailabilityService.class);
    private final AiContextFacade contextFacade = mock(AiContextFacade.class);
    private final AiGatewayClient gatewayClient = mock(AiGatewayClient.class);
    private final AiRagRetrievalService ragRetrievalService = mock(AiRagRetrievalService.class,
            invocation -> AiAuthorizedRetrieval.empty(
                    ((AiAssistantProfile) invocation.getArgument(0)).retrievalNamespace()));
    private final AiAuditUsageService auditUsageService = mock(AiAuditUsageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AiAssistantGatewayServiceImpl service = new AiAssistantGatewayServiceImpl(
            availabilityService, contextFacade, gatewayClient, objectMapper, auditUsageService, ragRetrievalService);

    @Test
    void parentResourceTypeRemainsExplicitlyNullWithProductionJacksonInclusion() {
        ObjectMapper productionMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        AiPythonAuthorizedContext.AllowedTool tool = new AiPythonAuthorizedContext.AllowedTool(
                "research.group.summary", "v1", AiResourceType.GROUP, null,
                List.of("resource"), AiActionRiskBoundary.READ_ONLY);

        JsonNode serialized = productionMapper.valueToTree(tool);

        assertTrue(serialized.has("parentResourceType"));
        assertTrue(serialized.path("parentResourceType").isNull());
    }

    @Test
    void labDraftContextPreservesContractualNullFieldsWithProductionJacksonInclusion() {
        ObjectMapper productionMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        AiLabContext context = new AiLabContext(
                new AiLabContext.Laboratory(10L, "Authorized Lab", null),
                null, null, null, null, null, true, "DRAFT_ONLY_NO_SHIFT_WRITE");

        JsonNode serialized = productionMapper.valueToTree(context);

        assertEquals(Set.of("laboratory", "slot", "booking", "managedSummary", "labPolicySnapshot",
                "checkinPolicySnapshot", "draftOnly", "policyOrDraftEligibilityLabel"), fieldSet(serialized));
        assertTrue(serialized.path("laboratory").has("status"));
        assertTrue(serialized.path("laboratory").path("status").isNull());
        assertTrue(serialized.path("slot").isNull());
        assertTrue(serialized.path("booking").isNull());
        assertTrue(serialized.path("managedSummary").isNull());
        assertTrue(serialized.path("labPolicySnapshot").isNull());
        assertTrue(serialized.path("checkinPolicySnapshot").isNull());
    }

    @Test
    void adminSystemSummaryProjectsOnlyBoundedAuthorizedContextToPython() {
        AiAssistantProfile profile = profile(AiAssistantKey.ADMIN_ASSISTANT);
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile, 1L, AiAssistantSystemRole.ADMIN);
        AiAssistantChatRequest publicRequest = request(AiCapability.ADMIN_SYSTEM_SUMMARY, null, null);
        AiCapabilityRequest capabilityRequest = capabilityRequest(
                AiAssistantKey.ADMIN_ASSISTANT, 1L, AiCapability.ADMIN_SYSTEM_SUMMARY, null, null);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.ADMIN_ASSISTANT)).thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(context(capabilityRequest));
        when(gatewayClient.chat(any())).thenReturn(new AiChatResponse(
                AiAssistantKey.ADMIN_ASSISTANT.name(), "Bounded summary", 3, 2, Map.of()));

        service.chat(AiAssistantKey.ADMIN_ASSISTANT, publicRequest, "request-admin");

        ArgumentCaptor<AiGatewayRequest> gatewayRequest = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gatewayClient).chat(gatewayRequest.capture());
        JsonNode authorized = gatewayRequest.getValue().payload().path("authorizedContext");
        assertEquals("ADMIN", authorized.path("domain").asText());
        assertEquals("admin.system.summary", authorized.path("allowedTools").get(0).path("toolId").asText());
        assertEquals("SYSTEM", authorized.path("resources").get(0).path("resourceType").asText());
        assertTrue(authorized.path("resources").get(0).path("resourceId").isNull());
        assertEquals(8L, authorized.path("context").path("systemSummary").path("activeUserCount").asLong());
        assertEquals(10L, authorized.path("context").path("systemSummary").path("registeredUserCount").asLong());
        assertTrue(authorized.path("context").path("targetUser").isNull());
        assertEquals(0, authorized.path("context").path("auditBuckets").path("items").size());
    }

    @Test
    void authorizedContextToolsAndResourcesAreProjectedBeforeCallingPython() throws Exception {
        AiAssistantProfile profile = profile(AiAssistantKey.LAB_ASSISTANT);
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile, 7L, AiAssistantSystemRole.STUDENT);
        AiAssistantChatRequest publicRequest = request(AiCapability.LAB_SLOT_READ, 17L, null);
        AiCapabilityRequest capabilityRequest = capabilityRequest(
                AiAssistantKey.LAB_ASSISTANT, 7L, AiCapability.LAB_SLOT_READ, 17L, null);
        AiAuthorizedContext authorizedContext = context(capabilityRequest);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT)).thenReturn(availability);
        when(contextFacade.build(new AiContextBuildRequest(capabilityRequest, "request-123")))
                .thenReturn(authorizedContext);
        doReturn(new AiAuthorizedRetrieval("lab-knowledge", List.of(
                new AiAuthorizedRetrieval.Chunk(91L, "lab-policy", 3, 2, 4, "POLICY",
                        "Authorized safety policy", false))))
                .when(ragRetrievalService).retrieve(profile, 7L, AiAssistantSystemRole.STUDENT,
                        authorizedContext, "Summarize what I may access.");
        when(gatewayClient.chat(any())).thenReturn(new AiChatResponse(
                AiAssistantKey.LAB_ASSISTANT.name(), "Safe answer", 12, 7,
                Map.of("citations", objectMapper.valueToTree(List.of(Map.of("documentId", 999L))))));

        AiAssistantChatResponse response = service.chat(
                AiAssistantKey.LAB_ASSISTANT, publicRequest, " request-123 ");

        ArgumentCaptor<AiGatewayRequest> gatewayRequest = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gatewayClient).chat(gatewayRequest.capture());
        ArgumentCaptor<AiAssistantAuditEvent> auditEvent = ArgumentCaptor.forClass(AiAssistantAuditEvent.class);
        InOrder invocationOrder = inOrder(availabilityService, contextFacade, gatewayClient, auditUsageService);
        invocationOrder.verify(availabilityService).requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT);
        invocationOrder.verify(contextFacade).build(new AiContextBuildRequest(capabilityRequest, "request-123"));
        invocationOrder.verify(gatewayClient).chat(any());
        invocationOrder.verify(auditUsageService).recordAssistantRequest(auditEvent.capture());

        JsonNode serialized = objectMapper.readTree(objectMapper.writeValueAsBytes(gatewayRequest.getValue().payload()));
        assertEquals(Set.of("assistantKey", "input", "authorizedContext"), fieldSet(serialized));
        assertEquals("LAB_ASSISTANT", serialized.path("assistantKey").asText());
        assertEquals("Summarize what I may access.", serialized.path("input").asText());
        JsonNode pythonContext = serialized.path("authorizedContext");
        assertEquals(Set.of("domain", "contextVersion", "context", "allowedTools", "resources",
                        "authorizedRetrieval"),
                fieldSet(pythonContext));
        assertEquals("LAB", pythonContext.path("domain").asText());
        assertEquals("P5A-T5-v1", pythonContext.path("contextVersion").asText());
        assertEquals(1, pythonContext.path("allowedTools").size());
        assertEquals("lab.slot.read", pythonContext.path("allowedTools").get(0).path("toolId").asText());
        assertEquals("v1", pythonContext.path("allowedTools").get(0).path("schemaVersion").asText());
        assertEquals("TIME_SLOT", pythonContext.path("resources").get(0).path("resourceType").asText());
        assertEquals(17L, pythonContext.path("resources").get(0).path("resourceId").asLong());
        assertEquals(1, pythonContext.path("resources").size());
        assertEquals("lab-knowledge", pythonContext.path("authorizedRetrieval").path("namespace").asText());
        assertEquals(1, pythonContext.path("authorizedRetrieval").path("chunks").size());
        assertFalse(serialized.toString().contains("STUDENT"));
        assertFalse(serialized.toString().contains("userJwt"));
        assertFalse(serialized.toString().contains("Authorization"));
        assertFalse(serialized.toString().contains("effectiveScope"));
        assertEquals("request-123", gatewayRequest.getValue().requestId());
        assertEquals("LAB_ASSISTANT", response.assistantKey());
        assertEquals(1, response.citations().size());
        assertEquals(91L, response.citations().getFirst().documentId());
        assertEquals("lab-policy", response.citations().getFirst().resourceId());
        assertEquals(3, response.citations().getFirst().version());
        assertEquals(2, response.citations().getFirst().chunkIndex());
        assertEquals(4, response.citations().getFirst().pageNumber());
        assertEquals("POLICY", response.citations().getFirst().sourceType());
        assertEquals(7L, auditEvent.getValue().actorId());
        assertEquals(AiAssistantKey.LAB_ASSISTANT, auditEvent.getValue().assistant());
        assertEquals(AiCapability.LAB_SLOT_READ, auditEvent.getValue().action());
        assertEquals(AiResourceType.TIME_SLOT, auditEvent.getValue().resourceType());
        assertEquals(17L, auditEvent.getValue().resourceId());
        assertEquals("model", auditEvent.getValue().modelVersion());
        assertNull(auditEvent.getValue().adapterVersion());
        assertEquals("prompt-v1", auditEvent.getValue().promptVersion());
        assertEquals("request-123", auditEvent.getValue().requestId());
        assertEquals(AiAuditGateStatus.NOT_REQUIRED, auditEvent.getValue().gateStatus());
        assertEquals(AiAuditExecutionResult.SUCCEEDED, auditEvent.getValue().executionResult());
        assertNull(auditEvent.getValue().failureCode());
        assertEquals(12, auditEvent.getValue().promptTokens());
        assertEquals(7, auditEvent.getValue().completionTokens());
        assertTrue(auditEvent.getValue().consumesUsage());
    }

    @Test
    void labShiftDraftReceivesTrustedRequestTimeWithoutChangingThePythonContract() {
        AiAssistantProfile profile = profile(AiAssistantKey.LAB_ASSISTANT);
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile, 7L, AiAssistantSystemRole.LAB_MANAGER);
        AiAssistantChatRequest publicRequest = request(AiCapability.LAB_SHIFT_CREATE_DRAFT, 10L, null);
        publicRequest.setInput("Create a slot tomorrow from 15:00 to 17:00 in Asia/Ho_Chi_Minh.");
        AiCapabilityRequest capabilityRequest = capabilityRequest(
                AiAssistantKey.LAB_ASSISTANT, 7L, AiCapability.LAB_SHIFT_CREATE_DRAFT, 10L, null);
        AiAuthorizedContext authorizedContext = context(capabilityRequest);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT)).thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(authorizedContext);
        when(gatewayClient.chat(any())).thenReturn(new AiChatResponse(
                AiAssistantKey.LAB_ASSISTANT.name(), "Draft", 12, 7, Map.of()));

        service.chat(AiAssistantKey.LAB_ASSISTANT, publicRequest, "request-relative-date");

        ArgumentCaptor<AiGatewayRequest> gatewayRequest = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gatewayClient).chat(gatewayRequest.capture());
        JsonNode payload = gatewayRequest.getValue().payload();
        assertEquals(Set.of("assistantKey", "input", "authorizedContext"), fieldSet(payload));
        assertTrue(payload.path("input").asText().contains("requestTimeUtc=2026-01-01T00:00:00Z"));
        assertTrue(payload.path("input").asText().contains("defaultTimezone=Asia/Ho_Chi_Minh"));
        assertTrue(payload.path("input").asText().contains("User request: Create a slot tomorrow"));
        assertFalse(payload.path("authorizedContext").has("requestTime"));
    }

    @Test
    void parentResourceIsDerivedFromAuthorizedDecision() {
        AiAssistantProfile profile = profile(AiAssistantKey.RESEARCH_ASSISTANT);
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile, 7L, AiAssistantSystemRole.STUDENT);
        AiAssistantChatRequest publicRequest = request(
                AiCapability.RESEARCH_TASK_PROPOSAL_DRAFT, 30L, 20L);
        AiCapabilityRequest capabilityRequest = capabilityRequest(
                AiAssistantKey.RESEARCH_ASSISTANT, 7L,
                AiCapability.RESEARCH_TASK_PROPOSAL_DRAFT, 30L, 20L);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.RESEARCH_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(context(capabilityRequest));
        when(gatewayClient.chat(any())).thenReturn(new AiChatResponse(
                AiAssistantKey.RESEARCH_ASSISTANT.name(), "Draft", 1, 1, Map.of()));

        service.chat(AiAssistantKey.RESEARCH_ASSISTANT, publicRequest, "request-parent");

        ArgumentCaptor<AiGatewayRequest> gatewayRequest = ArgumentCaptor.forClass(AiGatewayRequest.class);
        verify(gatewayClient).chat(gatewayRequest.capture());
        JsonNode resources = gatewayRequest.getValue().payload().path("authorizedContext").path("resources");
        assertEquals(2, resources.size());
        assertEquals("GROUP", resources.get(0).path("resourceType").asText());
        assertEquals(30L, resources.get(0).path("resourceId").asLong());
        assertEquals("PROJECT", resources.get(1).path("resourceType").asText());
        assertEquals(20L, resources.get(1).path("resourceId").asLong());
    }

    static Stream<Arguments> availabilityDenials() {
        return Stream.of(
                Arguments.of(AiAssistantAvailabilityFailure.ASSISTANT_UNAVAILABLE),
                Arguments.of(AiAssistantAvailabilityFailure.ROLE_NOT_ALLOWED),
                Arguments.of(AiAssistantAvailabilityFailure.CONFIGURATION_UNAVAILABLE),
                Arguments.of(AiAssistantAvailabilityFailure.QUOTA_EXCEEDED));
    }

    @ParameterizedTest
    @MethodSource("availabilityDenials")
    void deniedAvailabilityNeverInvokesCapabilityContextOrClient(AiAssistantAvailabilityFailure failure) {
        when(availabilityService.requireAvailableForActor(AiAssistantKey.RESEARCH_ASSISTANT))
                .thenThrow(new AiAssistantAvailabilityException(failure));

        AiAssistantAvailabilityException exception = assertThrows(AiAssistantAvailabilityException.class,
                () -> service.chat(AiAssistantKey.RESEARCH_ASSISTANT,
                        request(AiCapability.RESEARCH_GROUP_SUMMARY, 30L, null), "request-denied"));

        assertEquals(failure, exception.failure());
        verifyNoInteractions(contextFacade, ragRetrievalService, gatewayClient);
        ArgumentCaptor<AiAssistantAuditEvent> auditEvent = ArgumentCaptor.forClass(AiAssistantAuditEvent.class);
        verify(auditUsageService).recordAssistantRequest(auditEvent.capture());
        assertNull(auditEvent.getValue().assistant());
        assertEquals(AiAuditExecutionResult.DENIED, auditEvent.getValue().executionResult());
        assertEquals(AiAuditFailureCode.valueOf(failure.name()), auditEvent.getValue().failureCode());
        assertNull(auditEvent.getValue().promptTokens());
        assertFalse(auditEvent.getValue().consumesUsage());
    }

    @Test
    void capabilityDenialNeverInvokesPython() {
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile(AiAssistantKey.RESEARCH_ASSISTANT), 7L, AiAssistantSystemRole.STUDENT);
        AiCapabilityDecision denied = new AiCapabilityDecision(false, null, null,
                AiAssistantKey.RESEARCH_ASSISTANT, AiAssistantDomain.RESEARCH,
                AiCapability.RESEARCH_GROUP_SUMMARY, null,
                AiCapabilityDecisionReason.DENIED_BY_RESOURCE_POLICY,
                AiCapabilityDenialReason.NOT_GROUP_MEMBER, AiActionRiskBoundary.READ_ONLY,
                Set.of(), null);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.RESEARCH_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenThrow(new AiCapabilityDeniedException(denied));

        assertThrows(AiCapabilityDeniedException.class,
                () -> service.chat(AiAssistantKey.RESEARCH_ASSISTANT,
                        request(AiCapability.RESEARCH_GROUP_SUMMARY, 30L, null), "request-denied"));

        verifyNoInteractions(gatewayClient);
        ArgumentCaptor<AiAssistantAuditEvent> auditEvent = ArgumentCaptor.forClass(AiAssistantAuditEvent.class);
        verify(auditUsageService).recordAssistantRequest(auditEvent.capture());
        assertEquals(7L, auditEvent.getValue().actorId());
        assertEquals(AiAssistantKey.RESEARCH_ASSISTANT, auditEvent.getValue().assistant());
        assertEquals(AiAuditExecutionResult.DENIED, auditEvent.getValue().executionResult());
        assertEquals(AiAuditFailureCode.RESOURCE_NOT_AUTHORIZED, auditEvent.getValue().failureCode());
        assertFalse(auditEvent.getValue().consumesUsage());
    }

    @Test
    void gatewayFailureIsAuditedWithoutFabricatedUsageOrRawFailureDetails() {
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile(AiAssistantKey.LAB_ASSISTANT), 7L, AiAssistantSystemRole.STUDENT);
        AiCapabilityRequest capabilityRequest = capabilityRequest(
                AiAssistantKey.LAB_ASSISTANT, 7L, AiCapability.LAB_SLOT_READ, 17L, null);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(context(capabilityRequest));
        when(gatewayClient.chat(any())).thenThrow(new IllegalStateException(
                "Authorization: Bearer internal-service-token raw-model-response"));

        assertThrows(IllegalStateException.class,
                () -> service.chat(AiAssistantKey.LAB_ASSISTANT,
                        request(AiCapability.LAB_SLOT_READ, 17L, null), "request-failed"));

        ArgumentCaptor<AiAssistantAuditEvent> auditEvent = ArgumentCaptor.forClass(AiAssistantAuditEvent.class);
        verify(auditUsageService).recordAssistantRequest(auditEvent.capture());
        assertEquals(AiAuditExecutionResult.FAILED, auditEvent.getValue().executionResult());
        assertEquals(AiAuditFailureCode.INTERNAL_FAILURE, auditEvent.getValue().failureCode());
        assertNull(auditEvent.getValue().promptTokens());
        assertNull(auditEvent.getValue().completionTokens());
        assertFalse(auditEvent.getValue().consumesUsage());
    }

    @Test
    void contextAuthorizationFailureNeverInvokesPython() {
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile(AiAssistantKey.LAB_ASSISTANT), 7L, AiAssistantSystemRole.STUDENT);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenThrow(new AiContextReadDeniedException());

        assertThrows(AiContextReadDeniedException.class,
                () -> service.chat(AiAssistantKey.LAB_ASSISTANT,
                        request(AiCapability.LAB_POLICY_READ, 10L, null), "request-denied"));

        verifyNoInteractions(gatewayClient);
    }

    @Test
    void mismatchedAuthorizedContextFailsClosedBeforePython() {
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile(AiAssistantKey.LAB_ASSISTANT), 7L, AiAssistantSystemRole.STUDENT);
        AiCapabilityRequest differentAssistant = capabilityRequest(
                AiAssistantKey.RESEARCH_ASSISTANT, 7L, AiCapability.RESEARCH_GROUP_SUMMARY, 30L, null);
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(context(differentAssistant));

        assertThrows(AiContextReadDeniedException.class,
                () -> service.chat(AiAssistantKey.LAB_ASSISTANT,
                        request(AiCapability.LAB_POLICY_READ, 10L, null), "request-tampered"));

        verifyNoInteractions(gatewayClient);
    }

    @Test
    void crossDomainContextPayloadFailsClosedBeforePython() {
        AiAssistantAvailability availability = new AiAssistantAvailability(
                profile(AiAssistantKey.LAB_ASSISTANT), 7L, AiAssistantSystemRole.STUDENT);
        AiCapabilityRequest request = capabilityRequest(
                AiAssistantKey.LAB_ASSISTANT, 7L, AiCapability.LAB_POLICY_READ, 10L, null);
        AiAuthorizedContext valid = context(request);
        AiAuthorizedContext crossDomain = new AiAuthorizedContext(
                valid.requestId(), valid.assistantKey(), valid.domain(), valid.capability(), valid.resource(),
                valid.toolPolicy(), valid.contextVersion(), valid.builtAt(), valid.freshness(), researchContext(30L));
        when(availabilityService.requireAvailableForActor(AiAssistantKey.LAB_ASSISTANT))
                .thenReturn(availability);
        when(contextFacade.build(any())).thenReturn(crossDomain);

        assertThrows(AiContextReadDeniedException.class,
                () -> service.chat(AiAssistantKey.LAB_ASSISTANT,
                        request(AiCapability.LAB_POLICY_READ, 10L, null), "request-cross-domain"));

        verifyNoInteractions(gatewayClient);
    }

    private static AiAssistantChatRequest request(AiCapability capability,
                                                   Long resourceId,
                                                   Long parentResourceId) {
        AiAssistantChatRequest request = new AiAssistantChatRequest();
        request.setInput("Summarize what I may access.");
        request.setCapability(capability);
        request.setResourceId(resourceId);
        request.setParentResourceId(parentResourceId);
        return request;
    }

    private static AiCapabilityRequest capabilityRequest(AiAssistantKey key,
                                                          Long actorId,
                                                          AiCapability capability,
                                                          Long resourceId,
                                                          Long parentResourceId) {
        return new AiCapabilityRequest(key, actorId, capability,
                new AiCapabilityRequest.ResourceReference(capability.resourceType(), resourceId),
                capability.parentResourceType() == null ? null
                        : new AiCapabilityRequest.ResourceReference(capability.parentResourceType(), parentResourceId),
                capability.action());
    }

    private static AiAuthorizedContext context(AiCapabilityRequest request) {
        AiCapability capability = request.capability();
        AiCapabilityDecision.ResolvedResource resource = switch (capability.domain()) {
            case LAB -> new AiCapabilityDecision.ResolvedResource(
                    capability.resourceType(), request.resource().id(), 10L,
                    null, null, null, AiResourceScope.EXISTING_BUSINESS_PERMISSION);
            case RESEARCH -> new AiCapabilityDecision.ResolvedResource(
                    capability.resourceType(), request.resource().id(), 10L,
                    capability == AiCapability.RESEARCH_TASK_PROPOSAL_DRAFT
                            ? request.parentResource().id() : 20L,
                    request.resource().id(), null, AiResourceScope.GROUP_MEMBER);
            case ADMIN -> new AiCapabilityDecision.ResolvedResource(
                    capability.resourceType(), request.resource().id(),
                    null, null, null, null, AiResourceScope.GLOBAL);
        };
        AiDomainContext projectedContext = switch (capability.domain()) {
            case ADMIN -> new AiAdminContext(
                    new AiAdminContext.SystemSummary(8L, 10L), null,
                    AiBoundedList.fromOverfetch(List.of(), 14), false);
            case LAB -> new AiLabContext(new AiLabContext.Laboratory(10L, "Authorized Lab", null),
                        new AiLabContext.Slot(request.resource().id(), Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-01-01T01:00:00Z"), null),
                        null, null, null, null, capability.riskBoundary() == AiActionRiskBoundary.DRAFT_ONLY,
                        "AUTHORIZED_ONLY");
            case RESEARCH -> researchContext(request.resource().id());
        };
        return new AiAuthorizedContext("request-123", request.assistantKey(), capability.domain(), capability,
                resource, new AiAuthorizedToolPolicy(new AiToolRegistryServiceImpl().get(capability)),
                "P5A-T5-v1", Instant.parse("2026-01-01T00:00:00Z"),
                AiAuthorizedContext.Freshness.LIVE_READ_NO_CACHE, projectedContext);
    }

    private static AiResearchAssistantContext researchContext(Long selectedId) {
        AiResearchContext research = new AiResearchContext(
                new AiResearchContext.Identity(7L, List.of()),
                new AiResearchContext.Laboratory(10L, "Authorized Lab"),
                new AiResearchContext.Project(20L, "P", "Authorized Project",
                        ProjectStatus.ONGOING, null, null),
                List.of(), List.of(), List.of());
        return new AiResearchAssistantContext(research,
                AiBoundedList.fromOverfetch(List.of(), 20),
                AiBoundedList.fromOverfetch(List.of(), 20),
                AiBoundedList.fromOverfetch(List.of(), 25), null, selectedId, true);
    }

    private static AiAssistantProfile profile(AiAssistantKey key) {
        AiAssistantDomain domain = key.domain();
        String namespace = switch (domain) {
            case ADMIN -> "admin-knowledge";
            case LAB -> "lab-knowledge";
            case RESEARCH -> "research-knowledge";
        };
        return new AiAssistantProfile(key, domain, true,
                Set.of(AiAssistantSystemRole.ADMIN, AiAssistantSystemRole.LAB_MANAGER,
                        AiAssistantSystemRole.STUDENT),
                "model", "prompt-v1", null, namespace, AiQuotaPolicyReference.AI_CONFIG_QUOTA,
                Set.of(switch (domain) {
                    case ADMIN -> AiAssistantToolGroup.ADMIN_READ;
                    case LAB -> AiAssistantToolGroup.LAB_READ;
                    case RESEARCH -> AiAssistantToolGroup.RESEARCH_DRAFT;
                }), "suite-v1");
    }

    private static Set<String> fieldSet(JsonNode object) {
        Set<String> fields = new HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
