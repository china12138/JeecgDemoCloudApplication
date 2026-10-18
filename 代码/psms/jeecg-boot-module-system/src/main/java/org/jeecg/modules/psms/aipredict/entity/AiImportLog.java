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
 * AI预测-历史数据导入任务日志
 */
@Data
@TableName("ai_import_log")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
public class AiImportLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**任务类型 full-全量/increment-增量*/
    private String taskType;
    /**状态 running/success/fail*/
    private String taskStatus;
    /**来源表*/
    private String srcTable;
    /**读取原始行数*/
    private Long readTotal;
    /**异常过滤行数*/
    private Long outlierTotal;
    /**写入中间表行数(对齐后时槽数)*/
    private Long writeTotal;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    /**耗时毫秒*/
    private Long costMs;
    /**执行信息/错误信息*/
    private String msg;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
