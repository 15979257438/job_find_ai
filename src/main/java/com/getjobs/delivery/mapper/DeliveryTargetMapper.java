package com.getjobs.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.delivery.entity.DeliveryTargetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DeliveryTargetMapper extends BaseMapper<DeliveryTargetEntity> {
    @Select("SELECT * FROM delivery_target WHERE delivery_request_id = #{reqId} ORDER BY match_score DESC")
    List<DeliveryTargetEntity> findByRequestId(Long reqId);

    @Select("SELECT * FROM delivery_target WHERE status = #{status} ORDER BY updated_at DESC LIMIT #{limit}")
    List<DeliveryTargetEntity> findRecentByStatus(String status, int limit);

    @Select("SELECT * FROM delivery_target WHERE delivered_at >= datetime('now', '-' || #{hours} || ' hours') ORDER BY delivered_at DESC")
    List<DeliveryTargetEntity> findSinceHours(int hours);
}