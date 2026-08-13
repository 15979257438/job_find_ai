package com.getjobs.resume.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.getjobs.resume.entity.ResumeVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResumeVersionMapper extends BaseMapper<ResumeVersionEntity> {
    @Select("SELECT * FROM resume_version WHERE resume_id = #{resumeId} AND type = #{type}")
    List<ResumeVersionEntity> findByResumeIdAndType(Long resumeId, String type);

    @Select("SELECT * FROM resume_version WHERE resume_id = #{resumeId} ORDER BY created_at DESC")
    List<ResumeVersionEntity> findAllByResumeId(Long resumeId);

    @Select("SELECT * FROM resume_version WHERE resume_id = #{resumeId} AND type = 'JOB_SPECIFIC' " +
            "AND target_job_id = #{jobId} AND template_id = #{templateId} LIMIT 1")
    ResumeVersionEntity findJobSpecific(Long resumeId, String jobId, String templateId);

    @Select("SELECT COUNT(*) FROM resume_version WHERE resume_id = #{resumeId} AND type = #{type}")
    int countByResumeIdAndType(Long resumeId, String type);

    @Select("SELECT * FROM resume_version WHERE id = #{id}")
    ResumeVersionEntity findById(Long id);
}