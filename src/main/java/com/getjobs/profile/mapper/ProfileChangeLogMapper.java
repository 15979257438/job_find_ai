package com.getjobs.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.profile.entity.ProfileChangeLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProfileChangeLogMapper extends BaseMapper<ProfileChangeLogEntity> {
    @Select("SELECT * FROM profile_change_log WHERE profile_id = #{profileId} ORDER BY created_at DESC")
    List<ProfileChangeLogEntity> findByProfileId(Long profileId);
}