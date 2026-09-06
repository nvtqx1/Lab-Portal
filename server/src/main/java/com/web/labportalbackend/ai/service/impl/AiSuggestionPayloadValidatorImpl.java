package com.web.labportalbackend.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.client.AiSuggestionResponse;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidator;
import com.web.labportalbackend.research.enums.TaskPriority;
import com.web.labportalbackend.research.enums.TaskType;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AiSuggestionPayloadValidatorImpl implements AiSuggestionPayloadValidator {

    private static final Set<String> CREATE_TASK_FIELDS = Set.of(
            "projectId", "groupId", "milestoneId", "title", "description", "assigneeId", "priority", "type", "dueDate");
    private static final Set<String> CREATE_SUBTASK_FIELDS = Set.of(
            "parentTaskId", "title", "description", "assigneeId", "priority", "dueDate");
    private static final Set<String> CREATE_MILESTONE_FIELDS = Set.of(
            "projectId", "groupId", "title", "description", "startDate", "deadline");
    private static final Set<String> CREATE_REPORT_REVIEW_COMMENT_FIELDS = Set.of("reportId", "comment", "suggestedDecision");
    private static final Set<String> CREATE_TASK_PROPOSAL_FIELDS = Set.of(
            "projectId", "groupId", "milestoneId", "title", "description", "priority", "type", "dueDate");
    private static final Set<String> CREATE_LAB_SHIFT_FIELDS = Set.of(
            "kind", "labRef", "startLocalDateTime", "endLocalDateTime", "timeZone", "capacity",
            "requiresHumanReview");
    private static final Set<String> TASK_PRIORITIES = enumNames(TaskPriority.values());
    private static final Set<String> TASK_TYPES = enumNames(TaskType.values());
    private static final Set<String> REPORT_DECISIONS = Set.of("REQUEST_REVISION", "REJECT");

    @Override
    public void validate(AiSuggestionResponse response) {
        try {
            if (response == null || response.schemaVersion() != 1 || !response.payload().isObject()) {
                throw invalid();
            }

            ObjectNode payload = (ObjectNode) response.payload();
            switch (response.actionType()) {
                case "CREATE_TASK" -> validateCreateTask(payload);
                case "CREATE_SUBTASK" -> validateCreateSubtask(payload);
                case "CREATE_MILESTONE" -> validateCreateMilestone(payload);
                case "CREATE_REPORT_REVIEW_COMMENT" -> validateCreateReportReviewComment(payload);
                case "CREATE_TASK_PROPOSAL" -> validateCreateTaskProposal(payload);
                case "CREATE_LAB_SHIFT" -> validateCreateLabShift(payload);
                default -> throw invalid();
            }
        } catch (AiSuggestionPayloadValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void validateCreateTask(ObjectNode payload) {
        validateExactFields(payload, CREATE_TASK_FIELDS);
        requiredPositiveIdentifier(payload, "projectId");
        nullablePositiveIdentifier(payload, "groupId");
        nullableIdentifier(payload, "milestoneId");
        requiredText(payload, "title", 1, 200);
        nullableDescription(payload, "description", Integer.MAX_VALUE);
        nullableIdentifier(payload, "assigneeId");
        nullableEnum(payload, "priority", TASK_PRIORITIES);
        nullableEnum(payload, "type", TASK_TYPES);
        nullableDate(payload, "dueDate");
    }

    private void validateCreateSubtask(ObjectNode payload) {
        validateExactFields(payload, CREATE_SUBTASK_FIELDS);
        nullableIdentifier(payload, "parentTaskId");
        requiredText(payload, "title", 1, 200);
        nullableDescription(payload, "description", Integer.MAX_VALUE);
        nullableIdentifier(payload, "assigneeId");
        nullableEnum(payload, "priority", TASK_PRIORITIES);
        nullableDate(payload, "dueDate");
    }

    private void validateCreateMilestone(ObjectNode payload) {
        validateExactFields(payload, CREATE_MILESTONE_FIELDS);
        nullablePositiveIdentifier(payload, "projectId");
        nullablePositiveIdentifier(payload, "groupId");
        requiredText(payload, "title", 3, 200);
        nullableDescription(payload, "description", 4000);
        nullableDate(payload, "startDate");
        nullableDate(payload, "deadline");
    }

    private void validateCreateReportReviewComment(ObjectNode payload) {
        validateExactFields(payload, CREATE_REPORT_REVIEW_COMMENT_FIELDS);
        requiredIdentifier(payload, "reportId");
        requiredText(payload, "comment", 1, 5000);
        requiredEnum(payload, "suggestedDecision", REPORT_DECISIONS);
    }

    private void validateCreateTaskProposal(ObjectNode payload) {
        validateExactFields(payload, CREATE_TASK_PROPOSAL_FIELDS);
        requiredPositiveIdentifier(payload, "projectId");
        requiredPositiveIdentifier(payload, "groupId");
        nullableIdentifier(payload, "milestoneId");
        requiredText(payload, "title", 1, 200);
        nullableDescription(payload, "description", 4000);
        nullableEnum(payload, "priority", TASK_PRIORITIES);
        nullableEnum(payload, "type", TASK_TYPES);
        nullableDate(payload, "dueDate");
    }

    private void validateCreateLabShift(ObjectNode payload) {
        validateExactFields(payload, CREATE_LAB_SHIFT_FIELDS);
        requiredExactText(payload, "kind", "LAB_SHIFT_CREATE_DRAFT");
        requiredPositiveIdentifier(payload, "labRef");
        requiredLocalDateTime(payload, "startLocalDateTime");
        requiredLocalDateTime(payload, "endLocalDateTime");
        JsonNode timeZone = required(payload, "timeZone");
        validateText(timeZone, 1, 100);
        ZoneId.of(timeZone.textValue());
        JsonNode capacity = required(payload, "capacity");
        if (!capacity.isIntegralNumber() || !capacity.canConvertToInt() || capacity.intValue() <= 0) {
            throw invalid();
        }
        JsonNode review = required(payload, "requiresHumanReview");
        if (!review.isBoolean() || !review.booleanValue()) {
            throw invalid();
        }
    }

    private void validateExactFields(ObjectNode payload, Set<String> expectedFields) {
        if (payload.size() != expectedFields.size()) {
            throw invalid();
        }
        payload.fieldNames().forEachRemaining(field -> {
            if (!expectedFields.contains(field)) {
                throw invalid();
            }
        });
        if (!expectedFields.stream().allMatch(payload::has)) {
            throw invalid();
        }
    }

    private void requiredPositiveIdentifier(ObjectNode payload, String field) {
        JsonNode node = required(payload, field);
        validatePositiveIdentifier(node);
    }

    private void nullablePositiveIdentifier(ObjectNode payload, String field) {
        JsonNode node = nullable(payload, field);
        if (node != null) {
            validatePositiveIdentifier(node);
        }
    }

    private void requiredIdentifier(ObjectNode payload, String field) {
        validateIdentifier(required(payload, field));
    }

    private void nullableIdentifier(ObjectNode payload, String field) {
        JsonNode node = nullable(payload, field);
        if (node != null) {
            validateIdentifier(node);
        }
    }

    private void requiredText(ObjectNode payload, String field, int minimumLength, int maximumLength) {
        validateText(required(payload, field), minimumLength, maximumLength);
    }

    private void requiredExactText(ObjectNode payload, String field, String expected) {
        JsonNode node = required(payload, field);
        if (!node.isTextual() || !expected.equals(node.textValue())) {
            throw invalid();
        }
    }

    private void requiredLocalDateTime(ObjectNode payload, String field) {
        JsonNode node = required(payload, field);
        if (!node.isTextual()) {
            throw invalid();
        }
        try {
            LocalDateTime.parse(node.textValue());
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    private void nullableDescription(ObjectNode payload, String field, int maximumLength) {
        JsonNode node = nullable(payload, field);
        if (node != null) {
            validateOptionalDescription(node, maximumLength);
        }
    }

    private void requiredEnum(ObjectNode payload, String field, Set<String> values) {
        validateEnum(required(payload, field), values);
    }

    private void nullableEnum(ObjectNode payload, String field, Set<String> values) {
        JsonNode node = nullable(payload, field);
        if (node != null) {
            validateEnum(node, values);
        }
    }

    private void nullableDate(ObjectNode payload, String field) {
        JsonNode node = nullable(payload, field);
        if (node == null) {
            return;
        }
        if (!node.isTextual()) {
            throw invalid();
        }
        try {
            LocalDate.parse(node.textValue());
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    private JsonNode required(ObjectNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw invalid();
        }
        return node;
    }

    private JsonNode nullable(ObjectNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null) {
            throw invalid();
        }
        return node.isNull() ? null : node;
    }

    private void validatePositiveIdentifier(JsonNode node) {
        validateIdentifier(node);
        if (node.longValue() <= 0) {
            throw invalid();
        }
    }

    private void validateIdentifier(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid();
        }
    }

    private void validateText(JsonNode node, int minimumLength, int maximumLength) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() < minimumLength
                || node.textValue().length() > maximumLength) {
            throw invalid();
        }
    }

    private void validateOptionalDescription(JsonNode node, int maximumLength) {
        if (!node.isTextual() || node.textValue().length() > maximumLength) {
            throw invalid();
        }
    }

    private void validateEnum(JsonNode node, Set<String> values) {
        if (!node.isTextual() || !values.contains(node.textValue())) {
            throw invalid();
        }
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Set.of(java.util.Arrays.stream(values).map(Enum::name).toArray(String[]::new));
    }

    private static AiSuggestionPayloadValidationException invalid() {
        return new AiSuggestionPayloadValidationException();
    }
}
