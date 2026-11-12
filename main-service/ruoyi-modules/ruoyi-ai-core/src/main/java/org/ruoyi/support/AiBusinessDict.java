package org.ruoyi.support;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.system.domain.vo.SysDictDataVo;
import org.ruoyi.system.service.ISysDictTypeService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** AI 业务字典统一读取入口。 */
@Component
@RequiredArgsConstructor
public class AiBusinessDict {

    private final ISysDictTypeService dictTypeService;

    /** 返回指定字典的默认值；未配置默认项时拒绝继续保存。 */
    public String defaultValue(String dictType) {
        return requireData(dictType).stream()
            .filter(item -> "Y".equals(item.getIsDefault()))
            .map(SysDictDataVo::getDictValue).filter(StringUtils::isNotBlank).findFirst()
            .orElseThrow(() -> new ServiceException("AI业务字典未配置默认值：" + dictType));
    }

    /** 校验并标准化指定字典中的值。 */
    public String requireValue(String dictType, String value) {
        if (StringUtils.isBlank(value)) return defaultValue(dictType);
        Set<String> values = values(dictType);
        if (!values.contains(value)) throw new ServiceException("AI业务字典值无效：" + dictType);
        return value;
    }

    /** 校验必填业务字典值，不允许用字典默认值掩盖调用方缺参。 */
    public String requireSpecifiedValue(String dictType, String value) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("AI业务字典值不能为空：" + dictType);
        }
        return requireValue(dictType, value);
    }

    /** 返回指定字典的全部有效值，用于受控运行参数的合法值边界。 */
    public Set<String> values(String dictType) {
        return requireData(dictType).stream()
            .map(SysDictDataVo::getDictValue).filter(StringUtils::isNotBlank)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** 字典缺失属于部署初始化问题，不能退化为普通的值校验失败。 */
    private List<SysDictDataVo> requireData(String dictType) {
        List<SysDictDataVo> data = dictTypeService.selectDictDataByType(dictType);
        if (data == null || data.isEmpty()) {
            throw new ServiceException("AI业务字典未初始化，请执行数据库迁移并刷新字典缓存：" + dictType);
        }
        return data;
    }
}
