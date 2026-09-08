package com.web.labportalbackend.booking.repository;

import com.web.labportalbackend.booking.entity.Booking;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.common.enums.BookingStatus;
import com.web.labportalbackend.face.dto.response.FaceCheckinCandidateResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$OwnBooking(
                b.id, b.status,
                new com.web.labportalbackend.ai.context.AiLabContext$Slot(
                    ts.id, ts.startTime, ts.endTime, ts.status))
            FROM Booking b JOIN b.timeSlot ts
            WHERE b.user.id = :actorId
              AND b.active = true AND b.deleted = false
              AND ts.active = true AND ts.deleted = false
            ORDER BY b.startTime DESC, b.id DESC
            """)
    List<AiLabContext.OwnBooking> findAiCandidateOwnBookings(
            @Param("actorId") Long actorId, Pageable pageable);

    @Query("""
            SELECT new com.web.labportalbackend.face.dto.response.FaceCheckinCandidateResponse(
                b.id, u.id, u.fullName, u.email, l.id, l.labName, ts.id,
                b.startTime, b.endTime, b.status)
            FROM Booking b JOIN b.user u JOIN b.lab l JOIN b.timeSlot ts
            WHERE l.manager.id = :managerId
              AND b.status = com.web.labportalbackend.common.enums.BookingStatus.APPROVED
              AND b.active = true AND b.deleted = false
              AND u.active = true AND u.deleted = false
              AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
              AND b.startTime >= :earliestStart
              AND b.startTime <= :latestStart
            ORDER BY b.startTime ASC, b.id ASC
            """)
    List<FaceCheckinCandidateResponse> findFaceCheckinCandidatesForManager(
            @Param("managerId") Long managerId,
            @Param("earliestStart") Instant earliestStart,
            @Param("latestStart") Instant latestStart);

    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.user u
            JOIN FETCH b.lab l
            JOIN FETCH b.timeSlot ts
            WHERE b.id = :bookingId AND l.manager.id = :managerId
              AND b.active = true AND b.deleted = false
              AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
            """)
    java.util.Optional<Booking> findManagerFaceCheckinBooking(
            @Param("managerId") Long managerId, @Param("bookingId") Long bookingId);

    @Query("""
            SELECT b FROM Booking b
            JOIN FETCH b.user u
            JOIN FETCH b.lab l
            JOIN FETCH b.timeSlot ts
            WHERE ts.id = :slotId AND l.manager.id = :managerId
              AND b.status = com.web.labportalbackend.common.enums.BookingStatus.APPROVED
              AND b.active = true AND b.deleted = false
              AND u.active = true AND u.deleted = false
              AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
              AND b.startTime >= :earliestStart
              AND b.startTime <= :latestStart
            ORDER BY b.id ASC
            """)
    List<Booking> findManagerFaceCheckinBookingsBySlot(
            @Param("managerId") Long managerId,
            @Param("slotId") Long slotId,
            @Param("earliestStart") Instant earliestStart,
            @Param("latestStart") Instant latestStart);

    List<Booking> findByUserId(Long userId);

    List<Booking> findByLabId(Long labId);

    List<Booking> findByStatus(BookingStatus status);

    /**
     * Count bookings for a specific time slot.
     * Used for capacity validation.
     *
     * @param slotId the time slot ID
     * @return count of bookings for this slot
     */
    long countByTimeSlotId(Long slotId);

    /**
     * Count confirmed bookings for a specific time slot.
     * Used for accurate capacity validation - only counts CONFIRMED bookings.
     *
     * @param slotId the time slot ID
     * @param status the booking status (usually CONFIRMED)
     * @return count of confirmed bookings for this slot
     */
    long countByTimeSlotIdAndStatus(Long slotId, BookingStatus status);

    /**
     * Find overlapping bookings for a given lab in a specific time window.
     * Used for conflict detection when creating / updating a booking.
     */
    @Query("SELECT b FROM Booking b WHERE b.lab.id = :labId " +
           "AND b.status <> 'CANCELLED' " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Booking> findOverlappingBookings(
            @Param("labId") Long labId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    /**
     * Find bookings by time slot.
     *
     * @param slotId the time slot ID
     * @return list of bookings for the slot
     */
    @Query("SELECT b FROM Booking b WHERE b.timeSlot.id = :slotId AND b.deleted = false AND b.active = true")
    List<Booking> findBySlotId(@Param("slotId") Long slotId);

    @Query("SELECT b FROM Booking b WHERE b.user.id = :userId AND b.deleted = false AND b.active = true ORDER BY b.startTime DESC")
    List<Booking> findActiveByUserId(@Param("userId") Long userId);

    @Query("SELECT b FROM Booking b WHERE b.user.id = :userId " +
           "AND b.endTime > :now AND b.deleted = false AND b.active = true " +
           "ORDER BY b.startTime DESC, b.id DESC")
    List<Booking> findCurrentQrHistoryBookingsForUser(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    @Query("SELECT b FROM Booking b WHERE b.timeSlot.id = :slotId AND b.status IN :statuses AND b.deleted = false AND b.active = true")
    List<Booking> findBySlotIdAndStatusIn(
            @Param("slotId") Long slotId,
            @Param("statuses") List<BookingStatus> statuses
    );

    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.timeSlot.id = :slotId AND b.user.id = :userId AND b.status IN :statuses AND b.deleted = false AND b.active = true")
    boolean existsBySlotIdAndUserIdAndStatusIn(
            @Param("slotId") Long slotId,
            @Param("userId") Long userId,
            @Param("statuses") List<BookingStatus> statuses
    );

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.timeSlot.id = :slotId AND b.status = :status AND b.deleted = false AND b.active = true")
    long countActiveByTimeSlotIdAndStatus(@Param("slotId") Long slotId, @Param("status") BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.timeSlot.id = :slotId AND b.status IN :statuses AND b.deleted = false AND b.active = true")
    long countActiveByTimeSlotIdAndStatusIn(
            @Param("slotId") Long slotId,
            @Param("statuses") List<BookingStatus> statuses
    );

    @Query("""
            SELECT new com.web.labportalbackend.booking.repository.TimeSlotBookingCounts(
                b.timeSlot.id,
                SUM(CASE WHEN b.status IN (
                    com.web.labportalbackend.common.enums.BookingStatus.APPROVED,
                    com.web.labportalbackend.common.enums.BookingStatus.CHECKED_IN
                ) THEN 1 ELSE 0 END),
                SUM(CASE WHEN b.status = com.web.labportalbackend.common.enums.BookingStatus.CHECKED_IN
                    THEN 1 ELSE 0 END),
                SUM(CASE WHEN b.status = com.web.labportalbackend.common.enums.BookingStatus.PENDING_APPROVAL
                    THEN 1 ELSE 0 END)
            )
            FROM Booking b
            WHERE b.timeSlot.id IN :slotIds
              AND b.deleted = false
              AND b.active = true
            GROUP BY b.timeSlot.id
            """)
    List<TimeSlotBookingCounts> findActiveCountsByTimeSlotIds(@Param("slotIds") List<Long> slotIds);

    /**
     * Check if a user has an existing (non-cancelled) booking for a slot.
     * Used for duplicate booking prevention.
     *
     * @param userId the user ID
     * @param slotId the slot ID
     * @return true if user already has a booking for this slot
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.user.id = :userId AND b.timeSlot.id = :slotId " +
           "AND b.status NOT IN ('CANCELLED', 'CANCELLED_BY_STUDENT', 'CANCELLED_BY_MANAGER', 'REJECTED') AND b.deleted = false AND b.active = true")
    boolean existsActiveBookingByUserAndSlot(@Param("userId") Long userId, @Param("slotId") Long slotId);

    @Query("SELECT b FROM Booking b WHERE b.status = :status " +
           "AND b.startTime < :cutoff " +
           "AND b.deleted = false AND b.active = true")
    List<Booking> findNoShowCandidates(
            @Param("status") BookingStatus status,
            @Param("cutoff") Instant cutoff
    );

    @Query("SELECT b FROM Booking b WHERE b.status IN :statuses " +
           "AND b.endTime <= :cutoff AND b.deleted = false AND b.active = true")
    List<Booking> findEndedSessionCandidates(
            @Param("statuses") List<BookingStatus> statuses,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.startTime >= :startOfDay
              AND b.startTime < :startOfNextDay
              AND b.status IN :statuses
              AND b.deleted = false
              AND b.active = true
            """)
    long countActiveBookingsStartingBetweenAndStatusIn(
            @Param("startOfDay") Instant startOfDay,
            @Param("startOfNextDay") Instant startOfNextDay,
            @Param("statuses") List<BookingStatus> statuses
    );

    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.lab.id = :labId
              AND b.startTime >= :startOfDay
              AND b.startTime < :startOfNextDay
              AND b.status IN :statuses
              AND b.deleted = false
              AND b.active = true
            """)
    long countActiveBookingsStartingBetweenAndLabIdAndStatusIn(
            @Param("labId") Long labId,
            @Param("startOfDay") Instant startOfDay,
            @Param("startOfNextDay") Instant startOfNextDay,
            @Param("statuses") List<BookingStatus> statuses
    );

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$OwnBooking(
                b.id, b.status,
                new com.web.labportalbackend.ai.context.AiLabContext$Slot(ts.id, ts.startTime, ts.endTime, ts.status))
            FROM Booking b JOIN b.lab l JOIN b.timeSlot ts
            WHERE b.id = :bookingId AND b.user.id = :actorId AND b.lab.id = :labId AND ts.lab.id = l.id
              AND b.active = true AND b.deleted = false AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    java.util.Optional<AiLabContext.OwnBooking> findAiContextOwnBooking(
            @Param("actorId") Long actorId, @Param("labId") Long labId, @Param("bookingId") Long bookingId,
            @Param("selectedRoleName") String selectedRoleName);

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$OwnBooking(
                b.id, b.status,
                new com.web.labportalbackend.ai.context.AiLabContext$Slot(ts.id, ts.startTime, ts.endTime, ts.status))
            FROM Booking b JOIN b.lab l JOIN b.timeSlot ts
            WHERE b.id = :bookingId AND b.user.id = :actorId AND b.lab.id = :labId AND ts.lab.id = l.id
              AND b.active = true AND b.deleted = false AND l.active = true AND l.deleted = false
              AND ts.active = true AND ts.deleted = false
              AND b.status = com.web.labportalbackend.common.enums.BookingStatus.APPROVED
              AND ts.status <> com.web.labportalbackend.common.enums.TimeSlotStatus.CANCELLED
              AND b.startTime <= :readAt AND :readAt <= :endInclusive
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    java.util.Optional<AiLabContext.OwnBooking> findAiContextCheckinBooking(
            @Param("actorId") Long actorId, @Param("labId") Long labId, @Param("bookingId") Long bookingId,
            @Param("readAt") Instant readAt, @Param("endInclusive") Instant endInclusive,
            @Param("selectedRoleName") String selectedRoleName);

    @Query("""
            SELECT COUNT(b)
            FROM Booking b JOIN b.lab l
            WHERE l.id = :labId AND l.manager.id = :actorId AND l.active = true AND l.deleted = false
              AND b.active = true AND b.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    long countAiContextManagedBookings(@Param("actorId") Long actorId, @Param("labId") Long labId,
                                       @Param("selectedRoleName") String selectedRoleName);
}
