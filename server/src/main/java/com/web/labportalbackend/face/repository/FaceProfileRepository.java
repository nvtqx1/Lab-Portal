package com.web.labportalbackend.face.repository;

import com.web.labportalbackend.face.entity.FaceProfileEntity;
import com.web.labportalbackend.face.enums.FaceProfileStatus;
import com.web.labportalbackend.face.dto.response.FaceProfileResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FaceProfileRepository extends JpaRepository<FaceProfileEntity, Long> {
    @Query("""
            select new com.web.labportalbackend.face.dto.response.FaceProfileResponse(
                profile.user.id, profile.profileStatus, profile.embeddingModel, profile.updatedAt)
            from FaceProfileEntity profile
            where profile.deleted = false
            order by profile.updatedAt desc, profile.id desc
            """)
    List<FaceProfileResponse> findAllProfileMetadata();
    Optional<FaceProfileEntity> findByUserId(Long userId);
    Optional<FaceProfileEntity> findByUserIdAndDeletedFalse(Long userId);
    Optional<FaceProfileEntity> findByUserIdAndProfileStatusAndActiveTrueAndDeletedFalse(
            Long userId, FaceProfileStatus profileStatus);
    List<FaceProfileEntity> findAllByUserIdInAndProfileStatusAndActiveTrueAndDeletedFalse(
            List<Long> userIds, FaceProfileStatus profileStatus);
}
