package org.jeecg.modules.psms.aipredict.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.psms.aipredict.entity.AiEvalReport;
import org.jeecg.modules.psms.aipredict.entity.AiForecastResult;
import org.jeecg.modules.psms.aipredict.entity.AiLabelRecord;
import org.jeecg.modules.psms.aipredict.mapper.AiBizMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiEvalReportMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiForecastResultMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiLabelRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 预测准确率自动评估报告(COULD项):
 *  对已到期的预测(目标日已过去),用ai_predict_input实际超标做真值回填,
 *  支持人工标注覆盖,计算准确率(目标>=80%),产出混淆矩阵报告
 */
@Slf4j
@Service
public class AiEvalService {

    @Autowired
    private AiForecastResultMapper forecastResultMapper;
    @Autowired
    private AiEvalReportMapper evalReportMapper;
    @Autowired
    private AiLabelRecordMapper labelRecordMapper;
    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiConfigHolder cfg;

    /**
     * 生成评估报告
     *
     * @param forecastDateStr 只评估该预测生成日的到期预测,空=最近一个有到期结果的预测日
     * @return 报告实体
     */
    public AiEvalReport runEval(String forecastDateStr) throws Exception {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();
        // 1) 确定要评估的预测生成日
        String fd = forecastDateStr;
        if (fd == null || fd.isEmpty()) {
            QueryWrapper<AiForecastResult> qw = new QueryWrapper<>();
            qw.lt("target_date", df.format(new Date()))
                    .select("DISTINCT forecast_date")
                    .orderByDesc("forecast_date").last("limit 1");
            List<AiForecastResult> fds = forecastResultMapper.selectList(qw);
            if (fds != null && !fds.isEmpty()) {
                fd = df.format(fds.get(0).getForecastDate());
            } else {
                AiEvalReport empty = new AiEvalReport();
                empty.setId(UUID.randomUUID().toString().replace("-", ""));
                empty.setEvalDate(new Date());
                empty.setPassFlag(0);
                empty.setSampleCnt(0);
                empty.setAccuracy(BigDecimal.ZERO);
                empty.setCreateTime(new Date());
                empty.setReportJson("{\"msg\":\"暂无可评估的已到期预测\"}");
                return empty;
            }
        }
        // 2) 取该预测日全部到期结果
        QueryWrapper<AiForecastResult> qw2 = new QueryWrapper<>();
        qw2.eq("forecast_date", fd).le("target_date", df.format(new Date()));
        List<AiForecastResult> forecasts = forecastResultMapper.selectList(qw2);
        if (forecasts == null || forecasts.isEmpty()) {
            AiEvalReport empty = new AiEvalReport();
            empty.setId(UUID.randomUUID().toString().replace("-", ""));
            empty.setEvalDate(new Date());
            empty.setPassFlag(0);
            empty.setSampleCnt(0);
            empty.setAccuracy(BigDecimal.ZERO);
            empty.setCreateTime(new Date());
            empty.setReportJson("{\"msg\":\"预测日 " + fd + " 无已到期结果\"}");
            return empty;
        }
        // 3) 实际真值: 目标日是否发生超标(人工标注优先,无标注取当日超标时槽回填)
        Date minT = forecasts.stream().map(AiForecastResult::getTargetDate).min(Date::compareTo).orElse(new Date());
        Date maxT = forecasts.stream().map(AiForecastResult::getTargetDate).max(Date::compareTo).orElse(new Date());
        String startDay = df.format(minT);
        String endDay = df.format(maxT);
        List<Map<String, Object>> actuals = aiBizMapper.listDailyOverFlag(startDay, endDay, srcTable);
        Map<String, Integer> actualMap = new HashMap<>();
        for (Map<String, Object> a : actuals) {
            actualMap.put(String.valueOf(a.get("pointMac")) + "|" + a.get("statDate"),
                    "1".equals(String.valueOf(a.get("overFlag"))) ? 1 : 0);
        }
        // 人工标注覆盖(标注集: 标注人根据实际排放情况给出 实际是否超标)
        List<AiLabelRecord> labels = labelRecordMapper.selectList(new QueryWrapper<AiLabelRecord>());
        Map<String, Integer> labelMap = new HashMap<>();
        if (labels != null) {
            for (AiLabelRecord l : labels) {
                if (l.getActualOver() != null) {
                    labelMap.put(l.getPointMac() + "|" + df.format(l.getTargetDate()), l.getActualOver());
                }
            }
        }
        // 4) 混淆矩阵与明细
        int tp = 0, tn = 0, fp = 0, fn = 0, useManual = 0;
        JSONArray detail = new JSONArray();
        BigDecimal posTh = cfg.getDecimal("eval.positive.threshold", new BigDecimal("0.5"));
        for (AiForecastResult f : forecasts) {
            String key = f.getPointMac() + "|" + df.format(f.getTargetDate());
            Integer actual = labelMap.containsKey(key) ? labelMap.get(key) : actualMap.get(key);
            if (actual == null) {
                continue; // 无实际真值,不入样本
            }
            if (labelMap.containsKey(key)) {
                useManual++;
            }
            int predict = f.getExceedProb().compareTo(posTh) >= 0 ? 1 : 0;
            if (predict == 1 && actual == 1) {
                tp++;
            } else if (predict == 0 && actual == 0) {
                tn++;
            } else if (predict == 1 && actual == 0) {
                fp++;
            } else {
                fn++;
            }
            JSONObject o = new JSONObject();
            o.put("pointMac", f.getPointMac());
            o.put("targetDate", df.format(f.getTargetDate()));
            o.put("forecastProb", f.getExceedProb());
            o.put("predict", predict);
            o.put("actual", actual);
            o.put("labelSource", labelMap.containsKey(key) ? "manual" : "auto");
            detail.add(o);
        }
        int sample = tp + tn + fp + fn;
        BigDecimal acc = sample == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(tp + tn).divide(BigDecimal.valueOf(sample), 4, RoundingMode.HALF_UP);
        BigDecimal target = cfg.getDecimal("eval.target.accuracy", new BigDecimal("0.80"));
        JSONObject conf = new JSONObject();
        conf.put("tp", tp);
        conf.put("tn", tn);
        conf.put("fp", fp);
        conf.put("fn", fn);
        JSONObject report = new JSONObject();
        report.put("confusion", conf);
        report.put("precision", tp + fp == 0 ? 0 : round(BigDecimal.valueOf(tp).divide(BigDecimal.valueOf(tp + fp), 4, RoundingMode.HALF_UP)));
        report.put("recall", tp + fn == 0 ? 0 : round(BigDecimal.valueOf(tp).divide(BigDecimal.valueOf(tp + fn), 4, RoundingMode.HALF_UP)));
        report.put("threshold", posTh);
        report.put("manualLabelCnt", useManual);
        report.put("detail", detail);
        // 5) 入库
        AiEvalReport er = new AiEvalReport();
        er.setId(UUID.randomUUID().toString().replace("-", ""));
        er.setEvalDate(new Date());
        er.setWindowStart(parseDate(fd));
        er.setWindowEnd(maxT);
        er.setSampleCnt(sample);
        er.setCorrectCnt(tp + tn);
        er.setAccuracy(acc);
        er.setTargetAcc(target);
        er.setPassFlag(acc.compareTo(target) >= 0 ? 1 : 0);
        er.setReportJson(report.toJSONString());
        er.setCreateTime(new Date());
        evalReportMapper.insert(er);
        log.info("AI评估完成 预测日={} 样本={} 准确率={} 达标={}", fd, sample, acc, acc.compareTo(target) >= 0);
        return er;
    }

    public List<AiEvalReport> listReports(int limit) {
        QueryWrapper<AiEvalReport> qw = new QueryWrapper<>();
        qw.orderByDesc("create_time").last("limit " + Math.max(1, Math.min(50, limit)));
        return evalReportMapper.selectList(qw);
    }

    private Date parseDate(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(s);
        } catch (Exception e) {
            return new Date();
        }
    }

    private BigDecimal round(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }
}
