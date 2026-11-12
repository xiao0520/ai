package org.ruoyi.common.translation.core.impl;

import org.ruoyi.common.core.service.OssService;
import org.ruoyi.common.translation.annotation.TranslationType;
import org.ruoyi.common.translation.constant.TransConstant;
import org.ruoyi.common.translation.core.TranslationInterface;
import lombok.AllArgsConstructor;

/**
 * OSS翻译实现
 *
 * @author xiaoqh
 */
@AllArgsConstructor
@TranslationType(type = TransConstant.OSS_ID_TO_URL)
public class OssUrlTranslationImpl implements TranslationInterface<String> {

    private final OssService ossService;

    @Override
    public String translation(Object key, String other) {
        if (key instanceof String ids) {
            if (ids.startsWith("http://") || ids.startsWith("https://")) {
                return ids;
            }
            return ossService.selectUrlByIds(ids);
        } else if (key instanceof Long id) {
            return ossService.selectUrlByIds(id.toString());
        }
        return null;
    }
}

