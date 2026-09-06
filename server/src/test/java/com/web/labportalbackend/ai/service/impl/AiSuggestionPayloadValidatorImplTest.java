package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.web.labportalbackend.ai.client.AiSuggestionResponse;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidationException;
import com.web.labportalbackend.ai.service.AiSuggestionPayloadValidator;
import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AiSuggestionPayloadValidatorImplTest {

    private static final String MESSAGE = "Invalid AI suggestion payload.";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiSuggestionPayloadValidator validator = new AiSuggestionPayloadValidatorImpl();

    @ParameterizedTest
    @MethodSource("validSuggestions")
    void acceptsEachExactActionSchemaWithPermittedNulls(String actionType, ObjectNode payload) {
        assertDoesNotThrow(() -> validator.validate(response(actionType, payload)));
    }

    @Test
    void rejectsInvalidEnvelopeValues() {
        assertInvalid(() -> validator.validate(null));
        assertInvalid(() -> validator.validate(new AiSuggestionResponse("assistant", "CREATE_TASK", 2,
                createTaskPayload(), 0.5, "explanation")));
        assertInvalid(() -> validator.validate(response("UNKNOWN_ACTION", createTaskPayload())));
        assertInvalid(() -> validator.validate(response("CREATE_TASK", OBJECT_MAPPER.createArrayNode())));
    }

    @Test
    void rejectsMissingUnknownAndCrossActionFieldsWithoutLeakingUntrustedContent() {
        ObjectNode missing = createTaskPayload();
        missing.remove("title");
        assertInvalid(() -> validator.validate(response("CREATE_TASK", missing)));

        String sentinel = "sensitive-sentinel-value";
        String unknownField = "untrustedField";
        ObjectNode unknown = createTaskPayload();
        unknown.put(unknownField, sentinel);
        AiSuggestionPayloadValidationException exception = assertThrows(AiSuggestionPayloadValidationException.class,
                () -> validator.validate(response("CREATE_TASK", unknown)));

        assertEquals(MESSAGE, exception.getMessage());
        assertNoCauseContains(exception, sentinel, unknownField);

        assertInvalid(() -> validator.validate(response("CREATE_SUBTASK", createTaskPayload())));
    }

    @Test
    void rejectsJsonNullForRequiredFieldsAndAcceptsItForNullableFields() {
        ObjectNode requiredNull = createTaskPayload();
        requiredNull.putNull("title");
        assertInvalid(() -> validator.validate(response("CREATE_TASK", requiredNull)));

        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK", createTaskPayload())));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifierNodes")
    void rejectsWrongIdentifierNodeTypesAndOverflow(JsonNode invalidIdentifier) {
        ObjectNode payload = createTaskPayload();
        payload.set("projectId", invalidIdentifier);

        assertInvalid(() -> validator.validate(response("CREATE_TASK", payload)));
    }

    @Test
    void rejectsNonPositiveProjectAndGroupIdentifiersOnlyWhereRequired() {
        ObjectNode zeroProject = createTaskPayload();
        zeroProject.put("projectId", 0);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", zeroProject)));

        ObjectNode negativeGroup = createTaskPayload();
        negativeGroup.put("groupId", -1);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", negativeGroup)));

        ObjectNode nonPositiveOtherId = createSubtaskPayload();
        nonPositiveOtherId.put("parentTaskId", 0);
        assertDoesNotThrow(() -> validator.validate(response("CREATE_SUBTASK", nonPositiveOtherId)));
    }

    @Test
    void enforcesTextTypesAndBoundaries() {
        ObjectNode nonTextTitle = createTaskPayload();
        nonTextTitle.put("title", 1);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", nonTextTitle)));

        ObjectNode blankTitle = createTaskPayload();
        blankTitle.put("title", "  ");
        assertInvalid(() -> validator.validate(response("CREATE_TASK", blankTitle)));

        ObjectNode taskAtMaximum = createTaskPayload();
        taskAtMaximum.put("title", repeated('a', 200));
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK", taskAtMaximum)));

        ObjectNode taskOverMaximum = createTaskPayload();
        taskOverMaximum.put("title", repeated('a', 201));
        assertInvalid(() -> validator.validate(response("CREATE_TASK", taskOverMaximum)));

        ObjectNode milestoneTooShort = createMilestonePayload();
        milestoneTooShort.put("title", "ab");
        assertInvalid(() -> validator.validate(response("CREATE_MILESTONE", milestoneTooShort)));

        ObjectNode milestoneAtBounds = createMilestonePayload();
        milestoneAtBounds.put("title", "abc");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_MILESTONE", milestoneAtBounds)));
        milestoneAtBounds.put("title", repeated('m', 200));
        assertDoesNotThrow(() -> validator.validate(response("CREATE_MILESTONE", milestoneAtBounds)));
        milestoneAtBounds.put("title", repeated('m', 201));
        assertInvalid(() -> validator.validate(response("CREATE_MILESTONE", milestoneAtBounds)));

        ObjectNode reportAtMaximum = createReportPayload();
        reportAtMaximum.put("comment", repeated('c', 5000));
        assertDoesNotThrow(() -> validator.validate(response("CREATE_REPORT_REVIEW_COMMENT", reportAtMaximum)));
        reportAtMaximum.put("comment", repeated('c', 5001));
        assertInvalid(() -> validator.validate(response("CREATE_REPORT_REVIEW_COMMENT", reportAtMaximum)));

        ObjectNode proposalDescriptionAtMaximum = createProposalPayload();
        proposalDescriptionAtMaximum.put("description", repeated('d', 4000));
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK_PROPOSAL", proposalDescriptionAtMaximum)));
        proposalDescriptionAtMaximum.put("description", repeated('d', 4001));
        assertInvalid(() -> validator.validate(response("CREATE_TASK_PROPOSAL", proposalDescriptionAtMaximum)));
    }

    @Test
    void acceptsEmptyDescriptionsForEveryOptionalDescriptionField() {
        ObjectNode task = createTaskPayload();
        task.put("description", "");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK", task)));

        ObjectNode subtask = createSubtaskPayload();
        subtask.put("description", "");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_SUBTASK", subtask)));

        ObjectNode milestone = createMilestonePayload();
        milestone.put("description", "");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_MILESTONE", milestone)));

        ObjectNode proposal = createProposalPayload();
        proposal.put("description", "");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK_PROPOSAL", proposal)));
    }

    @Test
    void acceptsWhitespaceDescriptionsForEveryOptionalDescriptionField() {
        ObjectNode task = createTaskPayload();
        task.put("description", "  ");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK", task)));

        ObjectNode subtask = createSubtaskPayload();
        subtask.put("description", "  ");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_SUBTASK", subtask)));

        ObjectNode milestone = createMilestonePayload();
        milestone.put("description", "  ");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_MILESTONE", milestone)));

        ObjectNode proposal = createProposalPayload();
        proposal.put("description", "  ");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_TASK_PROPOSAL", proposal)));
    }

    @Test
    void retainsOptionalDescriptionTypeAndMaximumLengthConstraints() {
        ObjectNode nonTextTaskDescription = createTaskPayload();
        nonTextTaskDescription.put("description", 1);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", nonTextTaskDescription)));

        ObjectNode overlongMilestoneDescription = createMilestonePayload();
        overlongMilestoneDescription.put("description", repeated('m', 4001));
        assertInvalid(() -> validator.validate(response("CREATE_MILESTONE", overlongMilestoneDescription)));
    }

    @Test
    void rejectsInvalidEnumAndDateValuesButDoesNotAddMilestoneDateOrderingRules() {
        ObjectNode invalidPriority = createTaskPayload();
        invalidPriority.put("priority", "INVALID");
        assertInvalid(() -> validator.validate(response("CREATE_TASK", invalidPriority)));

        ObjectNode invalidType = createTaskPayload();
        invalidType.put("type", 1);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", invalidType)));

        ObjectNode invalidDecision = createReportPayload();
        invalidDecision.put("suggestedDecision", "ACCEPT");
        assertInvalid(() -> validator.validate(response("CREATE_REPORT_REVIEW_COMMENT", invalidDecision)));

        ObjectNode invalidDate = createTaskPayload();
        invalidDate.put("dueDate", "2026-02-30");
        assertInvalid(() -> validator.validate(response("CREATE_TASK", invalidDate)));

        ObjectNode nonTextDate = createTaskPayload();
        nonTextDate.put("dueDate", 20260806);
        assertInvalid(() -> validator.validate(response("CREATE_TASK", nonTextDate)));

        ObjectNode unorderedMilestoneDates = createMilestonePayload();
        unorderedMilestoneDates.put("startDate", "2026-12-31");
        unorderedMilestoneDates.put("deadline", "2026-01-01");
        assertDoesNotThrow(() -> validator.validate(response("CREATE_MILESTONE", unorderedMilestoneDates)));
    }

    private static Stream<Arguments> validSuggestions() {
        return Stream.of(
                Arguments.of("CREATE_TASK", createTaskPayload()),
                Arguments.of("CREATE_SUBTASK", createSubtaskPayload()),
                Arguments.of("CREATE_MILESTONE", createMilestonePayload()),
                Arguments.of("CREATE_REPORT_REVIEW_COMMENT", createReportPayload()),
                Arguments.of("CREATE_TASK_PROPOSAL", createProposalPayload()));
    }

    private static Stream<Arguments> invalidIdentifierNodes() {
        return Stream.of(
                Arguments.of(OBJECT_MAPPER.getNodeFactory().numberNode(1.5)),
                Arguments.of(OBJECT_MAPPER.getNodeFactory().textNode("1")),
                Arguments.of(OBJECT_MAPPER.getNodeFactory().booleanNode(true)),
                Arguments.of(OBJECT_MAPPER.createObjectNode()),
                Arguments.of(OBJECT_MAPPER.createArrayNode()),
                Arguments.of(OBJECT_MAPPER.getNodeFactory().numberNode(new BigInteger("9223372036854775808"))));
    }

    private static ObjectNode createTaskPayload() {
        return payload(List.of("projectId", "groupId", "milestoneId", "title", "description", "assigneeId", "priority",
                        "type", "dueDate"), node -> {
                    node.put("projectId", 1);
                    node.putNull("groupId");
                    node.putNull("milestoneId");
                    node.put("title", "Task");
                    node.putNull("description");
                    node.putNull("assigneeId");
                    node.putNull("priority");
                    node.putNull("type");
                    node.putNull("dueDate");
                });
    }

    private static ObjectNode createSubtaskPayload() {
        return payload(List.of("parentTaskId", "title", "description", "assigneeId", "priority", "dueDate"), node -> {
            node.putNull("parentTaskId");
            node.put("title", "Subtask");
            node.putNull("description");
            node.putNull("assigneeId");
            node.putNull("priority");
            node.putNull("dueDate");
        });
    }

    private static ObjectNode createMilestonePayload() {
        return payload(List.of("projectId", "groupId", "title", "description", "startDate", "deadline"), node -> {
            node.putNull("projectId");
            node.putNull("groupId");
            node.put("title", "Milestone");
            node.putNull("description");
            node.putNull("startDate");
            node.putNull("deadline");
        });
    }

    private static ObjectNode createReportPayload() {
        return payload(List.of("reportId", "comment", "suggestedDecision"), node -> {
            node.put("reportId", 1);
            node.put("comment", "Review comment");
            node.put("suggestedDecision", "REQUEST_REVISION");
        });
    }

    private static ObjectNode createProposalPayload() {
        return payload(List.of("projectId", "groupId", "milestoneId", "title", "description", "priority", "type", "dueDate"),
                node -> {
                    node.put("projectId", 1);
                    node.put("groupId", 2);
                    node.putNull("milestoneId");
                    node.put("title", "Proposal");
                    node.putNull("description");
                    node.putNull("priority");
                    node.putNull("type");
                    node.putNull("dueDate");
                });
    }

    private static ObjectNode payload(List<String> fields, Consumer<ObjectNode> populate) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        populate.accept(node);
        assertEquals(fields, node.properties().stream().map(entry -> entry.getKey()).toList());
        return node;
    }

    private static AiSuggestionResponse response(String actionType, JsonNode payload) {
        return new AiSuggestionResponse("assistant", actionType, 1, payload, 0.5, "explanation");
    }

    @Test
    void createLabShiftRequiresExactTypedPayload() throws Exception {
        JsonNode payload = OBJECT_MAPPER.readTree("""
                {"kind":"LAB_SHIFT_CREATE_DRAFT","labRef":10,
                 "startLocalDateTime":"2026-09-10T08:00:00",
                 "endLocalDateTime":"2026-09-10T10:00:00","timeZone":"Asia/Ho_Chi_Minh",
                 "capacity":20,"requiresHumanReview":true}
                """);

        assertDoesNotThrow(() -> validator.validate(response("CREATE_LAB_SHIFT", payload)));

        ((ObjectNode) payload).put("labRef", 0);
        assertInvalid(() -> validator.validate(response("CREATE_LAB_SHIFT", payload)));
    }

    @Test
    void createLabShiftRejectsAnUnknownTimeZone() throws Exception {
        JsonNode payload = OBJECT_MAPPER.readTree("""
                {"kind":"LAB_SHIFT_CREATE_DRAFT","labRef":10,
                 "startLocalDateTime":"2026-09-10T08:00:00",
                 "endLocalDateTime":"2026-09-10T10:00:00","timeZone":"Not/A_Time_Zone",
                 "capacity":20,"requiresHumanReview":true}
                """);

        assertInvalid(() -> validator.validate(response("CREATE_LAB_SHIFT", payload)));
    }

    private static String repeated(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        AiSuggestionPayloadValidationException exception = assertThrows(AiSuggestionPayloadValidationException.class, executable);
        assertEquals(MESSAGE, exception.getMessage());
        assertNull(exception.getCause());
    }

    private static void assertNoCauseContains(Throwable throwable, String... prohibitedValues) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            for (String prohibitedValue : prohibitedValues) {
                assertFalse(String.valueOf(current.getMessage()).contains(prohibitedValue));
            }
        }
    }
}
