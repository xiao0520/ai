package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.constant.AiDomainConstants;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeDocument;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;
import org.ruoyi.mapper.knowledge.KnowledgeDocumentMapper;
import org.ruoyi.service.knowledge.IKnowledgeDocumentService;
import org.ruoyi.service.knowledge.KnowledgeDeletionService;
import org.springframework.stereotype.Service;

/**
 * 知识库文档管理Service实现类
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements IKnowledgeDocumentService {
    private final KnowledgeDocumentMapper baseMapper;
    private final KnowledgeDeletionService deletionService;

    @Override
    public KnowledgeDocumentVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<KnowledgeDocumentVo> queryPageList(KnowledgeDocumentBo bo, PageQuery pageQuery) {
        // 列表筛选和排序由 Mapper XML 统一维护，服务层只传递状态约束与分页参数。
        Page<KnowledgeDocumentVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectDocumentList(page, bo, AiDomainConstants.KNOWLEDGE_STATUS_DELETED));
        return TableDataInfo.build(page);
    }

    @Override
    public boolean updateByBo(KnowledgeDocumentBo bo) {
        // 已删除的文档不允许编辑，避免页面修改无法关联的失效数据。
        KnowledgeDocument current = baseMapper.selectById(bo.getId());
        if (current == null || AiDomainConstants.KNOWLEDGE_STATUS_DELETED.equals(current.getStatus())) {
            throw new ServiceException("知识库文档不存在");
        }

        // 归属知识库、编码与当前版本指针不可由页面修改，始终沿用数据库现值。
        KnowledgeDocument entity = MapstructUtils.convert(bo, KnowledgeDocument.class);
        entity.setKnowledgeId(current.getKnowledgeId());
        entity.setDocumentCode(current.getDocumentCode());
        entity.setCurrentVersionId(current.getCurrentVersionId());
        entity.setCurrentVersionNo(current.getCurrentVersionNo());
        if (StringUtils.isBlank(entity.getStatus())) entity.setStatus(current.getStatus());
        validateStatus(entity.getStatus());
        return baseMapper.updateById(entity) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String status) {
        // 状态先走白名单校验，再确认文档存在后更新。
        validateStatus(status);
        KnowledgeDocument current = baseMapper.selectById(id);
        if (current == null) {
            throw new ServiceException("知识库文档不存在");
        }
        return baseMapper.updateStatus(id, status) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return deletionService.requestDocumentDeletion(id);
    }

    /** 校验文档状态是否属于允许的业务状态集合。 */
    private void validateStatus(String status) {
        if (!AiDomainConstants.KNOWLEDGE_DOCUMENT_STATUSES.contains(status)) {
            throw new ServiceException("文档状态不正确");
        }
    }

}
