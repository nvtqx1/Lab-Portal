package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.client.AiChatResponse;
import com.web.labportalbackend.ai.client.AiGatewayClient;
import com.web.labportalbackend.ai.client.AiGatewayException;
import com.web.labportalbackend.ai.client.AiGatewayRequest;
import com.web.labportalbackend.ai.context.AiAuthorizedContext;
import com.web.labportalbackend.ai.context.AiContextBuildRequest;
import com.web.labportalbackend.ai.context.AiContextFacade;
import com.web.labportalbackend.ai.context.AiContextReadDeniedException;
import com.web.labportalbackend.ai.context.AiPythonAuthorizedContext;
import com.web.labportalbackend.ai.dto.request.AiAssistantChatRequest;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.rag.service.AiAuthorizedRetrieval;
import com.web.labportalbackend.ai.rag.service.AiRagRetrievalService;
import com.web.labportalbackend.ai.rag.dto.response.AiRagCitationResponse;
import com.web.labportalbackend.ai.service.AiAssistantAuditEvent;
import com.web.labportalbackend.ai.service.AiAssistantAvailability;
import com.web.labportalbackend.ai.service.AiAssistantAvailabilityException;
import com.web.labportalbackend.ai.service.AiAssistantAvailabilityService;
import com.web.labportalbackend.ai.service.AiAssistantGatewayService;
import com.web.labportalbackend.ai.service.AiAssistantProfile;
import com.web.labportalbackend.ai.service.AiAuditExecutionResult;
import com.web.labportalbackend.ai.service.AiAuditFailureCode;
import com.web.labportalbackend.ai.service.AiAuditGateStatus;
import com.web.labportalbackend.ai.service.AiAuditUsageService;
import com.web.labportalbackend.ai.service.AiCapabilityDecision;
import com.web.labportalbackend.ai.service.AiCapabilityDeniedException;
import com.web.labportalbackend.ai.service.AiCapabilityRequest;
import java.util.Objects;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AiAssistantGatewayServiceImpl implements AiAssistantGatewayService {

    private final AiAssistantAvailabilityService availabilityService;
    private final AiContextFacade contextFacade;
    private final AiGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;
    private final AiAuditUsageService auditUsageService;
    private final AiRagRetrievalService ragRetrievalService;

    public AiAssistantGatewayServiceImpl(AiAssistantAvailabilityService availabilityService,
                                         AiContextFacade contextFacade,
                                         AiGatewayClient gatewayClient,
                                         ObjectMapper objectMapper,
                                         AiAuditUsageService auditUsageService,
                                         AiRagRetrievalService ragRetrievalService) {
        this.availabilityService = availabilityService;
        this.contextFacade = contextFacade;
        this.gatewayClient = gatewayClient;
        this.objectMapper = objectMapper;
        this.auditUsageService = auditUsageService;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public AiAssistantChatResponse chat(AiAssistantKey assistantKey,
                                        AiAssistantChatRequest request,
                                        String requestId) {
        String normalizedRequestId = AiGatewayRequest.normalizeRequestId(requestId);
        AiAssistantAvailability availability = null;
        AiAssistantProfile profile = null;
        AiAuthorizedContext authorized = null;
        AiAuthorizedRetrieval retrieval = null;
        AiChatResponse response;
        boolean requestShapeValidated = false;
        try {
            if (request == null || request.getCapability() == null || !request.hasValidResourceSelection()) {
                throw new IllegalArgumentException("AI capability selection is invalid");
            }
            requestShapeValidated = true;
            availability = availabilityService.requireAvailableForActor(assistantKey);
            profile = availability.profile();
            AiCapabilityRequest capabilityRequest = new AiCapabilityRequest(
                    profile.key(), availability.actorId(), request.getCapability(),
                    new AiCapabilityRequest.ResourceReference(
                            request.getCapability().resourceType(), request.getResourceId()),
                    request.getCapability().parentResourceType() == null ? null
                            : new AiCapabilityRequest.ResourceReference(
                                    request.getCapability().parentResourceType(), request.getParentResourceId()),
                    request.getCapability().action());
            authorized = contextFacade.build(new AiContextBuildRequest(capabilityRequest, normalizedRequestId));
            validateAuthority(profile, capabilityRequest, authorized);
            retrieval = ragRetrievalService.retrieve(profile, availability.actorId(),
                    availability.selectedSystemRole(), authorized, request.getInput());

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("assistantKey", profile.key().name());
            payload.put("input", inferenceInput(request, authorized));
            payload.set("authorizedContext",
                    objectMapper.valueToTree(AiPythonAuthorizedContext.from(authorized, retrieval)));
            response = gatewayClient.chat(new AiGatewayRequest(payload, normalizedRequestId));
        } catch (RuntimeException exception) {
            auditUsageService.recordAssistantRequest(failedEvent(
                    normalizedRequestId, request, availability, exception, requestShapeValidated));
            throw exception;
        }
        auditUsageService.recordAssistantRequest(succeededEvent(
                normalizedRequestId, profile, availability, authorized, response));
        return new AiAssistantChatResponse(profile.key().name(), response.answer(),
                response.promptTokens(), response.completionTokens(), citations(retrieval));
    }

    private static List<AiRagCitationResponse> citations(AiAuthorizedRetrieval retrieval) {
        if (retrieval == null) {
            throw new AiContextReadDeniedException();
        }
        return retrieval.chunks().stream()
                .map(chunk -> new AiRagCitationResponse(chunk.documentId(), chunk.resourceId(), chunk.version(),
                        chunk.chunkIndex(), chunk.pageNumber(), chunk.sourceType()))
                .toList();
    }

    private static String inferenceInput(AiAssistantChatRequest request, AiAuthorizedContext authorized) {
        if (request.getCapability() != AiCapability.LAB_SHIFT_CREATE_DRAFT) {
            return request.getInput();
        }
        return "Trusted Spring temporal context: requestTimeUtc=" + authorized.builtAt()
                + ", defaultTimezone=Asia/Ho_Chi_Minh. Resolve relative dates such as today or tomorrow from "
                + "this instant after converting it to the timezone explicitly supplied in the user request, "
                + "or to defaultTimezone when the request does not specify one. Never use a training example or "
                + "training cutoff as the current date. User request: " + request.getInput();
    }

    private static AiAssistantAuditEvent succeededEvent(String requestId,
                                                         AiAssistantProfile profile,
                                                         AiAssistantAvailability availability,
                                                         AiAuthorizedContext authorized,
                                                         AiChatResponse response) {
        AiCapabilityDecision.ResolvedResource resource = authorized.resource();
        return new AiAssistantAuditEvent(
                availability.actorId(), profile.key(), authorized.capability(), resource.type(), resource.id(),
                profile.modelProfile(), profile.adapterReference(), profile.promptVersion(), requestId,
                AiAuditGateStatus.from(authorized.capability().riskBoundary()),
                AiAuditExecutionResult.SUCCEEDED, null, availability.selectedSystemRole(),
                resource.labId(), resource.projectId(), resource.groupId(),
                response.promptTokens(), response.completionTokens(), true);
    }

    private static AiAssistantAuditEvent failedEvent(String requestId,
                                                      AiAssistantChatRequest request,
                                                      AiAssistantAvailability availability,
                                                      RuntimeException exception,
                                                      boolean requestShapeValidated) {
        AiAssistantProfile profile = availability == null ? null : availability.profile();
        AiCapability capability = request == null ? null : request.getCapability();
        AiResourceType resourceType = capability == null ? null : capability.resourceType();
        Long resourceId = request == null || request.getResourceId() == null || request.getResourceId() <= 0
                ? null : request.getResourceId();
        return new AiAssistantAuditEvent(
                availability == null ? null : availability.actorId(),
                profile == null ? null : profile.key(), capability, resourceType, resourceId,
                profile == null ? null : profile.modelProfile(),
                profile == null ? null : profile.adapterReference(),
                profile == null ? null : profile.promptVersion(), requestId,
                AiAuditGateStatus.from(capability == null ? null : capability.riskBoundary()),
                denied(exception, requestShapeValidated)
                        ? AiAuditExecutionResult.DENIED : AiAuditExecutionResult.FAILED,
                failureCode(exception, requestShapeValidated), null,
                null, null, null, null, null, false);
    }

    private static boolean denied(RuntimeException exception, boolean requestShapeValidated) {
        return (!requestShapeValidated && exception instanceof IllegalArgumentException)
                || exception instanceof AiAssistantAvailabilityException
                || exception instanceof AiCapabilityDeniedException
                || exception instanceof AiContextReadDeniedException;
    }

    private static AiAuditFailureCode failureCode(RuntimeException exception, boolean requestShapeValidated) {
        if (exception instanceof AiAssistantAvailabilityException availabilityException) {
            return AiAuditFailureCode.from(availabilityException.failure());
        }
        if (exception instanceof AiCapabilityDeniedException || exception instanceof AiContextReadDeniedException) {
            return AiAuditFailureCode.RESOURCE_NOT_AUTHORIZED;
        }
        if (exception instanceof AiGatewayException) {
            return AiAuditFailureCode.GATEWAY_FAILED;
        }
        if (!requestShapeValidated && exception instanceof IllegalArgumentException) {
            return AiAuditFailureCode.INVALID_ASSISTANT_REQUEST;
        }
        return AiAuditFailureCode.INTERNAL_FAILURE;
    }

    private static void validateAuthority(AiAssistantProfile profile,
                                          AiCapabilityRequest request,
                                          AiAuthorizedContext authorized) {
        if (profile == null || authorized == null || authorized.assistantKey() != profile.key()
                || authorized.domain() != profile.domain() || authorized.capability() != request.capability()
                || authorized.resource() == null
                || authorized.resource().type() != request.resource().type()
                || !Objects.equals(authorized.resource().id(), request.resource().id())) {
            throw new AiContextReadDeniedException();
        }
        if (request.parentResource() != null
                && (request.parentResource().type() != com.web.labportalbackend.ai.enums.AiResourceType.PROJECT
                || !Objects.equals(authorized.resource().projectId(), request.parentResource().id()))) {
            throw new AiContextReadDeniedException();
        }
    }
}
