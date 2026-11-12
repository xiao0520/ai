package org.ruoyi.generator.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.generator.domain.GenTableColumn;

/**
 * 业务字段 数据层
 *
 * @author xiaoqh
 */
@InterceptorIgnore(dataPermission = "true")
public interface GenTableColumnMapper extends BaseMapperPlus<GenTableColumn, GenTableColumn> {

}

