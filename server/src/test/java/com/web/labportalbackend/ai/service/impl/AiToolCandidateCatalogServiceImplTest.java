package com.web.labportalbackend.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.ai.enums.AiAssistantSystemRole;
import com.web.labportalbackend.ai.enums.AiToolId;
import com.web.labportalbackend.ai.service.AiCurrentActor;
import com.web.labportalbackend.ai.service.AiCurrentActorProvider;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.LabStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.research.repository.GroupRepository;
import com.web.labportalbackend.research.repository.ProjectRepository;
import com.web.labportalbackend.research.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiToolCandidateCatalogServiceImplTest {

    @Mock private AiCurrentActorProvider actorProvider;
    @Mock private LaboratoryRepository laboratoryRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private TaskRepository taskRepository;

    private AiToolCandidateCatalogServiceImpl catalog;

    @BeforeEach
    void setUp() {
        catalog = new AiToolCandidateCatalogServiceImpl(actorProvider, new AiToolRegistryServiceImpl(),
                laboratoryRepository, bookingRepository, projectRepository, groupRepository, taskRepository);
    }

    @Test
    void adminReceivesOnlyCurrentGlobalAdminCandidates() {
        when(actorProvider.requireCurrentActor()).thenReturn(new AiCurrentActor(1L, AiAssistantSystemRole.ADMIN));

        var candidates = catalog.candidates();

        assertEquals(List.of(AiToolId.ADMIN_SYSTEM_SUMMARY, AiToolId.ADMIN_AUDIT_SUMMARY,
                AiToolId.ADMIN_CONFIG_DRAFT), candidates.stream().map(candidate -> candidate.toolId()).toList());
        verifyNoInteractions(laboratoryRepository, bookingRepository, projectRepository, groupRepository, taskRepository);
    }

    @Test
    void studentCandidatesContainOnlyJoinedLabsAndOwnBookings() {
        when(actorProvider.requireCurrentActor()).thenReturn(new AiCurrentActor(7L, AiAssistantSystemRole.STUDENT));
        when(laboratoryRepository.findAiCandidateLabsForStudent(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(List.of(new AiLabContext.Laboratory(10L, "AI Lab", LabStatus.AVAILABLE)));
        when(bookingRepository.findAiCandidateOwnBookings(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(List.of(new AiLabContext.OwnBooking(30L, BookingStatus.APPROVED,
                        new AiLabContext.Slot(20L, Instant.parse("2026-09-06T08:00:00Z"),
                                Instant.parse("2026-09-06T10:00:00Z"), TimeSlotStatus.AVAILABLE))));
        when(projectRepository.findAiToolCandidateProjects(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("STUDENT"), any())).thenReturn(List.of());
        when(groupRepository.findAiToolCandidateGroups(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("STUDENT"), any())).thenReturn(List.of());
        when(taskRepository.findAiToolCandidateTasks(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("STUDENT"), any())).thenReturn(List.of());

        var candidates = catalog.candidates();

        assertEquals(List.of(AiToolId.LAB_AVAILABLE_SLOTS_READ, AiToolId.LAB_POLICY_READ,
                        AiToolId.LAB_OWN_BOOKING_READ),
                candidates.stream().map(candidate -> candidate.toolId()).toList());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.description().length() <= 512));
        assertTrue(candidates.stream()
                .filter(candidate -> candidate.toolId() == AiToolId.LAB_POLICY_READ)
                .allMatch(candidate -> candidate.description().contains("authorized knowledge documents")));
    }

    @Test
    void managerCandidatesAreBoundToTheManagedLabOnly() {
        when(actorProvider.requireCurrentActor()).thenReturn(
                new AiCurrentActor(8L, AiAssistantSystemRole.LAB_MANAGER));
        Laboratory lab = new Laboratory();
        lab.setId(10L);
        lab.setLabName("AI Lab");
        lab.setActive(true);
        lab.setDeleted(false);
        when(laboratoryRepository.findFirstByManagerIdAndDeletedFalse(8L)).thenReturn(Optional.of(lab));
        when(projectRepository.findAiToolCandidateProjects(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("LAB_MANAGER"), any())).thenReturn(List.of());
        when(groupRepository.findAiToolCandidateGroups(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("LAB_MANAGER"), any())).thenReturn(List.of());
        when(taskRepository.findAiToolCandidateTasks(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("LAB_MANAGER"), any())).thenReturn(List.of());

        var candidates = catalog.candidates();

        assertEquals(List.of(AiToolId.LAB_AVAILABLE_SLOTS_READ, AiToolId.LAB_MANAGED_SUMMARY,
                        AiToolId.LAB_SHIFT_CREATE_DRAFT, AiToolId.LAB_POLICY_READ),
                candidates.stream().map(candidate -> candidate.toolId()).toList());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.resource().resourceId().equals(10L)));
        assertTrue(candidates.stream()
                .filter(candidate -> candidate.toolId() == AiToolId.LAB_POLICY_READ)
                .allMatch(candidate -> candidate.description().contains("authorized knowledge documents")));
        verifyNoInteractions(bookingRepository);
    }
}
