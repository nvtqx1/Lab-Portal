package com.web.labportalbackend.ai.repository;

import com.web.labportalbackend.ai.entity.AiMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessageEntity, Long> {
    List<AiMessageEntity> findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(
            Long conversationId, Pageable pageable);
}
