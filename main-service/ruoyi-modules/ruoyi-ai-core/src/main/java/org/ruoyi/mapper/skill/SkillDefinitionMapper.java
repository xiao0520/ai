package org.ruoyi.mapper.skill;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.domain.bo.skill.SkillDefinitionBo;
import org.ruoyi.domain.entity.skill.SkillDefinition;
import org.ruoyi.domain.vo.skill.SkillDefinitionVo;
import org.ruoyi.domain.vo.skill.SkillOptionVo;

import java.util.List;

@Mapper
public interface SkillDefinitionMapper extends BaseMapperPlus<SkillDefinition, SkillDefinitionVo> {

    /** 按筛选条件分页查询 Skill 定义。 */
    List<SkillDefinition> selectSkillPage(@Param("page") Page<SkillDefinition> page,
                                          @Param("bo") SkillDefinitionBo bo);

    /** 按筛选条件查询 Skill 定义，用于导出。 */
    List<SkillDefinition> selectSkillList(@Param("bo") SkillDefinitionBo bo);

    /** 查询可绑定的启用 Skill 选项。 */
    List<SkillOptionVo> selectEnabledOptions();

    /** 检查 Skill 编码是否已被其他记录占用。 */
    boolean existsBySkillName(@Param("skillName") String skillName, @Param("excludeId") Long excludeId);

    /** 检查 OSS 制品是否仍被其他 Skill 引用。 */
    boolean existsByArtifactOssId(@Param("artifactOssId") Long artifactOssId);

    /** 更新 Skill 配置，并允许将制品 OSS ID 显式清空。 */
    int updateWithNullableArtifact(SkillDefinition entity);
}

