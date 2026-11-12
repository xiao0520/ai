package org.ruoyi.system.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * CDP 共享表基础字段。
 */
@Data
public class CdpEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonIgnore
    @TableField(exist = false)
    private String searchValue;

    private String createBy;

    private Date createTime;

    private String updateBy;

    private Date updateTime;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @TableField(exist = false)
    private Map<String, Object> params = new HashMap<>();
}
