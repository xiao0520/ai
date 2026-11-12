package org.ruoyi.controller.model;

import java.util.List;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.ruoyi.domain.bo.model.ModelBo;
import org.ruoyi.domain.vo.model.ModelVo;
import org.ruoyi.domain.vo.model.KnowledgeModelOptionVo;
import org.ruoyi.service.model.IChatModelService;
import org.ruoyi.domain.bo.model.ModelBatchKeyBo;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.ruoyi.common.idempotent.annotation.RepeatSubmit;
import org.ruoyi.common.log.annotation.Log;
import org.ruoyi.common.web.core.BaseController;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.log.enums.BusinessType;
import org.ruoyi.common.excel.utils.ExcelUtil;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.domain.vo.model.MyModelOptionVo;
import org.ruoyi.service.chat.IChatUserOptionService;


/**
 * 模型管理
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/model")
public class ModelController extends BaseController {

    private final IChatModelService chatModelService;
    private final IChatUserOptionService chatUserOptionService;

    /**
     * 查询模型管理列表
     */
    @SaCheckPermission("model:manage:list")
    @GetMapping("/list")
    public TableDataInfo<ModelVo> list(ModelBo bo, PageQuery pageQuery) {
        return chatModelService.queryPageList(bo, pageQuery);
    }

    /**
     * 查询用户聊天模型列表
     */
    @GetMapping("/my-options")
    @SaCheckLogin
    public R<List<MyModelOptionVo>> modelList() {
        return R.ok(chatUserOptionService.queryMyModels());
    }

    /**
     * 查询知识库配置可选择的模型。
     */
    @GetMapping("/knowledge-options")
    @SaCheckPermission(value = {"knowledge:document:add", "knowledge:document:edit",
        "knowledge:index:edit", "model:manage:list"}, mode = SaMode.OR)
    public R<List<KnowledgeModelOptionVo>> knowledgeOptions(
            @NotBlank(message = "模型分类不能为空") @RequestParam String category,
            @RequestParam(required = false) String inputModality) {
        return R.ok(chatModelService.queryKnowledgeOptions(category, inputModality));
    }

    /**
     * 导出模型管理列表
     */
    @SaCheckPermission("model:manage:export")
    @Log(title = "模型管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(ModelBo bo, HttpServletResponse response) {
        List<ModelVo> list = chatModelService.queryList(bo);
        ExcelUtil.exportExcel(list, "模型管理", ModelVo.class, response);
    }

    /**
     * 获取模型管理详细信息
     */
    @SaCheckPermission("model:manage:query")
    @GetMapping("/{id}")
    public R<ModelVo> getInfo(@NotNull(message = "主键不能为空")
                                     @PathVariable Long id) {
        return R.ok(chatModelService.queryById(id));
    }

    /**
     * 新增模型管理
     */
    @SaCheckPermission("model:manage:add")
    @Log(title = "模型管理", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody ModelBo bo) {
        return toAjax(chatModelService.insertByBo(bo));
    }

    /**
     * 修改模型管理
     */
    @SaCheckPermission("model:manage:edit")
    @Log(title = "模型管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody ModelBo bo) {
        return toAjax(chatModelService.updateByBo(bo));
    }

    /**
     * 修改用户端可见性
     */
    @SaCheckPermission("model:manage:edit")
    @Log(title = "模型用户端可见性", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/visibility")
    public R<Void> visibility(@NotNull(message = "模型ID不能为空") @PathVariable Long id,
                              @NotBlank(message = "用户端可见性不能为空") @RequestParam String modelShow) {
        return toAjax(chatModelService.updateVisibility(id, modelShow));
    }

    /**
     * 按厂商批量更新密钥
     */
    @SaCheckPermission("model:manage:edit")
    @Log(title = "模型管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/provider-credentials")
    public R<Void> batchCredentialByProvider(@Validated @RequestBody ModelBatchKeyBo bo) {
        return toAjax(chatModelService.updateCredentialKeyByProvider(bo.getProviderCode(), bo.getCredentialKey()));
    }

    /**
     * 删除模型管理
     */
    @SaCheckPermission("model:manage:remove")
    @Log(title = "模型管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(chatModelService.deleteWithValidByIds(List.of(ids), true));
    }
}
