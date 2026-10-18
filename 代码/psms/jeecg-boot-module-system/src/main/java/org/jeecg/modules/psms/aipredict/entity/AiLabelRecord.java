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
 * AI预测-人工标注集(预测准确率评估用)
 */
@Data
@TableName("ai_label_record")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiLabelRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**测点*/
    private String pointMac;
    /**企业id*/
    private String companyId;
    /**被预测目标日*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date targetDate;
    /**当时预测概率*/
    private BigDecimal forecastProb;
    /**当时预测等级*/
    private String forecastLevel;
    /**人工标注实际是否超标 1是0否*/
    private Integer actualOver;
    /**标注来源 manual人工/auto自动回填*/
    private String labelSource;
    /**标注人*/
    private String labelBy;
    /**标注时间*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date labelTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
