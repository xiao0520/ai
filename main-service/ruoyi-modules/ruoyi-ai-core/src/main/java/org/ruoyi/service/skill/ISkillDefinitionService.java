package org.ruoyi.service.skill;

import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.bo.skill.SkillDefinitionBo;
import org.ruoyi.domain.vo.skill.SkillBoundAgentVo;
import org.ruoyi.domain.vo.skill.SkillDefinitionVo;
import org.ruoyi.domain.vo.skill.SkillOptionVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Skill 定义管理服务接口。
 */
public interface ISkillDefinitionService {

    /**
     * 分页查询 Skill 定义。
     *
     * @param bo 查询条件
     * @param pageQuery 分页参数
     * @return Skill 定义分页结果
     */
    TableDataInfo<SkillDefinitionVo> queryPageList(SkillDefinitionBo bo, PageQuery pageQuery);

    /**
     * 查询符合条件的 Skill 定义。
     *
     * @param bo 查询条件
     * @return Skill 定义列表
     */
    List<SkillDefinitionVo> queryList(SkillDefinitionBo bo);

    /**
     * 查询 Skill 定义详情。
     *
     * @param id Skill 主键
     * @return Skill 定义详情
     */
    SkillDefinitionVo query(Long id);

    /**
     * 新增或修改 Skill 定义，并校验唯一名称和制品引用。
     *
     * @param bo Skill 定义
     * @return 是否保存成功
     */
    boolean save(SkillDefinitionBo bo);

    /**
     * 修改 Skill 启停状态。
     *
     * @param id Skill 主键
     * @param status 目标状态
     * @return 是否更新成功
     */
    boolean changeStatus(Long id, String status);

    /**
     * 删除未被 Agent 绑定的 Skill 定义。
     *
     * @param id Skill 主键
     * @return 是否删除成功
     */
    boolean delete(Long id);

    /**
     * 查询绑定指定 Skill 的 Agent。
     *
     * @param id Skill 主键
     * @return Agent 绑定明细
     */
    List<SkillBoundAgentVo> listBoundAgents(Long id);

    /**
     * 查询可供 Agent 配置使用的 Skill 选项。
     *
     * @return 已启用的 Skill 选项
     */
    List<SkillOptionVo> options();

    /**
     * 上传 Skill 制品文件到 OSS。
     *
     * @param file Skill 制品文件
     * @return OSS 文件信息
     */
    OssDTO uploadArtifact(MultipartFile file);
}