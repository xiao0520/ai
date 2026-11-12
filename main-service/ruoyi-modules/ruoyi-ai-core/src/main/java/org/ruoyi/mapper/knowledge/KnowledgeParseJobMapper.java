package org.ruoyi.mapper.knowledge;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.bo.knowledge.KnowledgeParseJobBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeParseJob;
import org.ruoyi.domain.vo.knowledge.KnowledgeParseJobVo;

import java.util.List;
import java.util.Collection;

@Mapper
public interface KnowledgeParseJobMapper extends BaseMapperPlus<KnowledgeParseJob, KnowledgeParseJobVo> {

    KnowledgeParseJob selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 按解析任务筛选条件查询列表；传入分页对象时由分页插件处理分页。 */
    List<KnowledgeParseJobVo> selectJobList(@Param("page") Page<KnowledgeParseJobVo> page,
                                            @Param("bo") KnowledgeParseJobBo bo);

    int cancelIfActive(@Param("id") Long id, @Param("cancelledStatus") String cancelledStatus,
                       @Param("activeStatuses") Collection<String> activeStatuses);

    int retryIfRetryable(@Param("id") Long id, @Param("pendingStatus") String pendingStatus,
                         @Param("attemptCount") Integer attemptCount, @Param("visible") String visible,
                         @Param("retryableStatuses") Collection<String> retryableStatuses);

    int hideTerminalById(@Param("id") Long id, @Param("visible") String visible,
                         @Param("hidden") String hidden,
                         @Param("terminalStatuses") Collection<String> terminalStatuses);
}

