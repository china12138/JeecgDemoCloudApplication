package org.jeecg.modules.psms.aipredict.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.modules.psms.aipredict.entity.*;
import org.jeecg.modules.psms.aipredict.mapper.*;
import org.jeecg.modules.psms.aipredict.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * AI预测分析(V2.0 A方向) 入口
 *  - 预测看板/风险榜单/异常时间线
 *  - 历史导入任务/趋势预测任务/异常识别任务 手动触发
 *  - 预测参数配置 / 大模型归因 / 准确率评估 / 演示数据
 */
@Api(tags = "AI预测分析-aipredict")
@Slf4j
@RestController
@RequestMapping("/aipredict")
public class AiPredictController {

    @Autowired
    private AiDashboardService dashboardService;
    @Autowired
    private AiForecastService forecastService;
    @Autowired
    private AiImportService importService;
    @Autowired
    private AiAnomalyService anomalyService;
    @Autowired
    private AiEvalService evalService;
    @Autowired
    private AiConfigService configService;
    @Autowired
    private AiDemoService demoService;
    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiAnomalyRecordMapper anomalyRecordMapper;
    @Autowired
    private AiImportLogMapper importLogMapper;
    @Autowired
    private AiEvalReportMapper evalReportMapper;
    @Autowired
    private AiLabelRecordMapper labelRecordMapper;
    @Autowired
    private AiForecastResultMapper forecastResultMapper;
    @Autowired
    private AiCompanyRiskMapper companyRiskMapper;

    // ============ 预测看板 ============

    @AutoLog(value = "AI预测-看板总览")
    @ApiOperation(value = "看板总览")
    @GetMapping("/dashboard/overview")
    public Result<?> overview() {
        try {
            return Result.OK(dashboardService.overview());
        } catch (Exception e) {
            log.error("看板总览失败", e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-超标趋势折线")
    @ApiOperation(value = "超标趋势折线")
    @GetMapping("/dashboard/trend")
    public Result<?> trend(@RequestParam(name = "pointMac", required = false) String pointMac,
                           @RequestParam(name = "companyId", required = false) String companyId,
                           @RequestParam(name = "backDays", defaultValue = "14") int backDays) {
        try {
            return Result.OK(dashboardService.trend(pointMac, companyId, backDays));
        } catch (Exception e) {
            log.error("趋势查询失败", e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-风险企业榜单")
    @ApiOperation(value = "风险企业TOP榜")
    @GetMapping("/dashboard/topRisk")
    public Result<?> topRisk(@RequestParam(name = "top", defaultValue = "5") int top) {
        try {
            return Result.OK(dashboardService.topRisk(top));
        } catch (Exception e) {
            log.error("风险榜单失败", e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-异常事件时间线")
    @ApiOperation(value = "异常事件时间线")
    @GetMapping("/dashboard/anomalyTimeline")
    public Result<?> anomalyTimeline(@RequestParam(name = "days", defaultValue = "14") int days) {
        try {
            return Result.OK(dashboardService.anomalyTimeline(days));
        } catch (Exception e) {
            log.error("异常时间线失败", e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-企业每日统计")
    @ApiOperation(value = "企业每日超标统计")
    @GetMapping("/dashboard/companyDaily")
    public Result<?> companyDaily(@RequestParam(name = "companyId", required = false) String companyId,
                                  @RequestParam(name = "backDays", defaultValue = "14") int backDays) {
        try {
            return Result.OK(dashboardService.companyDaily(companyId, backDays));
        } catch (Exception e) {
            log.error("企业日统计失败", e);
            return Result.error(e.getMessage());
        }
    }

    // ============ 下拉数据 ============

    @GetMapping("/options/points")
    public Result<?> points() {
        try {
            return Result.OK(aiBizMapper.listPointOptions());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/options/companies")
    public Result<?> companies() {
        try {
            return Result.OK(aiBizMapper.listCompanyOptions());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ============ 历史导入任务 ============

    @AutoLog(value = "AI预测-历史数据导入")
    @ApiOperation(value = "历史数据导入(full/increment)")
    @GetMapping("/import/run")
    public Result<?> runImport(@RequestParam(name = "mode", defaultValue = "increment") String mode,
                               @RequestParam(name = "endDay", required = false) String endDay) {
        try {
            AiImportLog logBean = importService.importHistory(mode, endDay);
            return Result.OK(logBean);
        } catch (Exception e) {
            log.error("历史导入失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/import/logs")
    public Result<?> importLogs(@RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        Page<AiImportLog> page = new Page<>(pageNo, pageSize);
        QueryWrapper<AiImportLog> qw = new QueryWrapper<>();
        qw.orderByDesc("create_time");
        IPage<AiImportLog> res = importLogMapper.selectPage(page, qw);
        return Result.OK(res);
    }

    // ============ 趋势预测任务 ============

    @AutoLog(value = "AI预测-执行趋势预测")
    @ApiOperation(value = "执行超标趋势预测(手动)")
    @GetMapping("/forecast/run")
    public Result<?> runForecast(@RequestParam(name = "forecastDate", required = false) String forecastDate) {
        try {
            int n = forecastService.runDailyForecast(forecastDate);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inserted", n);
            return Result.OK(m);
        } catch (Exception e) {
            log.error("趋势预测失败", e);
            return Result.error(e.getMessage());
        }
    }

    // ============ 异常识别 ============

    @AutoLog(value = "AI预测-异常识别扫描")
    @ApiOperation(value = "异常识别扫描(净化器停机超标/持续超标)")
    @GetMapping("/anomaly/scan")
    public Result<?> scanAnomaly(@RequestParam(name = "startDay", required = false) String startDay,
                                 @RequestParam(name = "endDay", required = false) String endDay) {
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            if (StringUtils.isBlank(startDay) && StringUtils.isBlank(endDay)) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_MONTH, -30);
                startDay = df.format(c.getTime());
                endDay = df.format(new Date());
            }
            int n = anomalyService.scanRange(startDay, endDay);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("created", n);
            return Result.OK(m);
        } catch (Exception e) {
            log.error("异常识别失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/anomaly/list")
    public Result<?> anomalyList(@RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                 @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
                                 @RequestParam(name = "handleStatus", required = false) Integer handleStatus,
                                 @RequestParam(name = "code", required = false) String code,
                                 @RequestParam(name = "pointMac", required = false) String pointMac) {
        Page<AiAnomalyRecord> page = new Page<>(pageNo, pageSize);
        QueryWrapper<AiAnomalyRecord> qw = new QueryWrapper<>();
        if (handleStatus != null) {
            qw.eq("handle_status", handleStatus);
        }
        if (StringUtils.isNotBlank(code)) {
            qw.eq("anomaly_code", code);
        }
        if (StringUtils.isNotBlank(pointMac)) {
            qw.eq("point_mac", pointMac);
        }
        qw.orderByDesc("start_time");
        return Result.OK(anomalyRecordMapper.selectPage(page, qw));
    }

    @AutoLog(value = "AI预测-异常处理")
    @ApiOperation(value = "异常记录处理状态")
    @GetMapping("/anomaly/handle")
    public Result<?> handleAnomaly(@RequestParam(name = "id") String id,
                                   @RequestParam(name = "status") Integer status) {
        AiAnomalyRecord rec = anomalyRecordMapper.selectById(id);
        if (rec == null) {
            return Result.error("记录不存在");
        }
        rec.setHandleStatus(status);
        rec.setUpdateTime(new Date());
        anomalyRecordMapper.updateById(rec);
        return Result.OK("ok");
    }

    @AutoLog(value = "AI预测-大模型归因")
    @ApiOperation(value = "对异常记录执行LLM归因(自动降级规则)")
    @GetMapping("/anomaly/attribution")
    public Result<?> attribution(@RequestParam(name = "id") String id) {
        AiAnomalyRecord rec = anomalyRecordMapper.selectById(id);
        if (rec == null) {
            return Result.error("记录不存在");
        }
        AiAnomalyRecord out = anomalyService.attributionFor(rec);
        return Result.OK(out);
    }

    // ============ 预测参数配置 ============

    @GetMapping("/config/list")
    public Result<?> configList() {
        try {
            return Result.OK(configService.listGrouped());
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-参数保存")
    @ApiOperation(value = "保存预测参数(JSON数组)")
    @PostMapping("/config/save")
    public Result<?> configSave(@RequestBody String json) {
        try {
            List<AiPredictConfig> items = JSON.parseArray(json, AiPredictConfig.class);
            int n = configService.saveBatch(items);
            return Result.OK("保存成功 " + n + " 项,已热生效");
        } catch (Exception e) {
            log.error("参数保存失败", e);
            return Result.error(e.getMessage());
        }
    }

    // ============ 预测任务记录 & 预测结果 ============

    @GetMapping("/forecast/resultList")
    public Result<?> forecastResultList(@RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                        @RequestParam(name = "pageSize", defaultValue = "10") int pageSize,
                                        @RequestParam(name = "forecastDate", required = false) String forecastDate,
                                        @RequestParam(name = "riskLevel", required = false) String riskLevel,
                                        @RequestParam(name = "pointMac", required = false) String pointMac) {
        Page<AiForecastResult> page = new Page<>(pageNo, pageSize);
        QueryWrapper<AiForecastResult> qw = new QueryWrapper<>();
        if (StringUtils.isNotBlank(forecastDate)) {
            qw.eq("forecast_date", forecastDate);
        }
        if (StringUtils.isNotBlank(riskLevel)) {
            qw.eq("risk_level", riskLevel);
        }
        if (StringUtils.isNotBlank(pointMac)) {
            qw.eq("point_mac", pointMac);
        }
        qw.orderByAsc("point_mac", "target_date");
        return Result.OK(forecastResultMapper.selectPage(page, qw));
    }

    @GetMapping("/forecast/dates")
    public Result<?> forecastDates() {
        List<AiForecastResult> list = forecastResultMapper.selectList(new QueryWrapper<AiForecastResult>()
                .select("DISTINCT forecast_date").orderByDesc("forecast_date").last("limit 30"));
        List<String> dates = new ArrayList<>();
        if (list != null) {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            for (AiForecastResult r : list) {
                dates.add(df.format(r.getForecastDate()));
            }
        }
        return Result.OK(dates);
    }

    // ============ 准确率评估(COULD) ============

    @AutoLog(value = "AI预测-准确率评估")
    @ApiOperation(value = "生成准确率评估报告")
    @GetMapping("/eval/run")
    public Result<?> runEval(@RequestParam(name = "forecastDate", required = false) String forecastDate) {
        try {
            return Result.OK(evalService.runEval(forecastDate));
        } catch (Exception e) {
            log.error("评估失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/eval/reports")
    public Result<?> evalReports(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return Result.OK(evalService.listReports(limit));
    }

    // 人工标注: 查看某预测日结果 → 标注实际是否超标
    @GetMapping("/label/pending")
    public Result<?> labelPending(@RequestParam(name = "forecastDate", required = false) String forecastDate) {
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            String fd = forecastDate;
            if (StringUtils.isBlank(fd)) {
                List<AiForecastResult> fds = forecastResultMapper.selectList(new QueryWrapper<AiForecastResult>()
                        .select("DISTINCT forecast_date").orderByDesc("forecast_date").last("limit 1"));
                if (fds != null && !fds.isEmpty()) {
                    fd = df.format(fds.get(0).getForecastDate());
                }
            }
            if (StringUtils.isBlank(fd)) {
                return Result.OK(Collections.emptyList());
            }
            List<AiForecastResult> list = forecastResultMapper.selectList(new QueryWrapper<AiForecastResult>()
                    .eq("forecast_date", fd).orderByAsc("point_mac", "target_date"));
            // 已标注map(标注量小,全量内存匹配)
            List<AiLabelRecord> lbls = labelRecordMapper.selectList(new QueryWrapper<AiLabelRecord>());
            Map<String, AiLabelRecord> lblMap = new HashMap<>();
            if (lbls != null) {
                for (AiLabelRecord l : lbls) {
                    lblMap.put(l.getPointMac() + "|" + df.format(l.getTargetDate()), l);
                }
            }
            JSONArray out = new JSONArray();
            for (AiForecastResult f : list) {
                JSONObject o = new JSONObject();
                o.put("pointMac", f.getPointMac());
                o.put("companyId", f.getCompanyId());
                o.put("forecastDate", df.format(f.getForecastDate()));
                o.put("targetDate", df.format(f.getTargetDate()));
                o.put("forecastProb", f.getExceedProb());
                o.put("forecastLevel", f.getRiskLevel());
                AiLabelRecord lb = lblMap.get(f.getPointMac() + "|" + df.format(f.getTargetDate()));
                o.put("labeled", lb != null);
                o.put("actualOver", lb == null ? null : lb.getActualOver());
                out.add(o);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("forecastDate", fd);
            m.put("rows", out);
            return Result.OK(m);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-人工标注保存")
    @ApiOperation(value = "保存人工标注(实际是否超标)")
    @PostMapping("/label/save")
    public Result<?> labelSave(@RequestBody String json) {
        try {
            JSONObject o = JSON.parseObject(json);
            String pointMac = o.getString("pointMac");
            String targetDate = o.getString("targetDate");
            int actual = o.getIntValue("actualOver");
            if (StringUtils.isBlank(pointMac) || StringUtils.isBlank(targetDate)) {
                return Result.error("参数不完整");
            }
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            AiLabelRecord existing = labelRecordMapper.selectOne(new QueryWrapper<AiLabelRecord>()
                    .eq("point_mac", pointMac).eq("target_date", targetDate));
            if (existing == null) {
                existing = new AiLabelRecord();
                existing.setId(UUID.randomUUID().toString().replace("-", ""));
                existing.setPointMac(pointMac);
                existing.setTargetDate(df.parse(targetDate));
                existing.setLabelSource("manual");
                existing.setCreateTime(new Date());
            }
            existing.setActualOver(actual);
            existing.setLabelTime(new Date());
            if (existing.getId() == null) {
                existing.setId(UUID.randomUUID().toString().replace("-", ""));
            }
            if (labelRecordMapper.selectById(existing.getId()) != null) {
                labelRecordMapper.updateById(existing);
            } else {
                labelRecordMapper.insert(existing);
            }
            return Result.OK("标注成功");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/label/list")
    public Result<?> labelList(@RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                               @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) {
        Page<AiLabelRecord> page = new Page<>(pageNo, pageSize);
        QueryWrapper<AiLabelRecord> qw = new QueryWrapper<>();
        qw.orderByDesc("label_time");
        return Result.OK(labelRecordMapper.selectPage(page, qw));
    }

    // ============ 演示数据(本地验收用) ============

    @AutoLog(value = "AI预测-生成演示数据")
    @ApiOperation(value = "生成演示历史数据(打标aipredict-demo,可清理)")
    @GetMapping("/demo/generate")
    public Result<?> demoGenerate(@RequestParam(name = "days", defaultValue = "60") int days) {
        try {
            return Result.OK(demoService.generate(days));
        } catch (Exception e) {
            log.error("演示数据生成失败", e);
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "AI预测-清理演示数据")
    @ApiOperation(value = "清理演示数据与中间结果")
    @GetMapping("/demo/clear")
    public Result<?> demoClear() {
        try {
            int n = demoService.clearRaw();
            // 同步清理AI中间/结果数据(全部清理便于重新生成)
            aiBizMapper.clearAllInput();
            forecastResultMapper.delete(new QueryWrapper<>());
            companyRiskMapper.delete(new QueryWrapper<>());
            anomalyRecordMapper.delete(new QueryWrapper<>());
            labelRecordMapper.delete(new QueryWrapper<>());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deleted", n);
            m.put("msg", "演示主数据已清理,AI结果表已清空,可重新生成演示数据");
            return Result.OK(m);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
