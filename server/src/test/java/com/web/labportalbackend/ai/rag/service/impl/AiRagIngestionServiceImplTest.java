package com.web.labportalbackend.ai.rag.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.web.labportalbackend.ai.enums.AiAssistantDomain;
import com.web.labportalbackend.admin.audit.service.AuditLogService;
import com.web.labportalbackend.admin.audit.enums.AuditAction;
import com.web.labportalbackend.admin.audit.enums.AuditModule;
import com.web.labportalbackend.ai.rag.dto.request.AiRagDocumentIngestRequest;
import com.web.labportalbackend.ai.rag.entity.AiRagChunkEntity;
import com.web.labportalbackend.ai.rag.entity.AiRagDocumentEntity;
import com.web.labportalbackend.ai.rag.enums.AiRagVisibility;
import com.web.labportalbackend.ai.rag.repository.AiRagChunkRepository;
import com.web.labportalbackend.ai.rag.repository.AiRagDocumentRepository;
import com.web.labportalbackend.ai.rag.service.AiRagTextChunker;
import com.web.labportalbackend.auth.entity.Role;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.common.enums.UserStatus;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.research.entity.GroupEntity;
import com.web.labportalbackend.research.entity.ResearchTopicEntity;
import com.web.labportalbackend.research.repository.GroupRepository;
import com.web.labportalbackend.research.repository.ProjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AiRagIngestionServiceImplTest {

    @Mock private AiRagDocumentRepository documentRepository;
    @Mock private AiRagChunkRepository chunkRepository;
    @Mock private UserRepository userRepository;
    @Mock private LaboratoryRepository laboratoryRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private AuditLogService auditLogService;

    private AiRagIngestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiRagIngestionServiceImpl(documentRepository, chunkRepository, new AiRagTextChunker(),
                userRepository, laboratoryRepository, projectRepository, groupRepository, auditLogService);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("manager", "n/a", List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsVisibilityAndScopeMetadataOnEveryChunk() {
        User manager = actor("LAB_MANAGER");
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(10L, 7L)).thenReturn(true);
        when(documentRepository.existsByNamespaceAndResourceIdAndActiveTrueAndDeletedFalse(
                "lab-knowledge", "policy-1")).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> {
            AiRagDocumentEntity document = invocation.getArgument(0);
            document.setId(100L);
            return document;
        });

        var response = service.ingest(labRequest());

        assertEquals(100L, response.documentId());
        assertEquals("lab-knowledge", response.namespace());
        assertEquals(2, response.chunkCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiRagChunkEntity>> chunks = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(chunks.capture());
        assertTrue(chunks.getValue().stream().allMatch(chunk ->
                chunk.getDocumentId().equals(100L)
                        && chunk.getNamespace().equals("lab-knowledge")
                        && chunk.getDomain() == AiAssistantDomain.LAB
                        && chunk.getVisibility() == AiRagVisibility.LAB_MEMBERS
                        && chunk.getOwnerId().equals(7L)
                        && chunk.getLabId().equals(10L)
                        && chunk.getProjectId() == null
                        && chunk.getGroupId() == null
                        && chunk.getContentHash().length() == 64));
        verify(auditLogService).log(manager, AuditAction.AI_RAG_INGEST, AuditModule.AI,
                "AI_RAG_DOCUMENT", 100L, "Ingested an authorized RAG document");
    }

    @Test
    void reindexRevokesOldChunksAndCreatesHigherVersion() {
        User manager = actor("LAB_MANAGER");
        AiRagDocumentEntity existing = document(100L, 1);
        AiRagChunkEntity oldChunk = chunk(100L);
        AiRagDocumentIngestRequest request = labRequest();
        request.setVersion(2);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(documentRepository.findByIdAndActiveTrueAndDeletedFalse(100L)).thenReturn(Optional.of(existing));
        when(laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(10L, 7L)).thenReturn(true);
        when(chunkRepository.findByDocumentIdAndDeletedFalseOrderByChunkIndex(100L)).thenReturn(List.of(oldChunk));
        when(documentRepository.save(any())).thenAnswer(invocation -> {
            AiRagDocumentEntity value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(101L);
            }
            return value;
        });

        var response = service.reindex(100L, request);

        assertEquals(101L, response.documentId());
        assertEquals(2, response.version());
        assertTrue(Boolean.TRUE.equals(existing.getDeleted()));
        assertTrue(Boolean.FALSE.equals(existing.getActive()));
        assertTrue(Boolean.TRUE.equals(oldChunk.getDeleted()));
        assertTrue(Boolean.FALSE.equals(oldChunk.getActive()));
        verify(auditLogService).log(manager, AuditAction.AI_RAG_REINDEX, AuditModule.AI,
                "AI_RAG_DOCUMENT", 101L, "Reindexed an authorized RAG document");
    }

    @Test
    void revokeSoftDeletesDocumentAndChunksAndAuditsActor() {
        User manager = actor("LAB_MANAGER");
        AiRagDocumentEntity existing = document(100L, 1);
        AiRagChunkEntity oldChunk = chunk(100L);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(documentRepository.findByIdAndActiveTrueAndDeletedFalse(100L)).thenReturn(Optional.of(existing));
        when(laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(10L, 7L)).thenReturn(true);
        when(chunkRepository.findByDocumentIdAndDeletedFalseOrderByChunkIndex(100L)).thenReturn(List.of(oldChunk));

        service.revoke(100L);

        assertTrue(Boolean.TRUE.equals(existing.getDeleted()));
        assertTrue(Boolean.FALSE.equals(existing.getActive()));
        assertTrue(Boolean.TRUE.equals(oldChunk.getDeleted()));
        assertTrue(Boolean.FALSE.equals(oldChunk.getActive()));
        verify(auditLogService).log(manager, AuditAction.AI_RAG_REVOKE, AuditModule.AI,
                "AI_RAG_DOCUMENT", 100L, "Revoked an authorized RAG document");
    }

    @Test
    void nonOwnerCannotIngestIntoLaboratoryNamespace() {
        User student = actor("STUDENT");
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(student));

        assertThrows(AccessDeniedException.class, () -> service.ingest(labRequest()));

        verify(documentRepository, never()).save(any());
        verify(chunkRepository, never()).saveAll(any());
    }

    @Test
    void ingestsTopicGroupScopeWithoutRequiringProject() {
        User manager = actor("LAB_MANAGER");
        Laboratory lab = new Laboratory();
        lab.setId(10L);
        GroupEntity group = new GroupEntity();
        group.setId(30L);
        group.setLab(lab);
        ResearchTopicEntity topic = new ResearchTopicEntity();
        topic.setId(40L);
        topic.setLab(lab);
        group.setTopic(topic);
        AiRagDocumentIngestRequest request = researchGroupRequest();
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(10L, 7L)).thenReturn(true);
        when(groupRepository.findByIdAndDeletedFalseAndActiveTrue(30L)).thenReturn(Optional.of(group));
        when(documentRepository.existsByNamespaceAndResourceIdAndActiveTrueAndDeletedFalse(
                "research-knowledge", "topic-group-guide")).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> {
            AiRagDocumentEntity document = invocation.getArgument(0);
            document.setId(200L);
            return document;
        });

        service.ingest(request);

        ArgumentCaptor<AiRagDocumentEntity> document = ArgumentCaptor.forClass(AiRagDocumentEntity.class);
        verify(documentRepository).save(document.capture());
        assertEquals(10L, document.getValue().getLabId());
        assertEquals(30L, document.getValue().getGroupId());
        assertNull(document.getValue().getProjectId());
    }

    @Test
    void rejectsTopicGroupOutsideManagedLaboratory() {
        User manager = actor("LAB_MANAGER");
        Laboratory anotherLab = new Laboratory();
        anotherLab.setId(11L);
        GroupEntity group = new GroupEntity();
        group.setId(30L);
        group.setLab(anotherLab);
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(laboratoryRepository.existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(10L, 7L)).thenReturn(true);
        when(groupRepository.findByIdAndDeletedFalseAndActiveTrue(30L)).thenReturn(Optional.of(group));

        assertThrows(AccessDeniedException.class, () -> service.ingest(researchGroupRequest()));

        verify(documentRepository, never()).save(any());
        verify(chunkRepository, never()).saveAll(any());
    }

    private static User actor(String roleName) {
        User actor = new User();
        actor.setId(7L);
        actor.setUsername("manager");
        actor.setStatus(UserStatus.ACTIVE);
        actor.setActive(true);
        actor.setDeleted(false);
        actor.setRoles(Set.of(new Role(roleName, roleName)));
        return actor;
    }

    private static AiRagDocumentEntity document(Long id, int version) {
        AiRagDocumentEntity document = new AiRagDocumentEntity();
        document.setId(id);
        document.setNamespace("lab-knowledge");
        document.setDomain(AiAssistantDomain.LAB);
        document.setResourceId("policy-1");
        document.setDocumentVersion(version);
        document.setSourceType("LAB_POLICY");
        document.setTitle("Safety policy");
        document.setVisibility(AiRagVisibility.LAB_MEMBERS);
        document.setOwnerId(7L);
        document.setLabId(10L);
        document.setContentHash("a".repeat(64));
        return document;
    }

    private static AiRagChunkEntity chunk(Long documentId) {
        AiRagChunkEntity chunk = new AiRagChunkEntity();
        chunk.setDocumentId(documentId);
        chunk.setActive(true);
        chunk.setDeleted(false);
        return chunk;
    }

    private static AiRagDocumentIngestRequest labRequest() {
        AiRagDocumentIngestRequest request = new AiRagDocumentIngestRequest();
        request.setDomain(AiAssistantDomain.LAB);
        request.setResourceId("policy-1");
        request.setVersion(1);
        request.setSourceType("LAB_POLICY");
        request.setTitle("Safety policy");
        request.setContent("First page.\fSecond page.");
        request.setVisibility(AiRagVisibility.LAB_MEMBERS);
        request.setLabId(10L);
        return request;
    }

    private static AiRagDocumentIngestRequest researchGroupRequest() {
        AiRagDocumentIngestRequest request = new AiRagDocumentIngestRequest();
        request.setDomain(AiAssistantDomain.RESEARCH);
        request.setResourceId("topic-group-guide");
        request.setVersion(1);
        request.setSourceType("RESEARCH_REPORT");
        request.setTitle("Topic group guide");
        request.setContent("Authorized group research content.");
        request.setVisibility(AiRagVisibility.GROUP_MEMBERS);
        request.setLabId(10L);
        request.setGroupId(30L);
        return request;
    }
}
