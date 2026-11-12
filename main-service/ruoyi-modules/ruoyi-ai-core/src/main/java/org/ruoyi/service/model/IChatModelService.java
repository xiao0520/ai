package org.ruoyi.service.model;

import org.ruoyi.domain.bo.model.ModelBo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.model.KnowledgeModelOptionVo;
import org.ruoyi.domain.vo.model.MyModelOptionVo;
import org.ruoyi.domain.vo.token.AiModelOptionVo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;

import java.util.Collection;
import java.util.List;

/**
 * 模型管理Service接口
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface IChatModelService {

    /**
     * 查询模型管理
     *
     * @param id 主键
     * @return 模型管理
     */
    ModelVo queryById(Long id);

    /**
     * 按主键批量查询模型。
     *
     * @param ids 模型主键集合
     * @return 模型列表
     */
    List<ModelVo> queryByIds(Collection<Long> ids);

    /**
     * 查询用户端可展示的聊天模型。
     *
     * @param ids 已完成权限过滤的模型ID集合
     * @return 用户端模型选项
     */
    List<MyModelOptionVo> queryVisibleChatOptions(Collection<Long> ids);

    /**
     * 查询全部启用且未删除的模型授权选项。
     *
     * @return 可用于授权配置的模型选项
     */
    List<AiModelOptionVo> queryEnabledOptions();

    /**
     * 按主键查询未删除的模型授权选项。
     *
     * @param ids 模型主键集合
     * @return 存在且未删除的模型选项
     */
    List<AiModelOptionVo> queryEnabledOptionsByIds(Collection<Long> ids);

    /**
     * 查询知识库页面可选择的已发布模型。
     *
     * @param category 模型分类
     * @param inputModality 所需输入模态，可为空
     * @return 满足分类、状态和配置完整性的模型选项
     */
    List<KnowledgeModelOptionVo> queryKnowledgeOptions(String category, String inputModality);

    /**
     * 校验指定模型均为启用且未删除状态。
     *
     * @param ids 待校验的模型主键集合
     * @throws org.ruoyi.common.core.exception.ServiceException 模型不存在或未启用时抛出
     */
    void requireEnabledModels(Collection<Long> ids);

    /**
     * 判断指定厂商编码是否仍被模型配置引用。
     *
     * @param providerCodes 厂商编码集合
     * @return 任一厂商仍被模型引用时返回 {@code true}
     */
    boolean hasModelsByProviderCodes(Collection<String> providerCodes);

    /**
     * 根据模型名称查询模型
     *
     * @param modelName 模型名称
     * @return 模型管理
     */
    ModelVo selectModelByName(String modelName);

    /**
     * 分页查询模型管理列表
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 模型管理分页列表
     */
    TableDataInfo<ModelVo> queryPageList(ModelBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的模型管理列表
     *
     * @param bo 查询条件
     * @return 模型管理列表
     */
    List<ModelVo> queryList(ModelBo bo);

    /**
     * 新增模型管理
     *
     * @param bo 模型管理
     * @return 是否新增成功
     */
    Boolean insertByBo(ModelBo bo);

    /**
     * 修改模型管理
     *
     * @param bo 模型管理
     * @return 是否修改成功
     */
    Boolean updateByBo(ModelBo bo);

    /**
     * 修改模型在用户端的可见性。
     *
     * @param id 模型主键
     * @param modelShow 是否在用户端展示
     * @return 是否修改成功
     */
    Boolean updateVisibility(Long id, String modelShow);

    /**
     * 校验并批量删除模型管理信息
     *
     * @param ids     待删除的主键集合
     * @param isValid 是否进行有效性校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 按厂商批量更新 Python 凭证别名。
     *
     * @param providerCode 厂商编码
     * @param credentialKey Python 读取凭证时使用的别名
     * @return 是否存在并成功更新模型
     */
    Boolean updateCredentialKeyByProvider(String providerCode, String credentialKey);

}
