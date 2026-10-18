package org.jeecg.modules.psms.aipredict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * AI预测-参数配置(阈值/窗口/大模型等,管理员可调)
 */
@Data
@TableName("ai_predict_config")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiPredictConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**参数分组 over/import/forecast/anomaly/llm/eval*/
    private String cfgGroup;
    /**参数键*/
    private String cfgKey;
    /**参数名称*/
    private String cfgName;
    /**参数值*/
    private String cfgValue;
    /**值类型 int/decimal/string/bool*/
    private String cfgType;
    /**参数说明*/
    private String cfgRemark;
    /**排序*/
    private Integer sortNo;
    /**是否启用*/
    private Integer enabled;

    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
