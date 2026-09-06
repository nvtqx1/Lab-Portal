package com.web.labportalbackend.booking.repository;

import com.web.labportalbackend.booking.entity.TimeSlot;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.common.enums.TimeSlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * Repository for TimeSlot entity.
 * Provides custom query methods for efficient slot retrieval and filtering.
 */
@Repository
public interface TimeSlotRepository extends JpaRepository<TimeSlot, Long> {

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$Slot(
                ts.id, ts.startTime, ts.endTime, ts.status)
            FROM TimeSlot ts JOIN ts.lab l
            WHERE l.id = :labId
              AND ts.status = com.web.labportalbackend.common.enums.TimeSlotStatus.AVAILABLE
              AND ts.startTime > :now
              AND ts.active = true AND ts.deleted = false
              AND l.active = true AND l.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
              AND ((:managed = true AND l.manager.id = :actorId)
                   OR (:managed = false AND EXISTS (SELECT m.id FROM Membership m
                                                    WHERE m.user.id = :actorId AND m.laboratory.id = l.id
                                                      AND m.active = true AND m.deleted = false)))
            ORDER BY ts.startTime ASC, ts.id ASC
            """)
    List<AiLabContext.Slot> findAiContextAvailableSlots(
            @Param("actorId") Long actorId,
            @Param("labId") Long labId,
            @Param("managed") boolean managed,
            @Param("now") Instant now,
            @Param("selectedRoleName") String selectedRoleName,
            Pageable pageable);

    /**
     * Find all active time slots for a specific laboratory.
     *
     * @param labId the lab ID
     * @return list of active time slots for the lab
     */
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.lab.id = :labId " +
           "AND ts.endTime >= :now " +
           "AND ts.status NOT IN :hiddenStatuses " +
           "AND ts.deleted = false AND ts.active = true " +
           "ORDER BY ts.startTime ASC")
    List<TimeSlot> findUsableByLabId(
            @Param("labId") Long labId,
            @Param("now") Instant now,
            @Param("hiddenStatuses") List<TimeSlotStatus> hiddenStatuses
    );

    /**
     * Find all active time slots for a lab with a specific status.
     *
     * @param labId the lab ID
     * @param status the status to filter by
     * @return list of time slots matching the criteria
     */
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.lab.id = :labId AND ts.status = :status AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findByLabIdAndStatus(@Param("labId") Long labId, @Param("status") TimeSlotStatus status);

    /**
     * Find overlapping time slots for a lab in a specific time window.
     * Used to detect scheduling conflicts.
     *
     * @param labId the lab ID
     * @param startTime the start time of the window
     * @param endTime the end time of the window
     * @return list of overlapping time slots
     */
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.lab.id = :labId " +
           "AND ts.startTime < :endTime AND ts.endTime > :startTime " +
           "AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findOverlappingSlots(
            @Param("labId") Long labId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Find available time slots within a time range for a lab.
     *
     * @param labId the lab ID
     * @param startTime the start time of the range
     * @param endTime the end time of the range
     * @return list of available time slots
     */
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.lab.id = :labId " +
           "AND ts.status = 'AVAILABLE' " +
           "AND ts.startTime >= :startTime AND ts.endTime <= :endTime " +
           "AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findAvailableSlotsInRange(
            @Param("labId") Long labId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Find a single time slot by ID if it's active.
     *
     * @param id the time slot ID
     * @return Optional containing the time slot if found and active
     */
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.id = :id AND ts.deleted = false AND ts.active = true")
    Optional<TimeSlot> findActiveById(@Param("id") Long id);

    /**
     * Find and lock a time slot for booking. Uses pessimistic write lock at database level.
     * This ensures exclusive access to the slot during high-concurrency booking operations.
     *
     * @param id the time slot ID
     * @return Optional containing the locked time slot if found
     * @throws PessimisticLockingFailureException if lock cannot be acquired within timeout
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"),
            @QueryHint(name = "jakarta.persistence.lock.scope", value = "EXTENDED")
    })
    @Query("SELECT ts FROM TimeSlot ts WHERE ts.id = :id AND ts.deleted = false AND ts.active = true")
    Optional<TimeSlot> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT ts FROM TimeSlot ts WHERE ts.endTime <= :cutoff AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findEndedSlots(@Param("cutoff") Instant cutoff);

    @Query("SELECT ts FROM TimeSlot ts WHERE ts.status IN :statuses " +
           "AND ts.endTime <= :cutoff AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findEndedSessionSlots(
            @Param("statuses") List<TimeSlotStatus> statuses,
            @Param("cutoff") Instant cutoff);

    @Query("SELECT ts FROM TimeSlot ts WHERE ts.status IN :statuses " +
           "AND ts.startTime < :cutoff " +
           "AND ts.deleted = false AND ts.active = true")
    List<TimeSlot> findCheckinExpiredSlots(
            @Param("statuses") List<TimeSlotStatus> statuses,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            SELECT COUNT(ts)
            FROM TimeSlot ts
            WHERE ts.startTime >= :startOfDay
              AND ts.startTime < :startOfNextDay
              AND ts.deleted = false
              AND ts.active = true
            """)
    long countActiveSlotsStartingBetween(
            @Param("startOfDay") Instant startOfDay,
            @Param("startOfNextDay") Instant startOfNextDay
    );

    @Query("""
            SELECT COUNT(ts)
            FROM TimeSlot ts
            WHERE ts.lab.id = :labId
              AND ts.startTime >= :startOfDay
              AND ts.startTime < :startOfNextDay
              AND ts.deleted = false
              AND ts.active = true
            """)
    long countActiveSlotsStartingBetweenAndLabId(
            @Param("labId") Long labId,
            @Param("startOfDay") Instant startOfDay,
            @Param("startOfNextDay") Instant startOfNextDay
    );

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$Slot(ts.id, ts.startTime, ts.endTime, ts.status)
            FROM TimeSlot ts JOIN ts.lab l
            WHERE ts.id = :slotId AND l.id = :labId
              AND ts.active = true AND ts.deleted = false AND l.active = true AND l.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
              AND ((:managed = true AND l.manager.id = :actorId
                   AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                               WHERE roleActor.id = :actorId AND r.name = :selectedRoleName))
                   OR (:managed = false AND EXISTS (SELECT m.id FROM Membership m
                                                    WHERE m.user.id = :actorId AND m.laboratory.id = l.id
                                                      AND m.active = true AND m.deleted = false)))
              AND (:draft = false OR (ts.status = com.web.labportalbackend.common.enums.TimeSlotStatus.AVAILABLE
                                      AND ts.startTime > :now
                                      AND NOT EXISTS (SELECT b.id FROM Booking b WHERE b.user.id = :actorId
                                                      AND b.timeSlot.id = ts.id AND b.active = true AND b.deleted = false
                                                      AND b.status NOT IN (com.web.labportalbackend.common.enums.BookingStatus.CANCELLED,
                                                                           com.web.labportalbackend.common.enums.BookingStatus.CANCELLED_BY_STUDENT,
                                                                           com.web.labportalbackend.common.enums.BookingStatus.CANCELLED_BY_MANAGER,
                                                                           com.web.labportalbackend.common.enums.BookingStatus.REJECTED))))
            """)
    Optional<AiLabContext.Slot> findAiContextSlot(
            @Param("actorId") Long actorId, @Param("labId") Long labId,
            @Param("slotId") Long slotId, @Param("managed") boolean managed,
            @Param("draft") boolean draft, @Param("now") Instant now,
            @Param("selectedRoleName") String selectedRoleName);

    @Query("""
            SELECT COUNT(ts) FROM TimeSlot ts JOIN ts.lab l
            WHERE l.id = :labId AND l.manager.id = :actorId AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    long countAiContextManagedSlots(@Param("actorId") Long actorId, @Param("labId") Long labId,
                                    @Param("selectedRoleName") String selectedRoleName);

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$Slot(
                ts.id, ts.startTime, ts.endTime, ts.status)
            FROM TimeSlot ts JOIN ts.lab l
            WHERE l.id = :labId AND l.manager.id = :actorId
              AND ts.endTime >= :now
              AND ts.active = true AND ts.deleted = false
              AND l.active = true AND l.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            ORDER BY ts.startTime ASC, ts.id ASC
            """)
    List<AiLabContext.Slot> findAiContextManagedFutureSlots(
            @Param("actorId") Long actorId,
            @Param("labId") Long labId,
            @Param("now") Instant now,
            @Param("selectedRoleName") String selectedRoleName,
            Pageable pageable);
}
