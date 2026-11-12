package org.ruoyi.domain.vo.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 磁盘技能可选项
 *
 * @author xiaoqh
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

}

