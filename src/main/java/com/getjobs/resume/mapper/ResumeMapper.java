package com.getjobs.resume.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.resume.entity.ResumeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResumeMapper extends BaseMapper<ResumeEntity> {
    @Select("SELECT * FROM resume WHERE file_sha256 = #{sha256} LIMIT 1")
    ResumeEntity findBySha256(String sha256);

    @Select("SELECT * FROM resume ORDER BY created_at DESC")
    List<ResumeEntity> findAllOrderByCreatedDesc();
}