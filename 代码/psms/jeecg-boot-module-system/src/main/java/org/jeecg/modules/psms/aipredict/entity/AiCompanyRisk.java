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
 * AI预测-风险企业榜单(每日按企业聚合)
 */
@Data
@TableName("ai_company_risk")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiCompanyRisk implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**企业id*/
    private String companyId;
    /**企业名称*/
    private String companyName;
    /**榜单日期(预测生成日)*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date forecastDate;
    /**企业风险分*/
    private BigDecimal riskScore;
    /**风险等级 low/mid/high/veryhigh*/
    private String riskLevel;
    /**参与评估测点数*/
    private Integer pointCnt;
    /**当日前N排名*/
    private Integer rankNo;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
