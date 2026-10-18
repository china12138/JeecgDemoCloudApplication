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
 * AI预测-预测输入中间表(清洗/时间对齐后)
 * 对齐粒度默认5分钟: 同point_mac+同一对齐时槽聚合为一行
 */
@Data
@TableName("ai_predict_input")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiPredictInput implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**测点(point_mac,与base_point.point_mac关联)*/
    private String pointMac;
    /**所属企业id*/
    private String companyId;
    /**对齐后时槽开始时间*/
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date dataTime;
    /**时槽内分钟序号*/
    private Integer minuteNo;
    /**时槽内样本数*/
    private Integer sampleCnt;
    /**油烟浓度均值*/
    private BigDecimal lampblackAvg;
    /**油烟浓度最大值*/
    private BigDecimal lampblackMax;
    /**颗粒物均值*/
    private BigDecimal matterAvg;
    /**非甲烷总烃均值*/
    private BigDecimal nmhcAvg;
    /**风机开启样本数*/
    private Integer fanOnCnt;
    /**净化器运行样本数*/
    private Integer purifierOnCnt;
    /**净化器功率为0样本数*/
    private Integer purifierPowerZeroCnt;
    /**功率0且浓度超阈值样本数(复合异常判定)*/
    private Integer offOverCnt;
    /**超阈值样本数*/
    private Integer overCnt;
    /**该时槽是否判定超标*/
    private Integer overFlag;
    /**来源表*/
    private String sourceTable;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
