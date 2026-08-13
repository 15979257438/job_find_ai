package com.getjobs.aichat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.aichat.entity.AiChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessageEntity> {
    @Select("SELECT * FROM ai_chat_message WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<AiChatMessageEntity> findBySessionId(Long sessionId);

    @Select("SELECT * FROM ai_chat_message WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT #{limit}")
    List<AiChatMessageEntity> findRecentBySessionId(Long sessionId, int limit);

    /** FR-AI-006：取出最近一条待确认的 system_card（未关联 profile_change_log 的） */
    @Select("SELECT * FROM ai_chat_message WHERE session_id = #{sessionId} AND role = 'system_card' AND profile_change_log_id IS NULL ORDER BY id DESC LIMIT 1")
    AiChatMessageEntity findLastSystemCard(Long sessionId);
}