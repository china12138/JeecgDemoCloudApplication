package org.jeecg.modules.psms.aipredict.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.psms.aipredict.entity.AiAnomalyRecord;
import org.jeecg.modules.psms.aipredict.entity.AiCompanyRisk;
import org.jeecg.modules.psms.aipredict.entity.AiForecastResult;
import org.jeecg.modules.psms.aipredict.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 预测看板数据聚合:
 *  - 超标趋势折线(历史实际超标率+未来7天预测概率)
 *  - 风险企业TOP5榜单
 *  - 异常事件时间线
 */
@Slf4j
@Service
public class AiDashboardService {

    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiForecastResultMapper forecastResultMapper;
    @Autowired
    private AiCompanyRiskMapper companyRiskMapper;
    @Autowired
    private AiAnomalyRecordMapper anomalyRecordMapper;
    @Autowired
    private AiConfigHolder cfg;

    /**
     * 看板总览
     */
    public JSONObject overview() {
        JSONObject o = new JSONObject();
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        // 测点/企业
        List<Map<String, Object>> pts = aiBizMapper.listPointOptions();
        o.put("pointTotal", pts == null ? 0 : pts.size());
        Set<String> companySet = new HashSet<>();
        if (pts != null) {
            for (Map<String, Object> p : pts) {
                if (p.get("companyId") != null) {
                    companySet.add(String.valueOf(p.get("companyId")));
                }
            }
        }
        o.put("companyTotal", companySet.size());
        // 最新预测日
        String lastForecastDay = null;
        Map<String, Object> mxd = aiBizMapper.selectLastForecastDate();
        if (mxd != null && mxd.get("maxDay") != null) {
            lastForecastDay = String.valueOf(mxd.get("maxDay"));
        }
        o.put("lastForecastDay", lastForecastDay);
        // 各风险等级企业数(最新预测日)
        JSONObject riskStat = new JSONObject();
        riskStat.put("high", 0);
        riskStat.put("mid", 0);
        riskStat.put("low", 0);
        riskStat.put("verylow", 0);
        if (lastForecastDay != null) {
            QueryWrapper<AiCompanyRisk> qw2 = new QueryWrapper<>();
            qw2.eq("forecast_date", lastForecastDay);
            List<AiCompanyRisk> risks = companyRiskMapper.selectList(qw2);
            if (risks != null) {
                for (AiCompanyRisk r : risks) {
                    String lv = r.getRiskLevel() == null ? "verylow" : r.getRiskLevel();
                    riskStat.put(lv, riskStat.getIntValue(lv) + 1);
                }
            }
        }
        o.put("riskStat", riskStat);
        // 待处理异常数
        QueryWrapper<AiAnomalyRecord> qa = new QueryWrapper<>();
        qa.eq("handle_status", 0);
        o.put("pendingAnomaly", anomalyRecordMapper.selectCount(qa));
        // 中间数据量
        o.put("inputDays", countInputDays(srcTable));
        return o;
    }

    private int countInputDays(String srcTable) {
        try {
            List<Map<String, Object>> r = aiBizMapper.statDailyByCompany("2000-01-01", "2099-12-31", srcTable);
            Set<String> days = new HashSet<>();
            if (r != null) {
                for (Map<String, Object> m : r) {
                    days.add(String.valueOf(m.get("statDate")));
                }
            }
            return days.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 某测点趋势: 返回 {x:[日期], actual:[实际超标率], forecast:[预测概率(未来)]}
     */
    public JSONObject trend(String pointMac, String companyId, int backDays) throws Exception {
        JSONObject out = new JSONObject();
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -backDays);
        String startDay = df.format(c.getTime());
        String today = df.format(new Date());
        // 测点选择: 优先pointMac,否则取企业第一个测点
        String pMac = pointMac;
        if (StringUtils.isBlank(pMac)) {
            List<Map<String, Object>> pts = aiBizMapper.listPointOptions();
            for (Map<String, Object> p : pts) {
                if (companyId == null || companyId.equals(String.valueOf(p.get("companyId")))) {
                    pMac = String.valueOf(p.get("pointMac"));
                    break;
                }
            }
        }
        if (StringUtils.isBlank(pMac)) {
            out.put("empty", true);
            return out;
        }
        // 历史实际(当日是否发生超标: 0/1,与预测概率同为"日级超标"口径)
        List<Map<String, Object>> dayStats = aiBizMapper.statDailyByPoint(startDay, today, srcTable);
        Map<String, BigDecimal> actualMap = new TreeMap<>();
        for (Map<String, Object> d : dayStats) {
            if (!pMac.equals(String.valueOf(d.get("pointMac")))) {
                continue;
            }
            BigDecimal slotCnt = toDec(d.get("slotCnt"));
            BigDecimal overSlot = toDec(d.get("overSlotCnt"));
            if (slotCnt != null && slotCnt.compareTo(BigDecimal.ZERO) > 0) {
                boolean dayOver = overSlot != null && overSlot.compareTo(BigDecimal.ZERO) > 0;
                actualMap.put(String.valueOf(d.get("statDate")), dayOver ? BigDecimal.ONE : BigDecimal.ZERO);
            }
        }
        // 未来预测(最新预测日 >= today-1)
        String lastFd = null;
        QueryWrapper<AiForecastResult> qf = new QueryWrapper<>();
        qf.eq("point_mac", pMac).ge("target_date", today)
                .select("DISTINCT forecast_date").orderByDesc("forecast_date").last("limit 1");
        List<AiForecastResult> fds = forecastResultMapper.selectList(qf);
        if (fds != null && !fds.isEmpty()) {
            lastFd = df.format(fds.get(0).getForecastDate());
        }
        Map<String, BigDecimal> forecastMap = new TreeMap<>();
        if (lastFd != null) {
            QueryWrapper<AiForecastResult> qfr = new QueryWrapper<>();
            qfr.eq("point_mac", pMac).eq("forecast_date", lastFd).ge("target_date", today);
            List<AiForecastResult> frs = forecastResultMapper.selectList(qfr);
            if (frs != null) {
                for (AiForecastResult r : frs) {
                    forecastMap.put(df.format(r.getTargetDate()), r.getExceedProb());
                }
            }
        }
        JSONArray x = new JSONArray();
        JSONArray actual = new JSONArray();
        JSONArray forecast = new JSONArray();
        Set<String> days = new TreeSet<>(actualMap.keySet());
        days.addAll(forecastMap.keySet());
        for (String d : days) {
            x.add(d);
            actual.add(actualMap.get(d) == null ? null : actualMap.get(d).doubleValue());
            forecast.add(forecastMap.get(d) == null ? null : forecastMap.get(d).doubleValue());
        }
        out.put("pointMac", pMac);
        out.put("x", x);
        out.put("actual", actual);
        out.put("forecast", forecast);
        return out;
    }

    /**
     * 风险企业TOP5(最新预测日)
     */
    public List<AiCompanyRisk> topRisk(int top) {
        String lastFd = lastForecastDay();
        if (lastFd == null) {
            return Collections.emptyList();
        }
        QueryWrapper<AiCompanyRisk> qw = new QueryWrapper<>();
        qw.eq("forecast_date", lastFd).orderByAsc("rank_no")
                .last("limit " + Math.max(1, Math.min(20, top)));
        return companyRiskMapper.selectList(qw);
    }

    /** 最新预测日(所有测点) */
    public String lastForecastDay() {
        Map<String, Object> mxd = aiBizMapper.selectLastForecastDate();
        if (mxd != null && mxd.get("maxDay") != null) {
            return String.valueOf(mxd.get("maxDay"));
        }
        return null;
    }

    /**
     * 异常事件时间线(最近days天)
     */
    public List<AiAnomalyRecord> anomalyTimeline(int days) {
        QueryWrapper<AiAnomalyRecord> qw = new QueryWrapper<>();
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -Math.max(1, days));
        qw.ge("start_time", c.getTime()).orderByDesc("start_time").last("limit 200");
        return anomalyRecordMapper.selectList(qw);
    }

    /** 近7天每企业每日超标统计(供企业维度趋势) */
    public JSONArray companyDaily(String companyId, int backDays) throws Exception {
        JSONArray out = new JSONArray();
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -Math.max(7, backDays));
        List<Map<String, Object>> stat = aiBizMapper.statDailyByCompany(df.format(c.getTime()), df.format(new Date()), srcTable);
        Map<String, Map<String, Object>> byDay = new TreeMap<>();
        if (stat != null) {
            for (Map<String, Object> m : stat) {
                if (companyId != null && !companyId.isEmpty()
                        && !companyId.equals(String.valueOf(m.get("companyId")))) {
                    continue;
                }
                String d = String.valueOf(m.get("statDate"));
                if (!byDay.containsKey(d)) {
                    byDay.put(d, m);
                } else {
                    Map<String, Object> agg = byDay.get(d);
                    agg.put("slotCnt", toLong(agg.get("slotCnt")) + toLong(m.get("slotCnt")));
                    agg.put("overSlotCnt", toLong(agg.get("overSlotCnt")) + toLong(m.get("overSlotCnt")));
                    agg.put("overCnt", toLong(agg.get("overCnt")) + toLong(m.get("overCnt")));
                    agg.put("sampleCnt", toLong(agg.get("sampleCnt")) + toLong(m.get("sampleCnt")));
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : byDay.entrySet()) {
            JSONObject o = new JSONObject();
            Map<String, Object> m = e.getValue();
            o.put("date", e.getKey());
            o.put("slotCnt", m.get("slotCnt"));
            o.put("overSlotCnt", m.get("overSlotCnt"));
            o.put("overCnt", m.get("overCnt"));
            o.put("sampleCnt", m.get("sampleCnt"));
            out.add(o);
        }
        return out;
    }

    private BigDecimal toDec(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private long toLong(Object o) {
        if (o == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
