package org.ruoyi.service.knowledge;

import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import java.util.Collection;
import java.util.List;

/**
 * 知识库管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IKnowledgeInfoService {

    /**
     * 根据ID查询知识库
     *
     * @param id 主键
     * @return 知识库
     */
    KnowledgeInfoVo queryById(Long id);

    /**
     * 按主键批量查询知识库。
     *
     * @param ids 知识库主键集合
     * @return 知识库列表
     */
    List<KnowledgeInfoVo> queryByIds(Collection<Long> ids);

    /**
     * 读取知识库检索配置；未保存时返回展示默认值。
     *
     * @param id 知识库主键
     * @return 当前配置
     */
    org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig queryRetrievalConfig(Long id);

    /**
     * 保存查询阶段配置，不重建已发布索引。
     *
     * @param id 知识库主键
     * @param config 检索与重排配置
     * @return 是否更新成功
     */
    Boolean updateRetrievalConfig(Long id, org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig config);

    /**
     * 读取已保存配置；空值表示存量库沿用旧执行策略。
     *
     * @param id 知识库主键
     * @return 已保存配置或 null
     */
    org.ruoyi.domain.dto.knowledge.KnowledgeRetrievalConfig savedRetrievalConfig(Long id);

    /**
     * 分页查询知识库列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 知识库分页列表
     */
    TableDataInfo<KnowledgeInfoVo> queryAuthorizedPageList(KnowledgeInfoBo bo, PageQuery pageQuery,
                                                            Collection<Long> authorizedIds);

    /**
     * 查询符合条件的知识库列表
     *
     * @param bo 查询条件
     * @return 知识库列表
     */
    List<KnowledgeInfoVo> queryList(KnowledgeInfoBo bo);

    /**
     * 新增知识库
     *
     * @param bo 知识库
     * @return 是否新增成功
     */
    Boolean insertByBo(KnowledgeInfoBo bo);

    /**
     * 修改知识库
     *
     * @param bo 知识库
     * @return 是否修改成功
     */
    Boolean updateByBo(KnowledgeInfoBo bo);

    /**
     * 启停知识库，不改变其他配置。
     *
     * @param id     知识库主键
     * @param status 目标状态
     * @return 是否更新成功
     */
    Boolean updateStatus(Long id, String status);

    /**
     * 逻辑删除知识库
     *
     * @param id 知识库主键
     * @return 是否删除成功
     */
    Boolean deleteById(Long id);

    /**
     * 在同一事务中彻底删除多个知识库。
     *
     * @param ids 知识库主键集合
     * @return 是否删除成功
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 锁定知识库行，供索引版本切换、删除和任务提交在同一知识库范围内串行执行。
     *
     * @param id 知识库主键
     */
    void lockForIndexActivation(Long id);

    /**
     * 激活知识库索引版本
     *
     * @param id            主键
     * @param indexVersion  索引版本
     * @return 是否激活成功
     */
    Boolean activateIndexVersion(Long id, String indexVersion);

    /**
     * 当前索引版本仍等于期望值时清空指针。
     *
     * @param id                   知识库主键
     * @param expectedIndexVersion 期望的当前索引版本
     * @return 是否清空成功
     */
    Boolean clearCurrentIndexVersion(Long id, String expectedIndexVersion);
}
