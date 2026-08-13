package com.getjobs.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.message.entity.PlatformMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlatformMessageMapper extends BaseMapper<PlatformMessageEntity> {
    @Select("SELECT * FROM platform_message WHERE platform = #{platform} AND platform_message_id = #{mid} LIMIT 1")
    PlatformMessageEntity findByPlatformAndMessageId(String platform, String mid);

    @Select("SELECT * FROM platform_message ORDER BY received_at DESC, id DESC LIMIT #{limit}")
    List<PlatformMessageEntity> findRecent(int limit);

    @Select("SELECT * FROM platform_message WHERE delivery_target_id = #{targetId} ORDER BY sent_at ASC")
    List<PlatformMessageEntity> findByDeliveryTargetId(Long targetId);
}