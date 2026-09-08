package com.web.labportalbackend.ai.rag.service.impl;

import com.web.labportalbackend.ai.context.AiAuthorizedContext;
import com.web.labportalbackend.ai.context.AiContextReadDeniedException;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.rag.entity.AiRagChunkEntity;
import com.web.labportalbackend.ai.rag.enums.AiKnowledgeNamespace;
import com.web.labportalbackend.ai.rag.repository.AiRagChunkRepository;
import com.web.labportalbackend.ai.rag.service.AiAuthorizedRetrieval;
import com.web.labportalbackend.ai.rag.service.AiRagRetrievalService;
import com.web.labportalbackend.ai.service.AiAssistantProfile;
import com.web.labportalbackend.ai.service.AiCapabilityDecision;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiRagRetrievalServiceImpl implements AiRagRetrievalService {

    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_AUTHORIZED_CHUNKS = 5;
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private final AiRagChunkRepository chunkRepository;

    public AiRagRetrievalServiceImpl(AiRagChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AiAuthorizedRetrieval retrieve(AiAssistantProfile profile,
                                          Long actorId,
                                          AiAssistantSystemRole selectedRole,
                                          AiAuthorizedContext authorizedContext,
                                          String query) {
        validateAuthority(profile, actorId, selectedRole, authorizedContext);
        String namespace = AiKnowledgeNamespace.forDomain(profile.domain()).value();
        if (!namespace.equals(profile.retrievalNamespace())) {
            throw new AiContextReadDeniedException();
        }
        Set<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return AiAuthorizedRetrieval.empty(namespace);
        }
        List<AiRagChunkEntity> candidates = chunkRepository.findByNamespaceAndActiveTrueAndDeletedFalse(
                namespace, PageRequest.of(0, MAX_CANDIDATES,
                        Sort.by(Sort.Order.desc("documentVersion"), Sort.Order.asc("chunkIndex"))));
        AiCapabilityDecision.ResolvedResource resource = authorizedContext.resource();
        List<AiAuthorizedRetrieval.Chunk> chunks = candidates.stream()
                .filter(candidate -> validMetadata(candidate, profile.domain(), namespace))
                .filter(candidate -> isAuthorized(candidate, actorId, selectedRole, resource))
                .map(candidate -> new RankedChunk(candidate, score(candidate.getContent(), terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(RankedChunk::score).reversed()
                        .thenComparing((RankedChunk value) -> value.chunk().getDocumentVersion(), Comparator.reverseOrder())
                        .thenComparing(value -> value.chunk().getChunkIndex()))
                .limit(MAX_AUTHORIZED_CHUNKS)
                .map(AiRagRetrievalServiceImpl::project)
                .toList();
        return new AiAuthorizedRetrieval(namespace, chunks);
    }

    private static void validateAuthority(AiAssistantProfile profile,
                                          Long actorId,
                                          AiAssistantSystemRole selectedRole,
                                          AiAuthorizedContext authorized) {
        if (profile == null || actorId == null || actorId <= 0 || selectedRole == null
                || authorized == null || authorized.resource() == null
                || !authorized.resource().hasValidIdentityShape()
                || authorized.assistantKey() != profile.key() || authorized.domain() != profile.domain()
                || !profile.allowedSystemRoles().contains(selectedRole)) {
            throw new AiContextReadDeniedException();
        }
    }

    private static boolean validMetadata(AiRagChunkEntity chunk,
                                         com.web.labportalbackend.ai.enums.AiAssistantDomain domain,
                                         String namespace) {
        if (chunk == null || chunk.getDocumentId() == null || chunk.getDocumentId() <= 0
                || chunk.getDomain() != domain || !namespace.equals(chunk.getNamespace())
                || chunk.getVisibility() == null || chunk.getOwnerId() == null || chunk.getOwnerId() <= 0
                || chunk.getResourceId() == null || chunk.getResourceId().isBlank()
                || chunk.getDocumentVersion() == null || chunk.getDocumentVersion() <= 0
                || chunk.getChunkIndex() == null || chunk.getChunkIndex() < 0
                || chunk.getSourceType() == null || chunk.getSourceType().isBlank()
                || chunk.getContent() == null || chunk.getContent().isBlank()) {
            return false;
        }
        return switch (chunk.getVisibility()) {
            case ADMIN_ONLY -> domain == com.web.labportalbackend.ai.enums.AiAssistantDomain.ADMIN
                    && chunk.getLabId() == null && chunk.getProjectId() == null && chunk.getGroupId() == null;
            case LAB_MEMBERS -> chunk.getLabId() != null
                    && chunk.getProjectId() == null && chunk.getGroupId() == null;
            case PROJECT_MEMBERS -> domain == com.web.labportalbackend.ai.enums.AiAssistantDomain.RESEARCH
                    && chunk.getLabId() != null && chunk.getProjectId() != null && chunk.getGroupId() == null;
            case GROUP_MEMBERS -> domain == com.web.labportalbackend.ai.enums.AiAssistantDomain.RESEARCH
                    && chunk.getLabId() != null && chunk.getGroupId() != null;
            case OWNER -> chunk.getGroupId() == null || chunk.getProjectId() != null;
        };
    }

    private static boolean isAuthorized(AiRagChunkEntity chunk,
                                        Long actorId,
                                        AiAssistantSystemRole selectedRole,
                                        AiCapabilityDecision.ResolvedResource resource) {
        if (!matchesDeclaredScope(chunk, resource)) {
            return false;
        }
        return switch (chunk.getVisibility()) {
            case ADMIN_ONLY -> selectedRole == AiAssistantSystemRole.ADMIN;
            case OWNER -> actorId.equals(chunk.getOwnerId());
            case LAB_MEMBERS -> chunk.getLabId().equals(resource.labId());
            case PROJECT_MEMBERS -> chunk.getProjectId().equals(resource.projectId());
            case GROUP_MEMBERS -> chunk.getGroupId().equals(resource.groupId());
        };
    }

    private static boolean matchesDeclaredScope(AiRagChunkEntity chunk,
                                                AiCapabilityDecision.ResolvedResource resource) {
        return (chunk.getLabId() == null || chunk.getLabId().equals(resource.labId()))
                && (chunk.getProjectId() == null || chunk.getProjectId().equals(resource.projectId()))
                && (chunk.getGroupId() == null || chunk.getGroupId().equals(resource.groupId()));
    }

    private static Set<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Matcher matcher = TERM_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find() && terms.size() < 64) {
            terms.add(matcher.group());
        }
        return Set.copyOf(terms);
    }

    private static int score(String content, Set<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return (int) terms.stream().filter(normalized::contains).count();
    }

    private static AiAuthorizedRetrieval.Chunk project(RankedChunk ranked) {
        AiRagChunkEntity chunk = ranked.chunk();
        return new AiAuthorizedRetrieval.Chunk(chunk.getDocumentId(), chunk.getResourceId(),
                chunk.getDocumentVersion(), chunk.getChunkIndex(), chunk.getPageNumber(),
                chunk.getSourceType(), chunk.getContent(), false);
    }

    private record RankedChunk(AiRagChunkEntity chunk, int score) {
    }
}
