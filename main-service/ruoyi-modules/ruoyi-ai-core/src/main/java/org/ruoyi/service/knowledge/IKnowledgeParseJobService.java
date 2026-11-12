package org.ruoyi.service.knowledge;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeParseJobBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeParseJobVo;

/**
 * 知识库解析任务管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeParseJobService {

    /**
     * 根据ID查询知识库解析任务
     *
     * @param id 主键
     * @return 知识库解析任务
     */
    KnowledgeParseJobVo queryById(Long id);

    /**
     * 分页查询知识库解析任务列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库解析任务分页列表
     */
    TableDataInfo<KnowledgeParseJobVo> queryPageList(KnowledgeParseJobBo bo, PageQuery pageQuery);

    /**
     * 为文档版本创建解析任务。
     *
     * @param documentVersionId 文档版本主键
     * @return 已创建的解析任务
     */
    KnowledgeParseJobVo submitDocumentVersion(Long documentVersionId);

    /**
     * 提交重建索引任务
     *
     * @param knowledgeId        知识库ID
     * @param targetIndexVersion 目标索引版本
     * @return 知识库解析任务
     */
    KnowledgeParseJobVo submitRebuild(Long knowledgeId, String targetIndexVersion);

    /**
     * 取消知识库解析任务
     *
     * @param id 主键
     * @return 是否取消成功
     */
    Boolean cancel(Long id);

    /**
     * 重试知识库解析任务
     *
     * @param id 主键
     * @return 是否重试成功
     */
    Boolean retry(Long id);

    /**
     * 将终态任务从管理列表隐藏，保留索引与审计数据。
     *
     * @param id 主键
     * @return 是否隐藏成功
     */
    Boolean hideFromList(Long id);
}
