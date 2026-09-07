package com.web.labportalbackend.ai.repository;

import com.web.labportalbackend.ai.entity.AiConversationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public interface AiConversationRepository extends JpaRepository<AiConversationEntity, Long> {
    Optional<AiConversationEntity> findByIdAndUserIdAndActiveTrueAndDeletedFalse(Long id, Long userId);

    List<AiConversationEntity> findByUserIdAndActiveTrueAndDeletedFalseOrderByUpdatedAtDescIdDesc(
            Long userId, Pageable pageable);
}
