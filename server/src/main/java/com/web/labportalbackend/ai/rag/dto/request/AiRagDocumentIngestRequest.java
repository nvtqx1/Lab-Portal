package com.web.labportalbackend.ai.rag.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.web.labportalbackend.ai.enums.AiAssistantDomain;
import com.web.labportalbackend.ai.rag.enums.AiRagVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiRagDocumentIngestRequest {

    @NotNull(message = "RAG domain is required")
    @Schema(description = "Assistant domain that owns the isolated knowledge namespace")
    private AiAssistantDomain domain;

    @NotBlank(message = "Resource ID is required")
    @Size(max = 128, message = "Resource ID must not exceed 128 characters")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$", message = "Resource ID format is invalid")
    @Schema(description = "Stable source-resource identifier used across document versions")
    private String resourceId;

    @Positive(message = "Document version must be positive")
    @Schema(description = "Positive source document version")
    private int version;

    @NotBlank(message = "Source type is required")
    @Size(max = 64, message = "Source type must not exceed 64 characters")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Source type format is invalid")
    @Schema(description = "Source classification returned with authorized citations")
    private String sourceType;

    @NotBlank(message = "Document title is required")
    @Size(max = 255, message = "Document title must not exceed 255 characters")
    @Schema(description = "Human-readable source document title")
    private String title;

    @NotBlank(message = "Document content is required")
    @Size(max = 512000, message = "Document content must not exceed 512000 characters")
    @Schema(description = "Plain-text document content; form-feed characters delimit source pages")
    private String content;

    @NotNull(message = "Document visibility is required")
    @Schema(description = "Visibility rule enforced by Spring before retrieval enters an AI prompt")
    private AiRagVisibility visibility;

    @Positive(message = "Laboratory ID must be positive")
    @Schema(description = "Laboratory scope for Lab or Research knowledge, when required")
    private Long labId;

    @Positive(message = "Project ID must be positive")
    @Schema(description = "Research project scope for project-visible knowledge; optional for legacy group-scoped documents")
    private Long projectId;

    @Positive(message = "Group ID must be positive")
    @Schema(description = "Research group scope for group-visible knowledge")
    private Long groupId;

    @JsonIgnore
    @Schema(hidden = true)
    private final Set<String> unknownFields = new LinkedHashSet<>();

    @JsonAnySetter
    public void captureUnknownProperty(String name, JsonNode value) {
        unknownFields.add(name);
    }

    @JsonIgnore
    @AssertTrue(message = "Unknown RAG ingestion fields are not allowed")
    @Schema(hidden = true)
    public boolean hasNoUnknownFields() {
        return unknownFields.isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "RAG document scope is invalid")
    @Schema(hidden = true)
    public boolean hasValidScopeShape() {
        if (domain == null || visibility == null) {
            return true;
        }
        if (domain == AiAssistantDomain.ADMIN) {
            return visibility == AiRagVisibility.ADMIN_ONLY
                    && labId == null && projectId == null && groupId == null;
        }
        if (labId == null) {
            return false;
        }
        if (domain == AiAssistantDomain.LAB) {
            return (visibility == AiRagVisibility.OWNER || visibility == AiRagVisibility.LAB_MEMBERS)
                    && projectId == null && groupId == null;
        }
        return switch (visibility) {
            case ADMIN_ONLY -> false;
            case LAB_MEMBERS -> projectId == null && groupId == null;
            case PROJECT_MEMBERS -> domain == AiAssistantDomain.RESEARCH
                    && projectId != null && groupId == null;
            case GROUP_MEMBERS -> domain == AiAssistantDomain.RESEARCH
                    && groupId != null;
            case OWNER -> groupId == null || projectId != null;
        };
    }
}
