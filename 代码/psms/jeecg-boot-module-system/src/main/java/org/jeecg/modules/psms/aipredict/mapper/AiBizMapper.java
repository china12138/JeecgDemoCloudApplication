package org.jeecg.modules.psms.aipredict.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.psms.aipredict.entity.AiPredictInput;

import java.util.List;
import java.util.Map;

/**
 * AI预测-业务SQL Mapper
 * 读取bu_lampblack_data/bu_purifier_powerdata原始历史 + 中间表聚合统计
 */
public interface AiBizMapper extends BaseMapper<Map<String, Object>> {

    /** 分页读取原始油烟数据(按时间范围,id排序) */
    @InterceptorIgnore(tenantLine = "true")
    IPage<Map<String, Object>> pageLampblackRaw(Page<Map<String, Object>> page,
                                                @Param("srcTable") String srcTable,
                                                @Param("startTime") String startTime,
                                                @Param("endTime") String endTime);

    /** 按bu_lampblack_id批量取净化器功率聚合(单行多模块取max输出功率) */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> listPurifierPowerByLampblackIds(@Param("ids") List<String> ids,
                                                              @Param("srcTable") String srcTable);

    /** 取测点对应企业(base_point.point_mac=?,company_id) */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> listPointCompanyByMacs(@Param("macs") List<String> macs);

    /** 批量插入中间表(INSERT IGNORE,按point+slot唯一) */
    @InterceptorIgnore(tenantLine = "true")
    int batchInsertInput(@Param("list") List<AiPredictInput> list);

    /** 按测点+日期删除中间数据 */
    @InterceptorIgnore(tenantLine = "true")
    int deleteInputByPointDate(@Param("pointMac") String pointMac,
                               @Param("dataDate") String dataDate);

    /** 删除全量中间数据(重导) */
    @InterceptorIgnore(tenantLine = "true")
    int clearInputBySource(@Param("srcTable") String srcTable);

    /** 清空全部中间数据(演示清理用) */
    @InterceptorIgnore(tenantLine = "true")
    int clearAllInput();

    /** 删除指定日期段中间数据(重导该时间段) */
    @InterceptorIgnore(tenantLine = "true")
    int clearInputByRange(@Param("startTime") String startTime,
                          @Param("endTime") String endTime,
                          @Param("srcTable") String srcTable);

    /** 每测点每日统计(供预测特征) 返回: point_mac,stat_date,slot_cnt,over_slot_cnt,sample_cnt,over_cnt,avg_rate */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> statDailyByPoint(@Param("startDay") String startDay,
                                               @Param("endDay") String endDay,
                                               @Param("srcTable") String srcTable);

    /** 每企业每日统计 */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> statDailyByCompany(@Param("startDay") String startDay,
                                                 @Param("endDay") String endDay,
                                                 @Param("srcTable") String srcTable);

    /** 某测点某天逐时槽数据(用于趋势图/异常) */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> listSlotByPointDate(@Param("pointMac") String pointMac,
                                                  @Param("dataDate") String dataDate,
                                                  @Param("srcTable") String srcTable);

    /** 某日实际是否超标(point级): 以该日有超标时槽为超标 */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> listDailyOverFlag(@Param("startDay") String startDay,
                                                @Param("endDay") String endDay,
                                                @Param("srcTable") String srcTable);

    /** 测点下拉(base_point join base_company) */
    List<Map<String, Object>> listPointOptions();

    /** 企业下拉 */
    List<Map<String, Object>> listCompanyOptions();

    /** 公司名批量查询 */
    List<Map<String, Object>> listCompanyName(@Param("ids") List<String> ids);

    /** 最新预测生成日 */
    @InterceptorIgnore(tenantLine = "true")
    Map<String, Object> selectLastForecastDate();

    /** 最新中间数据时间 */
    @InterceptorIgnore(tenantLine = "true")
    Map<String, Object> selectLastInputTime();
}
