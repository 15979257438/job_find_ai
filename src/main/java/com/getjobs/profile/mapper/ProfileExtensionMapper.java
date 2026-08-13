package com.getjobs.profile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.profile.entity.ProfileExtensionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProfileExtensionMapper extends BaseMapper<ProfileExtensionEntity> {
    @Select("SELECT * FROM profile_extension WHERE profile_id = #{profileId} LIMIT 1")
    ProfileExtensionEntity findByProfileId(Long profileId);
}