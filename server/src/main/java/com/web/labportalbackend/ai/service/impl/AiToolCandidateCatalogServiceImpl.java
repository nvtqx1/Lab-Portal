package com.web.labportalbackend.ai.service.impl;

import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.ai.enums.AiAssistantKey;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiCapability;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.ai.service.AiToolCandidate;
import com.web.labportalbackend.ai.service.AiToolCandidateCatalog;
import com.web.labportalbackend.ai.service.AiToolDefinition;
import com.web.labportalbackend.ai.service.AiToolRegistry;
import com.web.labportalbackend.ai.service.AiResearchToolCandidateResource;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.research.repository.GroupRepository;
import com.web.labportalbackend.research.repository.ProjectRepository;
import com.web.labportalbackend.research.repository.TaskRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiToolCandidateCatalogServiceImpl implements AiToolCandidateCatalog {

    private static final int LAB_LIMIT = 50;
    private static final int BOOKING_LIMIT = 40;
    private static final int RESEARCH_RESOURCE_LIMIT = 20;

    private final AiCurrentActorProvider currentActorProvider;
    private final AiToolRegistry toolRegistry;
    private final LaboratoryRepository laboratoryRepository;
    private final BookingRepository bookingRepository;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;
    private final TaskRepository taskRepository;

    public AiToolCandidateCatalogServiceImpl(AiCurrentActorProvider currentActorProvider,
                                             AiToolRegistry toolRegistry,
                                             LaboratoryRepository laboratoryRepository,
                                             BookingRepository bookingRepository,
                                             ProjectRepository projectRepository,
                                             GroupRepository groupRepository,
                                             TaskRepository taskRepository) {
        this.currentActorProvider = currentActorProvider;
        this.toolRegistry = toolRegistry;
        this.laboratoryRepository = laboratoryRepository;
        this.bookingRepository = bookingRepository;
        this.projectRepository = projectRepository;
        this.groupRepository = groupRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiToolCandidate> candidates() {
        AiCurrentActor actor = currentActorProvider.requireCurrentActor();
        return switch (actor.role()) {
            case ADMIN -> adminCandidates();
            case LAB_MANAGER -> appendResearch(managerCandidates(actor.id()), actor);
            case STUDENT -> appendResearch(studentCandidates(actor.id()), actor);
        };
    }

    private List<AiToolCandidate> adminCandidates() {
        return List.of(
                candidate(AiCapability.ADMIN_SYSTEM_SUMMARY, "Summarize current Lab Portal system status", null),
                candidate(AiCapability.ADMIN_AUDIT_SUMMARY, "Summarize authorized audit activity", null),
                candidate(AiCapability.ADMIN_CONFIG_DRAFT, "Draft a system configuration proposal without applying it", null));
    }

    private List<AiToolCandidate> managerCandidates(Long actorId) {
        Laboratory lab = laboratoryRepository.findFirstByManagerIdAndDeletedFalse(actorId).orElse(null);
        if (lab == null || lab.getId() == null || !Boolean.TRUE.equals(lab.getActive())
                || Boolean.TRUE.equals(lab.getDeleted())) {
            return List.of();
        }
        String labLabel = safeLabel(lab.getLabName(), lab.getId());
        return List.of(
                candidate(AiCapability.LAB_AVAILABLE_SLOTS_READ,
                        "List future available time slots for managed Lab " + labLabel, lab.getId()),
                candidate(AiCapability.LAB_MANAGED_SUMMARY,
                        "List and summarize current time slots in managed Lab " + labLabel, lab.getId()),
                candidate(AiCapability.LAB_SHIFT_CREATE_DRAFT,
                        "Create a confirmation preview for a new time slot in managed Lab " + labLabel,
                        lab.getId()),
                candidate(AiCapability.LAB_POLICY_READ,
                        "Explain booking and check-in policy for Lab " + labLabel, lab.getId()));
    }

    private List<AiToolCandidate> studentCandidates(Long actorId) {
        List<AiToolCandidate> result = new ArrayList<>();
        List<AiLabContext.Laboratory> labs = laboratoryRepository.findAiCandidateLabsForStudent(
                actorId, PageRequest.of(0, LAB_LIMIT));
        for (AiLabContext.Laboratory lab : labs) {
            String labLabel = safeLabel(lab.name(), lab.id());
            result.add(candidate(AiCapability.LAB_AVAILABLE_SLOTS_READ,
                    "List future available time slots for joined Lab " + labLabel, lab.id()));
            result.add(candidate(AiCapability.LAB_POLICY_READ,
                    "Explain booking and check-in policy for joined Lab " + labLabel, lab.id()));
        }
        bookingRepository.findAiCandidateOwnBookings(actorId, PageRequest.of(0, BOOKING_LIMIT))
                .forEach(booking -> result.add(candidate(AiCapability.LAB_OWN_BOOKING_READ,
                        "Show own booking " + booking.id() + " with status " + booking.status()
                                + " starting " + booking.slot().startTime(), booking.id())));
        return List.copyOf(result);
    }

    private AiToolCandidate candidate(AiCapability capability, String description, Long resourceId) {
        return candidate(capability, description, resourceId, null);
    }

    private AiToolCandidate candidate(AiCapability capability,
                                      String description,
                                      Long resourceId,
                                      Long parentResourceId) {
        AiToolDefinition definition = toolRegistry.get(capability);
        if (definition == null) {
            throw new IllegalStateException("Canonical AI tool catalog is incomplete");
        }
        AiAssistantKey assistantKey = switch (definition.domain()) {
            case ADMIN -> AiAssistantKey.ADMIN_ASSISTANT;
            case LAB -> AiAssistantKey.LAB_ASSISTANT;
            case RESEARCH -> AiAssistantKey.RESEARCH_ASSISTANT;
        };
        return new AiToolCandidate(assistantKey, definition.schemaVersion(),
                definition.id(), description,
                new AiToolCandidate.ResourceReference(definition.resourceType(), resourceId),
                definition.parentResourceType() == null ? null
                        : new AiToolCandidate.ResourceReference(definition.parentResourceType(), parentResourceId));
    }

    private List<AiToolCandidate> appendResearch(List<AiToolCandidate> initial, AiCurrentActor actor) {
        List<AiToolCandidate> result = new ArrayList<>(initial);
        String role = actor.role().name();
        var page = PageRequest.of(0, RESEARCH_RESOURCE_LIMIT);
        projectRepository.findAiToolCandidateProjects(actor.id(), role, page).forEach(project ->
                result.add(candidate(AiCapability.RESEARCH_PROJECT_SUMMARY,
                        "Summarize authorized research project " + safeLabel(project.label(), project.id()),
                        project.id())));
        groupRepository.findAiToolCandidateGroups(actor.id(), role, page).forEach(group -> {
            result.add(candidate(AiCapability.RESEARCH_GROUP_SUMMARY,
                    "Summarize authorized research group " + safeLabel(group.label(), group.id()), group.id()));
            if (actor.role() == AiAssistantSystemRole.STUDENT) {
                result.add(candidate(AiCapability.RESEARCH_TASK_PROPOSAL_DRAFT,
                        "Draft a task proposal for research group " + safeLabel(group.label(), group.id()),
                        group.id(), group.projectId()));
            }
        });
        taskRepository.findAiToolCandidateTasks(actor.id(), role, page).forEach(task -> {
            result.add(candidate(AiCapability.RESEARCH_ASSIGNED_TASK_READ,
                    "Read authorized research task " + safeLabel(task.label(), task.id()), task.id()));
            result.add(candidate(AiCapability.RESEARCH_TASK_SUGGESTION_DRAFT,
                    "Draft guidance for research task " + safeLabel(task.label(), task.id()), task.id()));
        });
        if (result.size() > 200) {
            return List.copyOf(result.subList(0, 200));
        }
        return List.copyOf(result);
    }

    private static String safeLabel(String name, Long id) {
        String label = name == null || name.isBlank() ? "#" + id : name.trim();
        return label.length() <= 100 ? label : label.substring(0, 100);
    }
}
