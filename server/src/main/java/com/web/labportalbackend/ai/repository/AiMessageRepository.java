package com.web.labportalbackend.ai.repository;

import com.web.labportalbackend.ai.entity.AiMessageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessageEntity, Long> {
    java.util.Optional<AiMessageEntity> findByIdAndConversationIdAndActiveTrueAndDeletedFalse(Long id, Long conversationId);

    @org.springframework.data.jpa.repository.Query("""
            select m from AiMessageEntity m where m.conversationId = :conversationId
            and m.active = true and m.deleted = false
            and (m.createdAt < :beforeTime or (m.createdAt = :beforeTime and m.id < :beforeId))
            order by m.createdAt desc, m.id desc
            """)
    List<AiMessageEntity> findOlderMessages(Long conversationId, java.time.Instant beforeTime, Long beforeId, Pageable pageable);
    List<AiMessageEntity> findByConversationIdAndActiveTrueAndDeletedFalseOrderByCreatedAtDescIdDesc(
            Long conversationId, Pageable pageable);
}
