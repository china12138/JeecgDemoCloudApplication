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
 * AI预测-超标趋势预测结果(每日:每测点未来7天)
 */
@Data
@TableName("ai_forecast_result")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiForecastResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**测点*/
    private String pointMac;
    /**企业id*/
    private String companyId;
    /**预测生成日期*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date forecastDate;
    /**预测目标日期*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date targetDate;
    /**当日超标概率0~1*/
    private BigDecimal exceedProb;
    /**风险等级 low/mid/high/veryhigh*/
    private String riskLevel;
    /**方法 RULE规则基线*/
    private String method;
    /**数据是否充分*/
    private Integer dataSufficient;
    /**备注(贡献因子/降级原因)*/
    private String remark;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
