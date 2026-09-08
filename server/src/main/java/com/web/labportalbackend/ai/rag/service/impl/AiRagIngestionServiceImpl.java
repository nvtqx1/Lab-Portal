package com.web.labportalbackend.ai.rag.service.impl;

import com.web.labportalbackend.admin.audit.enums.AuditAction;
import com.web.labportalbackend.admin.audit.enums.AuditModule;
import com.web.labportalbackend.admin.audit.service.AuditLogService;
import com.web.labportalbackend.ai.enums.AiAssistantDomain;
import com.web.labportalbackend.ai.rag.dto.request.AiRagDocumentIngestRequest;
import com.web.labportalbackend.ai.rag.dto.response.AiRagDocumentResponse;
import com.web.labportalbackend.ai.rag.entity.AiRagChunkEntity;
import com.web.labportalbackend.ai.rag.entity.AiRagDocumentEntity;
import com.web.labportalbackend.ai.rag.enums.AiKnowledgeNamespace;
import com.web.labportalbackend.ai.rag.repository.AiRagChunkRepository;
import com.web.labportalbackend.ai.rag.repository.AiRagDocumentRepository;
import com.web.labportalbackend.ai.rag.service.AiRagIngestionService;
import com.web.labportalbackend.ai.rag.service.AiRagTextChunker;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.common.enums.UserStatus;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.research.entity.GroupEntity;
import com.web.labportalbackend.research.entity.ProjectEntity;
import com.web.labportalbackend.research.repository.GroupRepository;
import com.web.labportalbackend.research.repository.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;

@Service
public class AiRagIngestionServiceImpl implements AiRagIngestionService {

    private final AiRagDocumentRepository documentRepository;
    private final AiRagChunkRepository chunkRepository;
    private final AiRagTextChunker chunker;
    private final UserRepository userRepository;
    private final LaboratoryRepository laboratoryRepository;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;
    private final AuditLogService auditLogService;

    public AiRagIngestionServiceImpl(AiRagDocumentRepository documentRepository,
                                     AiRagChunkRepository chunkRepository,
                                     AiRagTextChunker chunker,
                                     UserRepository userRepository,
                                     LaboratoryRepository laboratoryRepository,
                                     ProjectRepository projectRepository,
                                     GroupRepository groupRepository,
                                     AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.userRepository = userRepository;
        this.laboratoryRepository = laboratoryRepository;
        this.projectRepository = projectRepository;
        this.groupRepository = groupRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional
    public AiRagDocumentResponse ingest(AiRagDocumentIngestRequest request) {
        if (request == null || request.getDomain() == null || request.getVisibility() == null
                || !request.hasValidScopeShape()) {
            throw new IllegalArgumentException("RAG ingestion request is invalid");
        }
        User actor = currentActor();
        validateOwnedScope(actor, request);
        String namespace = AiKnowledgeNamespace.forDomain(request.getDomain()).value();
        String resourceId = request.getResourceId().trim();
        if (documentRepository.existsByNamespaceAndResourceIdAndActiveTrueAndDeletedFalse(
                namespace, resourceId)) {
            throw new IllegalArgumentException("An active RAG document already exists for the resource");
        }
        AiRagDocumentResponse response = persist(request, actor.getId(), namespace, resourceId);
        auditLogService.log(actor, AuditAction.AI_RAG_INGEST, AuditModule.AI,
                "AI_RAG_DOCUMENT", response.documentId(), "Ingested an authorized RAG document");
        return response;
    }

    @Override
    @Transactional
    public AiRagDocumentResponse reindex(Long documentId, AiRagDocumentIngestRequest request) {
        if (documentId == null || documentId <= 0 || request == null || request.getDomain() == null
                || request.getVisibility() == null || !request.hasValidScopeShape()) {
            throw new IllegalArgumentException("RAG reindex request is invalid");
        }
        User actor = currentActor();
        AiRagDocumentEntity existing = activeDocument(documentId);
        authorizeDocument(actor, existing);
        String resourceId = request.getResourceId().trim();
        if (existing.getDomain() != request.getDomain()
                || !existing.getResourceId().equals(resourceId)
                || request.getVersion() <= existing.getDocumentVersion()
                || !samePermissionMetadata(existing, request)) {
            throw new IllegalArgumentException("RAG reindex must preserve permission metadata and increase version");
        }
        revokeEntities(existing);
        AiRagDocumentResponse response = persist(request, existing.getOwnerId(), existing.getNamespace(), resourceId);
        auditLogService.log(actor, AuditAction.AI_RAG_REINDEX, AuditModule.AI,
                "AI_RAG_DOCUMENT", response.documentId(), "Reindexed an authorized RAG document");
        return response;
    }

    @Override
    @Transactional
    public void revoke(Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new IllegalArgumentException("RAG document ID is invalid");
        }
        User actor = currentActor();
        AiRagDocumentEntity document = activeDocument(documentId);
        authorizeDocument(actor, document);
        revokeEntities(document);
        auditLogService.log(actor, AuditAction.AI_RAG_REVOKE, AuditModule.AI,
                "AI_RAG_DOCUMENT", documentId, "Revoked an authorized RAG document");
    }

    private AiRagDocumentResponse persist(AiRagDocumentIngestRequest request,
                                          Long ownerId,
                                          String namespace,
                                          String resourceId) {

        List<AiRagTextChunker.Chunk> parsed = chunker.chunk(request.getContent());
        AiRagDocumentEntity document = new AiRagDocumentEntity();
        document.setNamespace(namespace);
        document.setDomain(request.getDomain());
        document.setResourceId(resourceId);
        document.setDocumentVersion(request.getVersion());
        document.setSourceType(request.getSourceType().trim());
        document.setTitle(request.getTitle().trim());
        document.setVisibility(request.getVisibility());
        document.setOwnerId(ownerId);
        document.setLabId(request.getLabId());
        document.setProjectId(request.getProjectId());
        document.setGroupId(request.getGroupId());
        document.setContentHash(sha256(request.getContent()));
        AiRagDocumentEntity persistedDocument = documentRepository.save(document);
        if (persistedDocument.getId() == null) {
            throw new IllegalStateException("RAG document persistence did not assign an identifier");
        }

        Long documentId = persistedDocument.getId();
        List<AiRagChunkEntity> chunks = parsed.stream()
                .map(chunk -> toEntity(documentId, persistedDocument, chunk))
                .toList();
        chunkRepository.saveAll(chunks);
        return new AiRagDocumentResponse(documentId, namespace, request.getDomain(), resourceId,
                request.getVersion(), request.getSourceType().trim(), request.getVisibility(), chunks.size());
    }

    private AiRagDocumentEntity activeDocument(Long documentId) {
        return documentRepository.findByIdAndActiveTrueAndDeletedFalse(documentId)
                .orElseThrow(() -> new EntityNotFoundException("RAG document not found"));
    }

    private void authorizeDocument(User actor, AiRagDocumentEntity document) {
        if (actor.hasRole("ADMIN")) {
            return;
        }
        if (!actor.hasRole("LAB_MANAGER") || document.getLabId() == null
                || !laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(
                        document.getLabId(), actor.getId())) {
            throw new AccessDeniedException("RAG document ownership is required");
        }
    }

    private void revokeEntities(AiRagDocumentEntity document) {
        document.setActive(false);
        document.setDeleted(true);
        documentRepository.save(document);
        List<AiRagChunkEntity> chunks = chunkRepository
                .findByDocumentIdAndDeletedFalseOrderByChunkIndex(document.getId());
        chunks.forEach(chunk -> {
            chunk.setActive(false);
            chunk.setDeleted(true);
        });
        chunkRepository.saveAll(chunks);
    }

    private static boolean samePermissionMetadata(AiRagDocumentEntity document,
                                                  AiRagDocumentIngestRequest request) {
        return document.getVisibility() == request.getVisibility()
                && Objects.equals(document.getLabId(), request.getLabId())
                && Objects.equals(document.getProjectId(), request.getProjectId())
                && Objects.equals(document.getGroupId(), request.getGroupId());
    }

    private void validateOwnedScope(User actor, AiRagDocumentIngestRequest request) {
        if (request.getDomain() == AiAssistantDomain.ADMIN) {
            if (!actor.hasRole("ADMIN")) {
                throw new AccessDeniedException("Administrative RAG ownership is required");
            }
            return;
        }
        if (!actor.hasRole("LAB_MANAGER")
                || !laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(
                        request.getLabId(), actor.getId())) {
            throw new AccessDeniedException("Managed laboratory ownership is required");
        }
        ProjectEntity project = null;
        if (request.getProjectId() != null) {
            project = projectRepository.findByIdAndDeletedFalseAndActiveTrue(request.getProjectId())
                    .filter(value -> value.getLab() != null
                            && request.getLabId().equals(value.getLab().getId()))
                    .orElseThrow(() -> new AccessDeniedException("Research project scope is unavailable"));
        }
        if (request.getGroupId() != null) {
            ProjectEntity scopedProject = project;
            groupRepository.findByIdAndDeletedFalseAndActiveTrue(request.getGroupId())
                    .filter(value -> matchesGroupScope(value, request.getLabId(), scopedProject))
                    .orElseThrow(() -> new AccessDeniedException("Research group scope is unavailable"));
        }
    }

    private static boolean matchesGroupScope(GroupEntity group, Long labId, ProjectEntity project) {
        if (group.getLab() == null || !labId.equals(group.getLab().getId())) {
            return false;
        }
        if (project == null) {
            return true;
        }
        return (group.getProject() != null && project.getId().equals(group.getProject().getId()))
                || (project.getGroup() != null && group.getId().equals(project.getGroup().getId()));
    }

    private User currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getName())) {
            throw new AccessDeniedException("Authentication is required for RAG ingestion");
        }
        return userRepository.findByUsername(authentication.getName())
                .filter(user -> user.getId() != null)
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> !Boolean.TRUE.equals(user.getDeleted()))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> user.getRoles() != null)
                .orElseThrow(() -> new AccessDeniedException("Authenticated RAG actor is unavailable"));
    }

    private static AiRagChunkEntity toEntity(Long documentId, AiRagDocumentEntity document,
                                              AiRagTextChunker.Chunk chunk) {
        AiRagChunkEntity entity = new AiRagChunkEntity();
        entity.setDocumentId(documentId);
        entity.setNamespace(document.getNamespace());
        entity.setDomain(document.getDomain());
        entity.setResourceId(document.getResourceId());
        entity.setDocumentVersion(document.getDocumentVersion());
        entity.setChunkIndex(chunk.index());
        entity.setPageNumber(chunk.pageNumber());
        entity.setSourceType(document.getSourceType());
        entity.setVisibility(document.getVisibility());
        entity.setOwnerId(document.getOwnerId());
        entity.setLabId(document.getLabId());
        entity.setProjectId(document.getProjectId());
        entity.setGroupId(document.getGroupId());
        entity.setContentHash(sha256(chunk.content()));
        entity.setContent(chunk.content());
        return entity;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
