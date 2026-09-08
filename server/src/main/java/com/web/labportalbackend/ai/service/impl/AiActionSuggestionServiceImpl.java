package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.labportalbackend.ai.client.AiSuggestionResponse;
import com.web.labportalbackend.ai.dto.response.AiActionPreviewResponse;
import com.web.labportalbackend.ai.dto.response.AiActionResultResponse;
import com.web.labportalbackend.ai.dto.response.AiAssistantChatResponse;
import com.web.labportalbackend.ai.entity.AiActionSuggestionEntity;
import com.web.labportalbackend.ai.enums.AiActionConfirmationStatus;
import com.web.labportalbackend.ai.enums.AiActionExecutionStatus;
import com.web.labportalbackend.ai.enums.AiActionRiskBoundary;
import com.web.labportalbackend.ai.enums.AiActionSuggestionStatus;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.repository.AiActionSuggestionRepository;
import com.web.labportalbackend.ai.service.AiActionSuggestionService;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidator;
import com.web.labportalbackend.booking.dto.request.CreateTimeSlotRequest;
import com.web.labportalbackend.booking.dto.response.TimeSlotResponse;
import com.web.labportalbackend.booking.service.TimeSlotService;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.common.exception.ResourceNotFoundException;
import java.time.DateTimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiActionSuggestionServiceImpl implements AiActionSuggestionService {

    private static final String CREATE_LAB_SHIFT = "CREATE_LAB_SHIFT";
    private static final String BOOKING_MODULE = "BOOKING";
    private static final Set<String> STORED_FIELDS = Set.of("labId", "startTime", "endTime", "capacity");

    private final AiActionSuggestionRepository repository;
    private final AiCurrentActorProvider currentActorProvider;
    private final AiSuggestionPayloadValidator payloadValidator;
    private final TimeSlotService timeSlotService;
    private final LaboratoryRepository laboratoryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AiActionSuggestionServiceImpl(AiActionSuggestionRepository repository,
                                         AiCurrentActorProvider currentActorProvider,
                                         AiSuggestionPayloadValidator payloadValidator,
                                         TimeSlotService timeSlotService,
                                         LaboratoryRepository laboratoryRepository,
                                         ObjectMapper objectMapper) {
        this(repository, currentActorProvider, payloadValidator, timeSlotService, laboratoryRepository, objectMapper,
                Clock.systemUTC());
    }

    AiActionSuggestionServiceImpl(AiActionSuggestionRepository repository,
                                  AiCurrentActorProvider currentActorProvider,
                                  AiSuggestionPayloadValidator payloadValidator,
                                  TimeSlotService timeSlotService,
                                  LaboratoryRepository laboratoryRepository,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.repository = repository;
        this.currentActorProvider = currentActorProvider;
        this.payloadValidator = payloadValidator;
        this.timeSlotService = timeSlotService;
        this.laboratoryRepository = laboratoryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AiActionPreviewResponse createLabShiftPreview(Long authorizedLabId, AiAssistantChatResponse generated) {
        AiCurrentActor actor = requireManager();
        if (authorizedLabId == null || authorizedLabId <= 0 || generated == null
                || !AiAssistantKey.LAB_ASSISTANT.name().equals(generated.assistantKey())) {
            throw invalidSuggestion();
        }
        if (!laboratoryRepository.existsAiContextManagedLab(actor.id(), authorizedLabId, actor.role().name())) {
            throw new AccessDeniedException("Lab is outside the manager's scope");
        }
        var lab = laboratoryRepository.findAiContextLaboratory(actor.id(), authorizedLabId, actor.role().name())
                .orElseThrow(() -> new AccessDeniedException("Lab is outside the manager's scope"));
        JsonNode payload = parse(generated.answer());
        payloadValidator.validate(new AiSuggestionResponse(
                generated.assistantKey(), CREATE_LAB_SHIFT, 1, payload, 1.0, "Validated action preview"));
        if (payload.get("labRef").longValue() != authorizedLabId) {
            throw invalidSuggestion();
        }
        StoredLabShift stored = new StoredLabShift(
                authorizedLabId,
                toInstant(payload.get("startLocalDateTime").textValue(), payload.get("timeZone").textValue()),
                toInstant(payload.get("endLocalDateTime").textValue(), payload.get("timeZone").textValue()),
                payload.get("capacity").intValue());
        if (!stored.startTime().isAfter(clock.instant()) || !stored.startTime().isBefore(stored.endTime())) {
            throw invalidSuggestion();
        }

        AiActionSuggestionEntity saved = repository.save(AiActionSuggestionEntity.builder()
                .requestedById(actor.id())
                .assistantKey(AiAssistantKey.LAB_ASSISTANT)
                .actionType(CREATE_LAB_SHIFT)
                .targetModule(BOOKING_MODULE)
                .payloadJson(write(stored))
                .status(AiActionSuggestionStatus.PENDING)
                .resourceType(AiResourceType.LABORATORY)
                .resourceId(authorizedLabId)
                .actionRiskLevel(AiActionRiskBoundary.CONFIRM_REQUIRED)
                .confirmationStatus(AiActionConfirmationStatus.PENDING)
                .executionStatus(AiActionExecutionStatus.NOT_REQUESTED)
                .build());
        return preview(saved.getId(), stored, lab.name());
    }

    @Override
    @Transactional
    public AiActionResultResponse confirm(Long suggestionId) {
        AiCurrentActor actor = requireManager();
        AiActionSuggestionEntity suggestion = pendingOwnedSuggestion(suggestionId, actor);
        StoredLabShift payload = readStored(suggestion);
        if (!payload.startTime().isAfter(clock.instant())) {
            throw new IllegalStateException("AI action preview start time is no longer in the future");
        }

        suggestion.setConfirmationStatus(AiActionConfirmationStatus.CONFIRMED);
        suggestion.setExecutionStatus(AiActionExecutionStatus.PENDING);
        TimeSlotResponse created;
        try {
            created = timeSlotService.createSlot(CreateTimeSlotRequest.builder()
                    .labId(payload.labId())
                    .startTime(payload.startTime())
                    .endTime(payload.endTime())
                    .capacity(payload.capacity())
                    .status(TimeSlotStatus.AVAILABLE)
                    .build());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            suggestion.setStatus(AiActionSuggestionStatus.REJECTED);
            suggestion.setConfirmationStatus(AiActionConfirmationStatus.REJECTED);
            suggestion.setExecutionStatus(AiActionExecutionStatus.NOT_REQUESTED);
            suggestion.setRejectedReason(exception.getMessage());
            return new AiActionResultResponse(suggestion.getId(), CREATE_LAB_SHIFT, "UNAVAILABLE", null);
        }
        if (created == null || created.getId() == null) {
            throw new IllegalStateException("Time slot creation did not return an identifier");
        }
        suggestion.setTargetId(created.getId());
        suggestion.setExecutedById(actor.id());
        suggestion.setExecutedAt(Instant.now());
        suggestion.setExecutionStatus(AiActionExecutionStatus.EXECUTED);
        suggestion.setStatus(AiActionSuggestionStatus.EXECUTED);
        return new AiActionResultResponse(suggestion.getId(), CREATE_LAB_SHIFT, "EXECUTED", created.getId());
    }

    @Override
    @Transactional
    public AiActionResultResponse cancel(Long suggestionId) {
        AiCurrentActor actor = requireManager();
        AiActionSuggestionEntity suggestion = pendingOwnedSuggestion(suggestionId, actor);
        suggestion.setStatus(AiActionSuggestionStatus.REJECTED);
        suggestion.setConfirmationStatus(AiActionConfirmationStatus.REJECTED);
        suggestion.setExecutionStatus(AiActionExecutionStatus.NOT_REQUESTED);
        suggestion.setRejectedReason("Cancelled by requester");
        return new AiActionResultResponse(suggestion.getId(), CREATE_LAB_SHIFT, "CANCELLED", null);
    }

    private AiActionSuggestionEntity pendingOwnedSuggestion(Long suggestionId, AiCurrentActor actor) {
        if (suggestionId == null || suggestionId <= 0) {
            throw new IllegalArgumentException("Action suggestion ID must be positive");
        }
        AiActionSuggestionEntity suggestion = repository.findByIdForUpdate(suggestionId)
                .orElseThrow(() -> new ResourceNotFoundException("AI action preview not found"));
        if (!actor.id().equals(suggestion.getRequestedById())) {
            throw new AccessDeniedException("Only the requester can confirm this AI action preview");
        }
        if (suggestion.getStatus() != AiActionSuggestionStatus.PENDING
                || suggestion.getConfirmationStatus() != AiActionConfirmationStatus.PENDING
                || suggestion.getExecutionStatus() != AiActionExecutionStatus.NOT_REQUESTED) {
            throw new IllegalStateException("AI action preview is no longer pending");
        }
        if (!CREATE_LAB_SHIFT.equals(suggestion.getActionType())
                || !BOOKING_MODULE.equals(suggestion.getTargetModule())
                || suggestion.getAssistantKey() != AiAssistantKey.LAB_ASSISTANT
                || suggestion.getResourceType() != AiResourceType.LABORATORY
                || suggestion.getResourceId() == null
                || suggestion.getActionRiskLevel() != AiActionRiskBoundary.CONFIRM_REQUIRED) {
            throw new IllegalStateException("AI action preview is invalid");
        }
        return suggestion;
    }

    private AiCurrentActor requireManager() {
        AiCurrentActor actor = currentActorProvider.requireCurrentActor();
        if (actor.role() != AiAssistantSystemRole.LAB_MANAGER) {
            throw new AccessDeniedException("Only lab managers can manage lab shift previews");
        }
        return actor;
    }

    private StoredLabShift readStored(AiActionSuggestionEntity suggestion) {
        try {
            JsonNode root = objectMapper.readTree(suggestion.getPayloadJson());
            if (root == null || !root.isObject() || root.size() != STORED_FIELDS.size()
                    || !STORED_FIELDS.stream().allMatch(root::has)) {
                throw new IllegalStateException("AI action preview payload is invalid");
            }
            StoredLabShift value = objectMapper.treeToValue(root, StoredLabShift.class);
            if (value.labId() == null || !value.labId().equals(suggestion.getResourceId())
                    || value.startTime() == null || value.endTime() == null
                    || !value.startTime().isBefore(value.endTime())
                    || value.capacity() == null || value.capacity() <= 0) {
                throw new IllegalStateException("AI action preview payload is invalid");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI action preview payload is invalid");
        }
    }

    private JsonNode parse(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw invalidSuggestion();
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw invalidSuggestion();
        }
    }

    private String write(StoredLabShift value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI action preview could not be stored");
        }
    }

    private static Instant toInstant(String localDateTimeValue, String timeZoneValue) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(localDateTimeValue);
            ZoneId timeZone = ZoneId.of(timeZoneValue);
            List<ZoneOffset> validOffsets = timeZone.getRules().getValidOffsets(localDateTime);
            if (validOffsets.size() != 1) {
                throw invalidSuggestion();
            }
            return localDateTime.toInstant(validOffsets.getFirst());
        } catch (DateTimeException exception) {
            throw invalidSuggestion();
        }
    }

    private static AiActionPreviewResponse preview(Long id, StoredLabShift value, String labName) {
        return new AiActionPreviewResponse(id, CREATE_LAB_SHIFT, "AWAITING_CONFIRMATION",
                value.labId(), labName, value.startTime(), value.endTime(), value.capacity());
    }

    private static AiSuggestionPayloadValidationException invalidSuggestion() {
        return new AiSuggestionPayloadValidationException();
    }

    private record StoredLabShift(Long labId, Instant startTime, Instant endTime, Integer capacity) {
    }
}
