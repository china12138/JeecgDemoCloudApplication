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
import java.math.BigDecimal;
import java.util.Date;

/**
 * AI预测-异常识别预警记录
 * 模式示例: 净化器功率为0且油烟浓度超阈值持续30分钟
 */
@Data
@TableName("ai_anomaly_record")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiAnomalyRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**模式编码 PURIFIER_OFF_OVER/CONTINUOUS_OVER*/
    private String anomalyCode;
    /**模式名称*/
    private String anomalyName;
    /**测点*/
    private String pointMac;
    /**企业id*/
    private String companyId;
    /**企业名称*/
    private String companyName;
    /**异常开始时间*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    /**异常结束时间*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    /**持续分钟*/
    private Integer durationMin;
    /**期间油烟浓度峰值*/
    private BigDecimal maxLampblack;
    /**期间油烟浓度均值*/
    private BigDecimal avgLampblack;
    /**使用的判定阈值*/
    private BigDecimal thresholdUsed;
    /**处理状态 0待处理/1已确认/2已办结/3误报*/
    private Integer handleStatus;
    /**归因状态 0未归因/1LLM成功/2规则降级/3失败*/
    private Integer attributionStatus;
    /**大模型归因结构化JSON*/
    private String attributionJson;
    /**描述*/
    private String description;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
