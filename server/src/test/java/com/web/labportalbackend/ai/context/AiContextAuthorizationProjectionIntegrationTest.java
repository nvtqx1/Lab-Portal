package com.web.labportalbackend.ai.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.web.labportalbackend.admin.audit.entity.AuditLog;
import com.web.labportalbackend.admin.audit.enums.AuditAction;
import com.web.labportalbackend.admin.audit.enums.AuditModule;
import com.web.labportalbackend.admin.audit.repository.AuditLogRepository;
import com.web.labportalbackend.auth.entity.Role;
import com.web.labportalbackend.auth.entity.User;
import com.web.labportalbackend.auth.repository.UserRepository;
import com.web.labportalbackend.booking.entity.Booking;
import com.web.labportalbackend.booking.entity.TimeSlot;
import com.web.labportalbackend.booking.repository.BookingRepository;
import com.web.labportalbackend.booking.repository.TimeSlotRepository;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.common.enums.LabStatus;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import com.web.labportalbackend.common.enums.UserStatus;
import com.web.labportalbackend.lab.entity.Laboratory;
import com.web.labportalbackend.lab.entity.Membership;
import com.web.labportalbackend.lab.repository.LaboratoryRepository;
import com.web.labportalbackend.research.entity.GroupEntity;
import com.web.labportalbackend.research.entity.GroupMemberEntity;
import com.web.labportalbackend.research.entity.MilestoneEntity;
import com.web.labportalbackend.research.entity.ProjectEntity;
import com.web.labportalbackend.research.entity.ReportEntity;
import com.web.labportalbackend.research.entity.TaskEntity;
import com.web.labportalbackend.research.enums.GroupRole;
import com.web.labportalbackend.research.enums.MilestoneStatus;
import com.web.labportalbackend.research.enums.ProjectStatus;
import com.web.labportalbackend.research.enums.ReportStatus;
import com.web.labportalbackend.research.enums.TaskStatus;
import com.web.labportalbackend.research.repository.GroupMemberRepository;
import com.web.labportalbackend.research.repository.GroupRepository;
import com.web.labportalbackend.research.repository.MilestoneRepository;
import com.web.labportalbackend.research.repository.ProjectRepository;
import com.web.labportalbackend.research.repository.ReportRepository;
import com.web.labportalbackend.research.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class AiContextAuthorizationProjectionIntegrationTest {
    private static final String STUDENT_ROLE = "STUDENT";
    private static final String MANAGER_ROLE = "LAB_MANAGER";
    @Autowired EntityManager entityManager;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository auditLogs;
    @Autowired LaboratoryRepository labs;
    @Autowired BookingRepository bookings;
    @Autowired TimeSlotRepository slots;
    @Autowired GroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired ReportRepository reports;
    @Autowired MilestoneRepository milestones;
    @Autowired TaskRepository tasks;

    @Test
    void adminProjectionRequiresCurrentAdminActor() {
        User admin = user("admin");
        Role role = new Role("ADMIN", "admin");
        entityManager.persist(role);
        admin.addRole(role);
        users.saveAndFlush(admin);
        users.saveAndFlush(user("student"));
        assertEquals(2, users.findAiContextSystemSummary(admin.getId()).registeredUserCount());
        admin.setActive(false);
        users.saveAndFlush(admin);
        assertFalse(users.existsAiContextAdminActor(admin.getId()));
        assertEquals(0, users.findAiContextSystemSummary(admin.getId()).registeredUserCount());

        admin.setActive(true);
        admin.getRoles().clear();
        users.saveAndFlush(admin);
        assertFalse(users.existsAiContextAdminActor(admin.getId()));
    }

    @Test
    void adminTargetUserProjectionRequiresCurrentAdmin() {
        User admin = users.saveAndFlush(user("audit-admin"));
        User target = users.saveAndFlush(user("audit-target"));
        Role adminRole = new Role("ADMIN", "admin");
        entityManager.persist(adminRole);
        admin.addRole(adminRole);
        users.saveAndFlush(admin);

        assertTrue(users.findAiContextTarget(admin.getId(), target.getId()).isPresent());

        admin.removeRole(adminRole);
        users.saveAndFlush(admin);
        assertTrue(users.findAiContextTarget(admin.getId(), target.getId()).isEmpty());
    }

    @Test
    void aiAuditBucketsExcludeSoftDeletedAuditLogs() {
        User admin = users.saveAndFlush(user("audit-bucket-admin"));
        Role adminRole = new Role("ADMIN", "admin");
        entityManager.persist(adminRole);
        admin.addRole(adminRole);
        users.saveAndFlush(admin);

        AuditLog active = auditLog(admin);
        active.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        auditLogs.saveAndFlush(active);
        AuditLog deleted = auditLog(admin);
        deleted.setCreatedAt(Instant.parse("2026-08-01T11:00:00Z"));
        deleted.setDeleted(true);
        auditLogs.saveAndFlush(deleted);

        var buckets = auditLogs.findAiContextAuditBuckets(admin.getId(),
                Instant.parse("2026-08-01T00:00:00Z"), PageRequest.of(0, 20));

        assertEquals(1, buckets.size());
        assertEquals(1L, buckets.get(0).getCount());
    }

    @Test
    void managedLabAuthorizationAnchorFailsClosedWhenCurrentStateChanges() {
        User manager = users.saveAndFlush(user("lab-manager"));
        Role managerRole = new Role("LAB_MANAGER", "lab manager");
        entityManager.persist(managerRole);
        manager.addRole(managerRole);
        users.saveAndFlush(manager);
        User replacement = users.saveAndFlush(user("replacement-manager"));
        Laboratory lab = lab("Managed Lab");
        lab.setManager(manager);
        labs.saveAndFlush(lab);

        assertTrue(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));

        lab.setManager(replacement);
        labs.saveAndFlush(lab);
        assertFalse(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));

        lab.setManager(manager);
        labs.saveAndFlush(lab);
        manager.setActive(false);
        users.saveAndFlush(manager);
        assertFalse(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));

        manager.setActive(true);
        users.saveAndFlush(manager);
        lab.setActive(false);
        labs.saveAndFlush(lab);
        assertFalse(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));

        lab.setActive(true);
        lab.setDeleted(true);
        labs.saveAndFlush(lab);
        assertFalse(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));
    }

    @Test
    void managedLabAnchorFailsClosedWhenManagerRoleIsRevoked() {
        User manager = users.saveAndFlush(user("role-revoked-manager"));
        Role managerRole = new Role("LAB_MANAGER", "lab manager");
        entityManager.persist(managerRole);
        manager.addRole(managerRole);
        users.saveAndFlush(manager);
        Laboratory lab = lab("Managed Lab");
        lab.setManager(manager);
        labs.saveAndFlush(lab);

        assertTrue(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));

        manager.removeRole(managerRole);
        users.saveAndFlush(manager);

        assertFalse(labs.existsAiContextManagedLab(manager.getId(), lab.getId(), MANAGER_ROLE));
    }

    @Test
    void managedLabProjectionsRequireCurrentManagerRole() {
        User manager = users.saveAndFlush(user("managed-projection-manager"));
        Role managerRole = new Role("LAB_MANAGER", "lab manager");
        entityManager.persist(managerRole);
        manager.addRole(managerRole);
        users.saveAndFlush(manager);
        Laboratory lab = labs.saveAndFlush(lab("Managed Projection Lab"));
        lab.setManager(manager);
        labs.saveAndFlush(lab);
        TimeSlot slot = new TimeSlot(lab, Instant.parse("2026-08-07T09:00:00Z"),
                Instant.parse("2026-08-07T10:00:00Z"), 1, TimeSlotStatus.AVAILABLE);
        entityManager.persist(slot);
        entityManager.flush();
        Instant managedReadAt = Instant.parse("2026-08-07T08:00:00Z");

        assertTrue(slots.findAiContextSlot(manager.getId(), lab.getId(), slot.getId(), true, false, Instant.now(), MANAGER_ROLE).isPresent());
        assertEquals(1, slots.countAiContextManagedSlots(manager.getId(), lab.getId(), MANAGER_ROLE));
        assertEquals(List.of(slot.getId()), slots.findAiContextManagedFutureSlots(
                        manager.getId(), lab.getId(), managedReadAt, MANAGER_ROLE, PageRequest.of(0, 50))
                .stream().map(AiLabContext.Slot::id).toList());

        manager.removeRole(managerRole);
        users.saveAndFlush(manager);

        assertFalse(slots.findAiContextSlot(manager.getId(), lab.getId(), slot.getId(), true, false, Instant.now(), MANAGER_ROLE).isPresent());
        assertEquals(0, slots.countAiContextManagedSlots(manager.getId(), lab.getId(), MANAGER_ROLE));
        assertTrue(slots.findAiContextManagedFutureSlots(
                manager.getId(), lab.getId(), managedReadAt, MANAGER_ROLE, PageRequest.of(0, 50)).isEmpty());
    }

    @Test
    void checkinProjectionRequiresCurrentOwnerStateAndResolverDerivedInclusiveEnd() {
        User actor = grantRole(users.saveAndFlush(user("booking-owner")), STUDENT_ROLE);
        User anotherActor = users.saveAndFlush(user("other-owner"));
        Laboratory lab = labs.saveAndFlush(lab("Checkin Lab"));
        Laboratory otherLab = labs.saveAndFlush(lab("Other Lab"));
        Instant start = Instant.parse("2026-08-07T09:00:00Z");
        TimeSlot slot = new TimeSlot(lab, start, start.plusSeconds(3600), 1, TimeSlotStatus.AVAILABLE);
        entityManager.persist(slot);
        Booking booking = new Booking(actor, lab, slot, start, start.plusSeconds(3600), BookingStatus.APPROVED, "test", 1);
        bookings.saveAndFlush(booking);
        Instant endInclusive = start.plusSeconds(900);
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start.minusSeconds(1), endInclusive, STUDENT_ROLE).isPresent());
        assertTrue(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        assertTrue(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), endInclusive, endInclusive, STUDENT_ROLE).isPresent());
        // The query accepts only the resolver-supplied end; a later/longer config cannot widen this read.
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), endInclusive.plusSeconds(1), endInclusive, STUDENT_ROLE).isPresent());
        assertFalse(bookings.findAiContextCheckinBooking(anotherActor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), otherLab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        booking.setStatus(BookingStatus.PENDING);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        booking.setStatus(BookingStatus.APPROVED);
        booking.setLab(otherLab);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), otherLab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        booking.setLab(lab);
        booking.setActive(false);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        booking.setActive(true);
        booking.setDeleted(true);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        booking.setDeleted(false);
        lab.setActive(false);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), start, endInclusive, STUDENT_ROLE).isPresent());
        lab.setActive(true);
        slot.setStatus(TimeSlotStatus.CANCELLED);
        entityManager.flush();
        assertFalse(bookings.findAiContextCheckinBooking(actor.getId(), lab.getId(), booking.getId(), endInclusive, endInclusive, STUDENT_ROLE).isPresent());
    }

    @Test
    void ownBookingAndMemberSlotDraftProjectionFamiliesRequireCurrentMembershipAndNoExistingBooking() {
        User actor = grantRole(users.saveAndFlush(user("lab-member-projection")), STUDENT_ROLE);
        Laboratory lab = labs.saveAndFlush(lab("Member Projection Lab"));
        entityManager.persist(new Membership(actor, lab, "MEMBER"));
        Instant future = Instant.now().plusSeconds(3600);
        TimeSlot slot = new TimeSlot(lab, future, future.plusSeconds(3600), 1, TimeSlotStatus.AVAILABLE);
        entityManager.persist(slot);
        entityManager.flush();

        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, false, Instant.now(), STUDENT_ROLE).isPresent());
        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, true, Instant.now(), STUDENT_ROLE).isPresent());

        Booking booking = bookings.saveAndFlush(new Booking(actor, lab, slot, future, future.plusSeconds(3600),
                BookingStatus.PENDING, "test", 1));
        assertTrue(bookings.findAiContextOwnBooking(actor.getId(), lab.getId(), booking.getId(), STUDENT_ROLE).isPresent());
        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, true, Instant.now(), STUDENT_ROLE).isEmpty());

        actor.setActive(false);
        users.saveAndFlush(actor);
        assertTrue(bookings.findAiContextOwnBooking(actor.getId(), lab.getId(), booking.getId(), STUDENT_ROLE).isEmpty());
        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, false, Instant.now(), STUDENT_ROLE).isEmpty());
    }

    @Test
    void selectedGroupProjectionExcludesAccessibleProjectSiblings() {
        User actor = grantRole(users.saveAndFlush(user("researcher")), STUDENT_ROLE);
        Laboratory lab = labs.saveAndFlush(lab("Research Lab"));
        GroupEntity selected = groups.saveAndFlush(group(lab, actor, "selected"));
        ProjectEntity project = projects.saveAndFlush(project(lab, selected));
        selected.setProject(project);
        groups.saveAndFlush(selected);
        GroupEntity sibling = groups.saveAndFlush(group(lab, actor, "sibling"));
        sibling.setProject(project);
        groups.saveAndFlush(sibling);
        members.saveAndFlush(member(selected, actor));
        members.saveAndFlush(member(sibling, actor));
        MilestoneEntity selectedMilestone = milestones.saveAndFlush(milestone(project, selected, "selected milestone"));
        MilestoneEntity siblingMilestone = milestones.saveAndFlush(milestone(project, sibling, "sibling milestone"));
        TaskEntity selectedTask = tasks.saveAndFlush(task(project, selected, selectedMilestone, actor, "selected task"));
        tasks.saveAndFlush(task(project, sibling, siblingMilestone, actor, "sibling task"));
        assertEquals(Set.of(selected.getId()), groups.findAiContextGroups(actor.getId(), project.getId(), selected.getId(), PageRequest.of(0, 20), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertEquals(Set.of(selectedMilestone.getId()), milestones.findAiContextMilestones(actor.getId(), project.getId(), selected.getId(), null, null, PageRequest.of(0, 20), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertEquals(Set.of(selectedTask.getId()), tasks.findAiContextTasks(actor.getId(), project.getId(), selected.getId(), null, null, PageRequest.of(0, 25), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
    }

    @Test
    void groupProjectionsFailClosedWhenSelectedProjectIsInactiveDeletedOrInAnotherLab() {
        User actor = grantRole(users.saveAndFlush(user("selected-project-state-actor")), STUDENT_ROLE);
        Laboratory lab = labs.saveAndFlush(lab("Selected Project State Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "selected-project-state-group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        members.saveAndFlush(member(group, actor));
        entityManager.flush();

        assertGroupProjectionPresent(actor.getId(), project.getId(), group.getId());

        project.setActive(false);
        entityManager.flush();
        assertGroupProjectionAbsent(actor.getId(), project.getId(), group.getId());

        project.setActive(true);
        project.setDeleted(true);
        entityManager.flush();
        assertGroupProjectionAbsent(actor.getId(), project.getId(), group.getId());

        project.setDeleted(false);
        Laboratory otherLab = labs.saveAndFlush(lab("Selected Project Different Lab"));
        project.setLab(otherLab);
        entityManager.flush();
        assertGroupProjectionAbsent(actor.getId(), project.getId(), group.getId());
    }

    @Test
    void groupProjectionsAcceptCoherentReverseCanonicalRelation() {
        User actor = grantRole(users.saveAndFlush(user("reverse-canonical-group-actor")), STUDENT_ROLE);
        Laboratory lab = labs.saveAndFlush(lab("Reverse Canonical Group Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "reverse-canonical-group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        members.saveAndFlush(member(group, actor, GroupRole.MEMBER));
        entityManager.flush();

        assertNull(group.getProject());
        assertGroupProjectionPresent(actor.getId(), project.getId(), group.getId());
    }

    @Test
    void activeMemberReadsEveryCanonicalTaskInOwnGroupButNotAfterMilestoneReparenting() {
        User actor = grantRole(users.saveAndFlush(user("member-reader")), STUDENT_ROLE);
        User assignee = users.saveAndFlush(user("member-reader-assignee"));
        Laboratory lab = labs.saveAndFlush(lab("Member Read Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "member group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        members.saveAndFlush(member(group, actor, GroupRole.MEMBER));
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "member milestone"));
        TaskEntity task = tasks.saveAndFlush(task(project, group, milestone, assignee, "other member task"));

        assertTrue(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertEquals(Set.of(task.getId()), tasks.findAiContextTasks(actor.getId(), project.getId(), group.getId(),
                null, null, PageRequest.of(0, 25), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));

        GroupEntity reparented = groups.saveAndFlush(group(lab, actor, "reparented group"));
        reparented.setProject(project);
        groups.saveAndFlush(reparented);
        milestone.setGroup(reparented);
        entityManager.flush();

        assertFalse(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertTrue(tasks.findAiContextTasks(actor.getId(), project.getId(), group.getId(), null, null,
                PageRequest.of(0, 25), STUDENT_ROLE).isEmpty());
    }

    @Test
    void selectedTaskAndReportProjectionsFailClosedForRevokedMembershipAndBrokenParents() {
        User actor = grantRole(users.saveAndFlush(user("research-leader")), STUDENT_ROLE);
        User submitter = users.saveAndFlush(user("report-submitter"));
        Laboratory lab = labs.saveAndFlush(lab("Research Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "selected"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        GroupMemberEntity membership = members.saveAndFlush(member(group, actor));
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "selected milestone"));
        TaskEntity task = tasks.saveAndFlush(task(project, group, milestone, submitter, "selected task"));
        ReportEntity report = reports.saveAndFlush(report(project, group, milestone, task, submitter));

        assertTrue(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertTrue(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE));
        assertEquals(report.getContentDone(), reports.findAiContextReport(
                actor.getId(), project.getId(), report.getId(), STUDENT_ROLE).orElseThrow().contentDone());

        membership.setActive(false);
        entityManager.flush();
        assertFalse(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertFalse(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE));
        assertTrue(reports.findAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE).isEmpty());

        membership.setActive(true);
        task.setProjectId(project.getId() + 1000L);
        entityManager.flush();
        assertFalse(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertFalse(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE));
        assertTrue(reports.findAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE).isEmpty());
    }

    @Test
    void selectedTaskAndReportDependentProjectionsReassertCurrentAnchorState() {
        User actor = grantRole(users.saveAndFlush(user("dependent-projection-actor")), STUDENT_ROLE);
        User submitter = users.saveAndFlush(user("dependent-projection-submitter"));
        Laboratory lab = labs.saveAndFlush(lab("Dependent Projection Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "selected"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        members.saveAndFlush(member(group, actor));
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "selected milestone"));
        TaskEntity task = tasks.saveAndFlush(task(project, group, milestone, submitter, "selected task"));
        ReportEntity report = reports.saveAndFlush(report(project, group, milestone, task, submitter));

        assertEquals(Set.of(milestone.getId()), milestones.findAiContextMilestones(actor.getId(), project.getId(), null,
                task.getId(), null, PageRequest.of(0, 20), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertEquals(Set.of(task.getId()), tasks.findAiContextTasks(actor.getId(), project.getId(), null,
                null, report.getId(), PageRequest.of(0, 25), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));

        task.setDeleted(true);
        entityManager.flush();
        assertTrue(milestones.findAiContextMilestones(actor.getId(), project.getId(), null,
                task.getId(), null, PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());

        task.setDeleted(false);
        report.setMilestoneId(milestone.getId() + 1000L);
        entityManager.flush();
        assertTrue(tasks.findAiContextTasks(actor.getId(), project.getId(), null,
                null, report.getId(), PageRequest.of(0, 25), STUDENT_ROLE).isEmpty());
    }

    @Test
    void researchManagerProjectionsAndAnchorsRequireCurrentManagerRole() {
        User manager = users.saveAndFlush(user("research-projection-manager"));
        User worker = users.saveAndFlush(user("research-projection-worker"));
        Role managerRole = new Role("LAB_MANAGER", "lab manager");
        entityManager.persist(managerRole);
        manager.addRole(managerRole);
        users.saveAndFlush(manager);
        Laboratory lab = labs.saveAndFlush(lab("Research Manager Lab"));
        lab.setManager(manager);
        labs.saveAndFlush(lab);
        GroupEntity group = groups.saveAndFlush(group(lab, manager, "managed group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "managed milestone"));
        TaskEntity task = tasks.saveAndFlush(task(project, group, milestone, worker, "managed task"));
        ReportEntity report = reports.saveAndFlush(report(project, group, milestone, task, worker));

        assertTrue(projects.findAiContextProject(manager.getId(), lab.getId(), project.getId(), MANAGER_ROLE).isPresent());
        assertEquals(Set.of(group.getId()), groups.findAiContextGroups(manager.getId(), project.getId(), null,
                PageRequest.of(0, 20), MANAGER_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertEquals(Set.of(milestone.getId()), milestones.findAiContextMilestones(manager.getId(), project.getId(), null,
                null, null, PageRequest.of(0, 20), MANAGER_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertEquals(Set.of(task.getId()), tasks.findAiContextTasks(manager.getId(), project.getId(), null,
                null, null, PageRequest.of(0, 25), MANAGER_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertTrue(tasks.existsAiContextTask(manager.getId(), project.getId(), task.getId(), MANAGER_ROLE));
        assertTrue(reports.existsAiContextReport(manager.getId(), project.getId(), report.getId(), MANAGER_ROLE));
        assertTrue(reports.findAiContextReport(manager.getId(), project.getId(), report.getId(), MANAGER_ROLE).isPresent());

        manager.removeRole(managerRole);
        users.saveAndFlush(manager);

        assertFalse(projects.findAiContextProject(manager.getId(), lab.getId(), project.getId(), MANAGER_ROLE).isPresent());
        assertTrue(groups.findAiContextGroups(manager.getId(), project.getId(), null, PageRequest.of(0, 20), MANAGER_ROLE).isEmpty());
        assertTrue(milestones.findAiContextMilestones(manager.getId(), project.getId(), null,
                null, null, PageRequest.of(0, 20), MANAGER_ROLE).isEmpty());
        assertTrue(tasks.findAiContextTasks(manager.getId(), project.getId(), null,
                null, null, PageRequest.of(0, 25), MANAGER_ROLE).isEmpty());
        assertFalse(tasks.existsAiContextTask(manager.getId(), project.getId(), task.getId(), MANAGER_ROLE));
        assertFalse(reports.existsAiContextReport(manager.getId(), project.getId(), report.getId(), MANAGER_ROLE));
        assertTrue(reports.findAiContextReport(manager.getId(), project.getId(), report.getId(), MANAGER_ROLE).isEmpty());
    }

    @Test
    void revokedSelectedManagerAssignmentCannotFallBackToAssignedStudentTaskForProjectContext() {
        User actor = grantRole(users.saveAndFlush(user("manager-role-revoked-project-assignee")), STUDENT_ROLE);
        actor = grantRole(actor, MANAGER_ROLE);
        Laboratory lab = labs.saveAndFlush(lab("Manager Role Revoked Project Assignee Lab"));
        lab.setManager(actor);
        labs.saveAndFlush(lab);
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "manager-role-revoked-project-assignee-group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "manager-role-revoked-project-assignee-milestone"));
        tasks.saveAndFlush(task(project, group, milestone, actor, "manager-role-revoked-project-assignee-task"));

        assertTrue(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), MANAGER_ROLE).isPresent());

        lab.setManager(null);
        labs.saveAndFlush(lab);

        assertFalse(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), MANAGER_ROLE).isPresent());
        assertTrue(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), STUDENT_ROLE).isPresent());
    }

    @Test
    void conflictingBidirectionalProjectGroupRelationFailsClosedAcrossAiContextQueries() {
        User actor = grantRole(users.saveAndFlush(user("conflicting-project-group-actor")), STUDENT_ROLE);
        User submitter = users.saveAndFlush(user("conflicting-project-group-submitter"));
        Laboratory lab = labs.saveAndFlush(lab("Conflicting Project Group Lab"));
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "conflicting-project-group"));
        ProjectEntity selectedProject = projects.saveAndFlush(project(lab, group));
        ProjectEntity conflictingProject = projects.saveAndFlush(ProjectEntity.builder()
                .lab(lab).title("Conflicting Project").status(ProjectStatus.ONGOING).build());
        group.setProject(conflictingProject);
        groups.saveAndFlush(group);
        members.saveAndFlush(member(group, actor));
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(selectedProject, group, "conflicting milestone"));
        TaskEntity task = tasks.saveAndFlush(task(selectedProject, group, milestone, submitter, "conflicting task"));
        ReportEntity report = reports.saveAndFlush(report(selectedProject, group, milestone, task, submitter));
        entityManager.flush();

        assertTrue(members.findActiveGroupIdsByProjectIdAndUserIdAndRole(selectedProject.getId(), actor.getId(),
                GroupRole.LEADER).isEmpty());
        assertTrue(projects.findAiContextProject(actor.getId(), lab.getId(), selectedProject.getId(), STUDENT_ROLE).isEmpty());
        assertTrue(groups.findAiContextGroups(actor.getId(), selectedProject.getId(), group.getId(),
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertTrue(groups.findAiContextGroup(actor.getId(), selectedProject.getId(), group.getId(), STUDENT_ROLE).isEmpty());
        assertTrue(milestones.findAiContextMilestones(actor.getId(), selectedProject.getId(), group.getId(), null,
                null, PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertTrue(tasks.findAiContextTasks(actor.getId(), selectedProject.getId(), group.getId(), null,
                null, PageRequest.of(0, 25), STUDENT_ROLE).isEmpty());
        assertFalse(tasks.existsAiContextTask(actor.getId(), selectedProject.getId(), task.getId(), STUDENT_ROLE));
        assertFalse(reports.existsAiContextReport(actor.getId(), selectedProject.getId(), report.getId(), STUDENT_ROLE));
    }

    @Test
    void selectedStudentRoleRevocationFailsClosedWhenManagerRoleRemains() {
        User actor = grantRole(users.saveAndFlush(user("dual-role-actor")), STUDENT_ROLE);
        actor = grantRole(actor, MANAGER_ROLE);
        User submitter = users.saveAndFlush(user("dual-role-submitter"));
        Laboratory lab = labs.saveAndFlush(lab("Dual Role Lab"));
        lab.setManager(actor);
        labs.saveAndFlush(lab);
        entityManager.persist(new Membership(actor, lab, "MEMBER"));
        TimeSlot slot = new TimeSlot(lab, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                1, TimeSlotStatus.AVAILABLE);
        entityManager.persist(slot);
        GroupEntity group = groups.saveAndFlush(group(lab, actor, "dual-role-group"));
        ProjectEntity project = projects.saveAndFlush(project(lab, group));
        group.setProject(project);
        groups.saveAndFlush(group);
        members.saveAndFlush(member(group, actor));
        MilestoneEntity milestone = milestones.saveAndFlush(milestone(project, group, "dual-role-milestone"));
        TaskEntity task = tasks.saveAndFlush(task(project, group, milestone, submitter, "dual-role-task"));
        ReportEntity report = reports.saveAndFlush(report(project, group, milestone, task, submitter));
        entityManager.flush();

        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, false,
                Instant.now(), STUDENT_ROLE).isPresent());
        assertTrue(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), STUDENT_ROLE).isPresent());
        assertFalse(groups.findAiContextGroups(actor.getId(), project.getId(), group.getId(),
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertFalse(milestones.findAiContextMilestones(actor.getId(), project.getId(), group.getId(), null, null,
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertTrue(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertTrue(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE));

        Role studentRole = actor.getRoles().stream().filter(role -> STUDENT_ROLE.equals(role.getName())).findFirst().orElseThrow();
        actor.removeRole(studentRole);
        users.saveAndFlush(actor);

        assertFalse(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), false, false,
                Instant.now(), STUDENT_ROLE).isPresent());
        assertFalse(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), STUDENT_ROLE).isPresent());
        assertTrue(groups.findAiContextGroups(actor.getId(), project.getId(), group.getId(),
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertTrue(milestones.findAiContextMilestones(actor.getId(), project.getId(), group.getId(), null, null,
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertFalse(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), STUDENT_ROLE));
        assertFalse(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), STUDENT_ROLE));

        assertTrue(slots.findAiContextSlot(actor.getId(), lab.getId(), slot.getId(), true, false,
                Instant.now(), MANAGER_ROLE).isPresent());
        assertTrue(projects.findAiContextProject(actor.getId(), lab.getId(), project.getId(), MANAGER_ROLE).isPresent());
        assertTrue(tasks.existsAiContextTask(actor.getId(), project.getId(), task.getId(), MANAGER_ROLE));
        assertTrue(reports.existsAiContextReport(actor.getId(), project.getId(), report.getId(), MANAGER_ROLE));
    }

    private static User user(String username) { User user = new User(); user.setUsername(username); user.setEmail(username + "@test.local"); user.setPassword("hash"); user.setStatus(UserStatus.ACTIVE); return user; }
    private User grantRole(User actor, String name) { Role role = new Role(name, name); entityManager.persist(role); actor.addRole(role); return users.saveAndFlush(actor); }
    private void assertGroupProjectionPresent(Long actorId, Long projectId, Long groupId) {
        assertEquals(Set.of(groupId), groups.findAiContextGroups(actorId, projectId, groupId,
                PageRequest.of(0, 20), STUDENT_ROLE).stream().map(value -> value.id()).collect(Collectors.toSet()));
        assertTrue(groups.findAiContextGroup(actorId, projectId, groupId, STUDENT_ROLE).isPresent());
    }

    private void assertGroupProjectionAbsent(Long actorId, Long projectId, Long groupId) {
        assertTrue(groups.findAiContextGroups(actorId, projectId, groupId,
                PageRequest.of(0, 20), STUDENT_ROLE).isEmpty());
        assertTrue(groups.findAiContextGroup(actorId, projectId, groupId, STUDENT_ROLE).isEmpty());
    }

    private static AuditLog auditLog(User actor) { AuditLog log = new AuditLog(); log.setActorId(actor.getId()); log.setActorName(actor.getUsername()); log.setAction(AuditAction.CREATE_LAB); log.setModule(AuditModule.LAB); log.setDescription("audit detail not returned by the summary projection"); return log; }
    private static Laboratory lab(String name) { Laboratory lab = new Laboratory(); lab.setLabName(name); lab.setLocation("Room"); lab.setCapacity(4); lab.setStatus(LabStatus.AVAILABLE); return lab; }
    private static GroupEntity group(Laboratory lab, User leader, String name) { return GroupEntity.builder().lab(lab).leader(leader).name(name).build(); }
    private static ProjectEntity project(Laboratory lab, GroupEntity group) { return ProjectEntity.builder().lab(lab).group(group).title("Project").description("project").status(ProjectStatus.ONGOING).startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31)).build(); }
    private static GroupMemberEntity member(GroupEntity group, User user) { return member(group, user, GroupRole.LEADER); }
    private static GroupMemberEntity member(GroupEntity group, User user, GroupRole role) { return GroupMemberEntity.builder().group(group).user(user).role(role).build(); }
    private static MilestoneEntity milestone(ProjectEntity project, GroupEntity group, String title) { return MilestoneEntity.builder().project(project).group(group).title(title).name(title).status(MilestoneStatus.NOT_STARTED).build(); }
    private static TaskEntity task(ProjectEntity project, GroupEntity group, MilestoneEntity milestone, User assignee, String title) { return TaskEntity.builder().projectId(project.getId()).groupId(group.getId()).milestoneId(milestone.getId()).assigneeId(assignee.getId()).title(title).status(TaskStatus.TODO).build(); }
    private static ReportEntity report(ProjectEntity project, GroupEntity group, MilestoneEntity milestone, TaskEntity task, User submitter) { return ReportEntity.builder().projectId(project.getId()).groupId(group.getId()).milestoneId(milestone.getId()).taskId(task.getId()).submittedById(submitter.getId()).version(1).title("report").contentDone("done").result("result").difficulty("none").nextPlan("next").selfAssessment("ok").fileUrl("file").fileName("file").status(ReportStatus.SUBMITTED).submissionScope("task:" + task.getId()).build(); }
}
