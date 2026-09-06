package com.web.labportalbackend.lab.repository;

import com.web.labportalbackend.common.enums.LabStatus;
import com.web.labportalbackend.ai.context.AiLabContext;
import com.web.labportalbackend.lab.entity.Laboratory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

@Repository
public interface LaboratoryRepository extends JpaRepository<Laboratory, Long> {

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$Laboratory(l.id, l.labName, l.status)
            FROM Membership m JOIN m.laboratory l
            WHERE m.user.id = :actorId
              AND m.active = true AND m.deleted = false
              AND l.active = true AND l.deleted = false
            ORDER BY l.id ASC
            """)
    List<AiLabContext.Laboratory> findAiCandidateLabsForStudent(
            @Param("actorId") Long actorId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @EntityGraph(type = EntityGraph.EntityGraphType.FETCH)
    @Query("""
            SELECT l
            FROM Laboratory l
            WHERE l.id = :id
              AND l.deleted = false
              AND l.active = true
            """)
    Optional<Laboratory> findByIdForStatusAuthorization(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @EntityGraph(type = EntityGraph.EntityGraphType.FETCH)
    @Query("""
            SELECT l
            FROM Laboratory l
            WHERE l.id = :id
              AND l.manager.id = :managerId
              AND l.deleted = false
              AND l.active = true
            """)
    Optional<Laboratory> findManagedByIdForStatusAuthorization(
            @Param("id") Long id,
            @Param("managerId") Long managerId
    );

    Optional<Laboratory> findByLabName(String labName);

    Optional<Laboratory> findFirstByManagerIdAndDeletedFalse(Long managerId);

    boolean existsByIdAndManagerIdAndActiveTrueAndDeletedFalse(Long id, Long managerId);

    List<Laboratory> findByDepartment(String department);

    List<Laboratory> findByStatus(LabStatus status);

    boolean existsByLabName(String labName);

    @Query("SELECT COUNT(l) FROM Laboratory l WHERE l.deleted = false")
    long countNotDeleted();

    @Query("SELECT COUNT(l) FROM Laboratory l WHERE l.status = :status AND l.deleted = false")
    long countByStatusAndNotDeleted(@Param("status") LabStatus status);

    @Query("SELECT COUNT(l) FROM Laboratory l WHERE l.manager IS NULL AND l.deleted = false")
    long countWithoutManager();

    @Query("""
            SELECT new com.web.labportalbackend.ai.context.AiLabContext$Laboratory(l.id, l.labName, l.status, l.capacity)
            FROM Laboratory l
            WHERE l.id = :labId AND l.active = true AND l.deleted = false
              AND EXISTS (SELECT u.id FROM User u WHERE u.id = :actorId AND u.active = true
                          AND u.deleted = false AND u.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE)
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    Optional<AiLabContext.Laboratory> findAiContextLaboratory(
            @Param("actorId") Long actorId, @Param("labId") Long labId,
            @Param("selectedRoleName") String selectedRoleName);

    /** Authorization anchor for managed-lab aggregates, which otherwise return zero counts. */
    @Query("""
            SELECT COUNT(l) > 0
            FROM Laboratory l JOIN l.manager m
            WHERE l.id = :labId AND m.id = :actorId
              AND l.active = true AND l.deleted = false
              AND m.active = true AND m.deleted = false
              AND m.status = com.web.labportalbackend.common.enums.UserStatus.ACTIVE
              AND EXISTS (SELECT r.id FROM User roleActor JOIN roleActor.roles r
                          WHERE roleActor.id = :actorId AND r.name = :selectedRoleName)
            """)
    boolean existsAiContextManagedLab(@Param("actorId") Long actorId, @Param("labId") Long labId,
                                      @Param("selectedRoleName") String selectedRoleName);
}
