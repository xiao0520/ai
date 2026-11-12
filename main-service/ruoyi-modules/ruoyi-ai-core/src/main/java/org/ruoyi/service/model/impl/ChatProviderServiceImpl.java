package org.ruoyi.service.model.impl;

import org.ruoyi.common.core.utils.MapstructUtils;
import org.ruoyi.common.core.constant.SystemConstants;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.bo.model.ModelProviderBo;
import org.ruoyi.domain.entity.model.ModelProvider;
import org.ruoyi.domain.vo.model.ModelProviderVo;
import org.ruoyi.service.model.IChatProviderService;
import org.ruoyi.service.model.IChatModelService;
import org.springframework.stereotype.Service;
import org.ruoyi.mapper.model.ModelProviderMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 厂商管理Service业务层处理
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ChatProviderServiceImpl implements IChatProviderService {

    private final ModelProviderMapper baseMapper;
    private final IChatModelService chatModelService;

    @Override
    public ModelProviderVo queryById(Long id) {
        return baseMapper.selectVoById(id);
    }

    @Override
    public TableDataInfo<ModelProviderVo> queryPageList(ModelProviderBo bo, PageQuery pageQuery) {
        // 筛选条件由 Mapper XML 统一维护，业务层只负责分页调用编排。
        Page<ModelProviderVo> page = pageQuery.build();
        page.setRecords(baseMapper.selectProviderList(page, bo));
        return TableDataInfo.build(page);
    }

    @Override
    public List<ModelProviderVo> queryList(ModelProviderBo bo) {
        return baseMapper.selectProviderList(null, bo);
    }

    @Override
    public Boolean insertByBo(ModelProviderBo bo) {
        // 转换请求并先完成唯一性校验，再写入厂商基础资料。
        ModelProvider add = MapstructUtils.convert(bo, ModelProvider.class);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(ModelProviderBo bo) {
        // 使用主键排除当前记录，避免修改时将自身误判为编码重复。
        ModelProvider current = baseMapper.selectById(bo.getId());
        if (current == null) {
            throw new ServiceException("厂商不存在");
        }
        ModelProvider update = MapstructUtils.convert(bo, ModelProvider.class);
        validEntityBeforeSave(update);
        if (!Objects.equals(current.getProviderCode(), update.getProviderCode())
            && chatModelService.hasModelsByProviderCodes(List.of(current.getProviderCode()))) {
            throw new ServiceException("厂商已被模型引用，不能修改厂商编码");
        }
        // 厂商地址只作为新建模型时的默认值，不能批量改写已发布模型的执行快照。
        return baseMapper.updateById(update) > 0;
    }

    /** 保存厂商前校验编码唯一性。 */
    private void validEntityBeforeSave(ModelProvider entity) {
        if (baseMapper.existsByProviderCode(entity.getProviderCode(), entity.getId(), SystemConstants.NORMAL)) {
            throw new ServiceException("厂商编码已存在");
        }
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        // 删除前检查模型引用，避免模型记录保留已经不存在的厂商编码。
        if (Boolean.TRUE.equals(isValid) && ids != null && !ids.isEmpty()) {
            List<String> providerCodes = baseMapper.selectByIds(ids).stream()
                .map(ModelProvider::getProviderCode).distinct().toList();
            if (chatModelService.hasModelsByProviderCodes(providerCodes)) {
                throw new ServiceException("厂商仍被模型配置引用，不能删除");
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }
}
