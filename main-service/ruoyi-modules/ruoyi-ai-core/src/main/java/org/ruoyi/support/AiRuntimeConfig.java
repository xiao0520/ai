package org.ruoyi.support;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.system.service.ISysConfigService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** AI 运行参数的安全读取入口。 */
@Component
@RequiredArgsConstructor
public class AiRuntimeConfig {

    private final ISysConfigService configService;

    /** 读取必填文本参数，缺失时阻止运行。 */
    public String getRequiredString(String key) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("缺少AI系统参数：" + key);
        }
        return value.trim();
    }

    /** 读取正整数参数，非法或越界时使用安全默认值。 */
    public int getPositiveInt(String key, int defaultValue, int maximum) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= maximum ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 读取必填正整数参数，缺失、非法或超出范围时阻止运行。 */
    public int getRequiredPositiveInt(String key, int maximum) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("缺少AI系统参数：" + key);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0 && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
        }
        throw new ServiceException("AI系统参数值无效：" + key);
    }

    /** 读取正长整型参数，非法或越界时使用安全默认值。 */
    public long getPositiveLong(String key, long defaultValue, long maximum) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 && parsed <= maximum ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 读取逗号分隔的可选值，并限制在代码声明的安全能力范围内。 */
    public Set<String> getAllowedValues(String key, Set<String> supportedValues) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return supportedValues;
        }
        Set<String> values = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(item -> item.toLowerCase(Locale.ROOT))
            .filter(supportedValues::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return values.isEmpty() ? Collections.emptySet() : values;
    }

    /** 读取逗号分隔的可选值，并按不区分大小写规则映射为代码中的标准值。 */
    public Set<String> getAllowedValuesIgnoreCase(String key, Set<String> supportedValues) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return supportedValues;
        }
        Map<String, String> normalizedValues = supportedValues.stream()
            .collect(Collectors.toMap(item -> item.toLowerCase(Locale.ROOT), item -> item));
        Set<String> values = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(item -> normalizedValues.get(item.toLowerCase(Locale.ROOT)))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return values.isEmpty() ? Collections.emptySet() : values;
    }

    /** 读取逗号分隔的字符串集合，不做白名单限制，参数设置配什么就支持什么。 */
    public Set<String> getStringSet(String key) {
        String value = configService.selectConfigByKey(key);
        if (StringUtils.isBlank(value)) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(item -> item.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
