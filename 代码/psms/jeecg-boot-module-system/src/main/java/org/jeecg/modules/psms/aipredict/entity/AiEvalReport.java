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
 * AI预测-预测准确率自动评估报告
 */
@Data
@TableName("ai_eval_report")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiEvalReport implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**评估生成日*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date evalDate;
    /**评估窗口开始(预测生成日)*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date windowStart;
    /**评估窗口结束*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date windowEnd;
    /**样本数*/
    private Integer sampleCnt;
    /**预测正确数*/
    private Integer correctCnt;
    /**准确率0~1*/
    private BigDecimal accuracy;
    /**目标准确率*/
    private BigDecimal targetAcc;
    /**是否达标*/
    private Integer passFlag;
    /**明细JSON*/
    private String reportJson;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
