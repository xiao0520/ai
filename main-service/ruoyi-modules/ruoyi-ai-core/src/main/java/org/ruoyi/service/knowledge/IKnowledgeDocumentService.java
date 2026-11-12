package org.ruoyi.service.knowledge;

import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.knowledge.KnowledgeDocumentBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeDocumentVo;

/**
 * 知识库文档管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeDocumentService {

    /**
     * 根据ID查询知识库文档
     *
     * @param id 主键
     * @return 知识库文档
     */
    KnowledgeDocumentVo queryById(Long id);

    /**
     * 分页查询知识库文档列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库文档分页列表
     */
    TableDataInfo<KnowledgeDocumentVo> queryPageList(KnowledgeDocumentBo bo, PageQuery pageQuery);

    /**
     * 修改知识库文档
     *
     * @param bo 知识库文档
     * @return 是否修改成功
     */
    boolean updateByBo(KnowledgeDocumentBo bo);

    /**
     * 更新知识库文档状态
     *
     * @param id     主键
     * @param status 状态
     * @return 是否更新成功
     */
    boolean updateStatus(Long id, String status);

    /**
     * 删除知识库文档
     *
     * @param id 主键
     * @return 是否删除成功
     */
    boolean deleteById(Long id);
}
