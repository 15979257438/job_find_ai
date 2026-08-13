package com.getjobs.aichat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.aichat.entity.AiToolCallEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiToolCallMapper extends BaseMapper<AiToolCallEntity> {
    @Select("SELECT * FROM ai_tool_call WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<AiToolCallEntity> findBySessionId(Long sessionId);
}