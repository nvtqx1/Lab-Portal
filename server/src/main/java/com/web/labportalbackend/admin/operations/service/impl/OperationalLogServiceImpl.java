package com.web.labportalbackend.admin.operations.service.impl;

import com.web.labportalbackend.admin.operations.dto.AiActionOperationalResponse;
import com.web.labportalbackend.admin.operations.dto.AiUsageOperationalResponse;
import com.web.labportalbackend.admin.operations.dto.FaceCheckinOperationalResponse;
import com.web.labportalbackend.admin.operations.dto.OperationalLogPageResponse;
import com.web.labportalbackend.admin.operations.service.OperationalLogService;
import com.web.labportalbackend.ai.entity.AiActionSuggestionEntity;
import com.web.labportalbackend.ai.entity.AiUsageLogEntity;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiResourceType;
import com.web.labportalbackend.ai.repository.AiActionSuggestionRepository;
import com.web.labportalbackend.ai.repository.AiUsageLogRepository;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.face.entity.FaceCheckinLogEntity;
import com.web.labportalbackend.face.enums.FaceCheckinResult;
import com.web.labportalbackend.face.repository.FaceCheckinLogRepository;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OperationalLogServiceImpl implements OperationalLogService {

    private final AiUsageLogRepository aiUsageLogRepository;
    private final AiActionSuggestionRepository aiActionSuggestionRepository;
    private final FaceCheckinLogRepository faceCheckinLogRepository;
    private final UserRepository userRepository;
    private final LaboratoryRepository laboratoryRepository;

    @Override
    @Transactional(readOnly = true)
    public OperationalLogPageResponse<AiUsageOperationalResponse> getAiUsage(
            Long userId, String module, Long labId, Instant from, Instant to, Pageable pageable) {
        requireAdmin();
        validateTimeRange(from, to);
        Page<AiUsageLogEntity> page = aiUsageLogRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = activePredicates(root, cb);
            addEqual(predicates, cb, root.get("userId"), userId);
            addEqual(predicates, cb, root.get("labId"), labId);
            if (StringUtils.hasText(module)) {
                predicates.add(cb.equal(cb.lower(root.get("module")), module.trim().toLowerCase()));
            }
            addTime(predicates, cb, root.get("createdAt"), from, to);
            return cb.and(predicates.toArray(Predicate[]::new));
        }, pageable);
        return page(page, page.getContent().stream().map(this::toAiUsage).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OperationalLogPageResponse<AiActionOperationalResponse> getAiActions(
            Long userId, AiAssistantKey assistantKey, AiResourceType resourceType, Long resourceId,
            Instant from, Instant to, Pageable pageable) {
        requireAdmin();
        validateTimeRange(from, to);
        Specification<AiActionSuggestionEntity> specification = (root, query, cb) -> {
            List<Predicate> predicates = activePredicates(root, cb);
            addEqual(predicates, cb, root.get("requestedById"), userId);
            addEqual(predicates, cb, root.get("assistantKey"), assistantKey);
            addEqual(predicates, cb, root.get("resourceType"), resourceType);
            addEqual(predicates, cb, root.get("resourceId"), resourceId);
            addTime(predicates, cb, root.get("createdAt"), from, to);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<AiActionSuggestionEntity> page = aiActionSuggestionRepository.findAll(specification, pageable);
        return page(page, page.getContent().stream().map(this::toAiAction).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OperationalLogPageResponse<FaceCheckinOperationalResponse> getFaceCheckins(
            Long userId, Long labId, Long bookingId, FaceCheckinResult result,
            Instant from, Instant to, Pageable pageable) {
        User actor = currentUser();
        validateTimeRange(from, to);
        Long scopedLabId = resolveFaceLabScope(actor, labId);
        Specification<FaceCheckinLogEntity> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            addEqual(predicates, cb, root.get("user").get("id"), userId);
            addEqual(predicates, cb, root.get("lab").get("id"), scopedLabId);
            addEqual(predicates, cb, root.get("booking").get("id"), bookingId);
            addEqual(predicates, cb, root.get("result"), result);
            addTime(predicates, cb, root.get("createdAt"), from, to);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<FaceCheckinLogEntity> page = faceCheckinLogRepository.findAll(specification, pageable);
        return page(page, page.getContent().stream().map(this::toFaceCheckin).toList());
    }

    private Long resolveFaceLabScope(User actor, Long requestedLabId) {
        if (actor.hasRole("ADMIN")) {
            return requestedLabId;
        }
        if (!actor.hasRole("LAB_MANAGER")) {
            throw new AccessDeniedException("Operational logs require administrator or laboratory manager access");
        }
        Long managedLabId = laboratoryRepository.findFirstByManagerIdAndDeletedFalse(actor.getId())
                .filter(lab -> Boolean.TRUE.equals(lab.getActive()))
                .map(lab -> lab.getId())
                .orElseThrow(() -> new AccessDeniedException("No active managed laboratory was found"));
        if (requestedLabId != null && !requestedLabId.equals(managedLabId)) {
            throw new AccessDeniedException("Laboratory managers may view only their managed laboratory");
        }
        return managedLabId;
    }

    private void requireAdmin() {
        if (!currentUser().hasRole("ADMIN")) {
            throw new AccessDeniedException("Administrator access is required");
        }
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        return userRepository.findByUsername(authentication.getName())
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .filter(user -> !Boolean.TRUE.equals(user.getDeleted()))
                .orElseThrow(() -> new AccessDeniedException("Authenticated user was not found"));
    }

    private void validateTimeRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    private <T> List<Predicate> activePredicates(jakarta.persistence.criteria.Root<T> root,
                                                  jakarta.persistence.criteria.CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.isTrue(root.get("active")));
        predicates.add(cb.isFalse(root.get("deleted")));
        return predicates;
    }

    private void addEqual(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
                          jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value != null) {
            predicates.add(cb.equal(path, value));
        }
    }

    private void addTime(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
                         jakarta.persistence.criteria.Path<Instant> path, Instant from, Instant to) {
        if (from != null) predicates.add(cb.greaterThanOrEqualTo(path, from));
        if (to != null) predicates.add(cb.lessThanOrEqualTo(path, to));
    }

    private AiUsageOperationalResponse toAiUsage(AiUsageLogEntity log) {
        return new AiUsageOperationalResponse(log.getId(), log.getUserId(), log.getAssistantKey(), log.getModule(),
                log.getLabId(), log.getProjectId(), log.getGroupId(), log.getPromptTokens(),
                log.getCompletionTokens(), log.getStatus(), StringUtils.hasText(log.getErrorMessage()),
                log.getCreatedAt());
    }

    private AiActionOperationalResponse toAiAction(AiActionSuggestionEntity log) {
        return new AiActionOperationalResponse(log.getId(), log.getRequestedById(), log.getAssistantKey(),
                log.getActionType(), log.getResourceType(), log.getResourceId(), log.getStatus(),
                log.getExecutionStatus(), log.getCreatedAt());
    }

    private FaceCheckinOperationalResponse toFaceCheckin(FaceCheckinLogEntity log) {
        return new FaceCheckinOperationalResponse(log.getId(), log.getBooking().getId(), log.getUser().getId(),
                log.getUser().getFullName(), log.getLab().getId(), log.getCheckinMethod(), log.getResult(), log.getFailureReason(),
                log.getCreatedAt());
    }

    private <T, E> OperationalLogPageResponse<T> page(Page<E> page, List<T> items) {
        return new OperationalLogPageResponse<>(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
