package org.jeecg.modules.psms.aipredict.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.psms.aipredict.entity.AiAnomalyRecord;
import org.jeecg.modules.psms.aipredict.mapper.AiAnomalyRecordMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiBizMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 异常识别(自动生成预警记录):
 *  模式1 PURIFIER_OFF_OVER  : 净化器功率为0 且 油烟浓度超阈值 持续>=30分钟 (阈值对齐GB18483)
 *  模式2 CONTINUOUS_OVER    : 油烟浓度持续超阈值 >=30分钟
 * 扫描ai_predict_input对齐时槽,识别连续满足模式的时段并落 ai_anomaly_record
 */
@Slf4j
@Service
public class AiAnomalyService {

    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiAnomalyRecordMapper anomalyRecordMapper;
    @Autowired
    private AiConfigHolder cfg;
    @Autowired
    private AiLlmAttributionService llmService;

    /** 模式编码 */
    public static final String CODE_PURIFIER_OFF_OVER = "PURIFIER_OFF_OVER";
    public static final String CODE_CONTINUOUS_OVER = "CONTINUOUS_OVER";

    /**
     * 扫描一个时间范围内所有测点的异常
     *
     * @param startDayStr yyyy-MM-dd 起始日
     * @param endDayStr   yyyy-MM-dd 结束日
     * @return 新增异常条数
     */
    public int scanRange(String startDayStr, String endDayStr) throws Exception {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        Date now = new Date();
        Date startDay = df.parse(startDayStr == null || startDayStr.isEmpty() ? df.format(now) : startDayStr);
        Date endDay = df.parse(endDayStr == null || endDayStr.isEmpty() ? df.format(now) : endDayStr);
        if (startDay.after(endDay)) {
            Date t = startDay;
            startDay = endDay;
            endDay = t;
        }
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();
        int alignMin = Math.max(1, cfg.getInt("import.align.minutes", 5));
        int minDuration = Math.max(alignMin, cfg.getInt("anomaly.min.duration", 30));
        long toleranceMs = alignMin * 60_000L * 2L; // 允许跨1个时槽的缺口,容错2倍对齐粒度

        int created = 0;
        Calendar c = Calendar.getInstance();
        c.setTime(startDay);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        Date scanStart = c.getTime();
        c.setTime(endDay);
        c.add(Calendar.DAY_OF_MONTH, 1);
        Date scanEndEx = c.getTime();

        // 全部测点
        List<Map<String, Object>> points = aiBizMapper.listPointOptions();
        for (Map<String, Object> p : points) {
            String pointMac = String.valueOf(p.get("pointMac"));
            String companyId = p.get("companyId") == null ? null : String.valueOf(p.get("companyId"));
            String companyName = p.get("companyName") == null ? null : String.valueOf(p.get("companyName"));
            // 逐日取时槽
            Calendar dc = Calendar.getInstance();
            dc.setTime(scanStart);
            List<Map<String, Object>> slots = new ArrayList<>();
            while (dc.getTime().before(scanEndEx)) {
                String day = df.format(dc.getTime());
                List<Map<String, Object>> one = aiBizMapper.listSlotByPointDate(pointMac, day, srcTable);
                slots.addAll(one);
                dc.add(Calendar.DAY_OF_MONTH, 1);
            }
            slots.sort(Comparator.comparing(o -> String.valueOf(o.get("dataTime"))));
            if (slots.size() < 2) {
                continue;
            }
            // 模式1: 功率0且超阈值
            List<List<Map<String, Object>>> runs1 = detectRuns(slots, CODE_PURIFIER_OFF_OVER, alignMin, toleranceMs);
            for (List<Map<String, Object>> run : runs1) {
                if (durationMin(run, alignMin) >= minDuration) {
                    created += insertRecord(pointMac, companyId, companyName, CODE_PURIFIER_OFF_OVER,
                            "净化器功率为0且油烟浓度超阈值持续" + durationMin(run, alignMin) + "分钟", run, alignMin);
                }
            }
            // 模式2: 连续超标
            List<List<Map<String, Object>>> runs2 = detectRuns(slots, CODE_CONTINUOUS_OVER, alignMin, toleranceMs);
            for (List<Map<String, Object>> run : runs2) {
                if (durationMin(run, alignMin) >= minDuration) {
                    created += insertRecord(pointMac, companyId, companyName, CODE_CONTINUOUS_OVER,
                            "油烟浓度持续超阈值" + durationMin(run, alignMin) + "分钟", run, alignMin);
                }
            }
        }
        log.info("AI异常识别完成 范围[{},{}] 新增={}", df.format(scanStart), df.format(scanEndEx), created);
        return created;
    }

    /** 连续时段识别: conditionKey 模式下连续满足的时槽串(允许小缺口) */
    private List<List<Map<String, Object>>> detectRuns(List<Map<String, Object>> slots,
                                                       String code, int alignMin, long toleranceMs) {
        List<List<Map<String, Object>>> runs = new ArrayList<>();
        List<Map<String, Object>> cur = new ArrayList<>();
        long prevTime = -1;
        for (Map<String, Object> s : slots) {
            boolean hit;
            if (CODE_PURIFIER_OFF_OVER.equals(code)) {
                hit = over(s) && powerZero(s);
            } else {
                hit = over(s);
            }
            long t = parseTime(s.get("dataTime"));
            if (hit) {
                if (cur.isEmpty() || (prevTime >= 0 && t - prevTime <= toleranceMs)) {
                    cur.add(s);
                } else {
                    if (cur.size() >= 1) {
                        runs.add(new ArrayList<>(cur));
                    }
                    cur = new ArrayList<>();
                    cur.add(s);
                }
                prevTime = t;
            } else {
                if (cur.size() >= 1) {
                    runs.add(new ArrayList<>(cur));
                }
                cur = new ArrayList<>();
                prevTime = -1;
            }
        }
        if (cur.size() >= 1) {
            runs.add(cur);
        }
        // 合并可拼接时段(数据缺口<=容差)
        return mergeRuns(runs, toleranceMs, alignMin);
    }

    private List<List<Map<String, Object>>> mergeRuns(List<List<Map<String, Object>>> runs, long tol, int align) {
        if (runs.size() <= 1) {
            return runs;
        }
        List<List<Map<String, Object>>> out = new ArrayList<>();
        List<Map<String, Object>> prev = runs.get(0);
        for (int i = 1; i < runs.size(); i++) {
            List<Map<String, Object>> curRun = runs.get(i);
            long prevEnd = parseTime(prev.get(prev.size() - 1).get("dataTime"));
            long curStart = parseTime(curRun.get(0).get("dataTime"));
            if (curStart - prevEnd <= tol) {
                List<Map<String, Object>> merged = new ArrayList<>(prev);
                merged.addAll(curRun);
                prev = merged;
            } else {
                out.add(prev);
                prev = curRun;
            }
        }
        out.add(prev);
        return out;
    }

    private boolean over(Map<String, Object> s) {
        Object f = s.get("overFlag");
        return f != null && "1".equals(String.valueOf(f));
    }

    private boolean powerZero(Map<String, Object> s) {
        Object cnt = s.get("purifierPowerZeroCnt");
        Object sample = s.get("sampleCnt");
        if (cnt != null && sample != null) {
            long c = Long.parseLong(String.valueOf(cnt));
            long n = Long.parseLong(String.valueOf(sample));
            // 该时槽样本净化器全部功率为0才判定功率为0
            return n > 0 && c >= n;
        }
        return false;
    }

    private long parseTime(Object o) {
        if (o instanceof Date) {
            return ((Date) o).getTime();
        }
        if (o instanceof java.time.LocalDateTime) {
            return Date.from(((java.time.LocalDateTime) o).atZone(java.time.ZoneId.systemDefault()).toInstant()).getTime();
        }
        if (o instanceof java.time.LocalDate) {
            return Date.from(((java.time.LocalDate) o).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()).getTime();
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(String.valueOf(o)).getTime();
        } catch (Exception e) {
            try {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(String.valueOf(o)).getTime();
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    private int durationMin(List<Map<String, Object>> run, int alignMin) {
        if (run == null || run.isEmpty()) {
            return 0;
        }
        long st = parseTime(run.get(0).get("dataTime"));
        long en = parseTime(run.get(run.size() - 1).get("dataTime"));
        if (st < 0 || en < 0) {
            return 0;
        }
        return (int) ((en - st) / 60000L) + alignMin;
    }

    /** 插入异常记录(唯一约束point+code+start,冲突忽略)并自动尝试LLM归因(失败不影响落库) */
    private int insertRecord(String pointMac, String companyId, String companyName, String code,
                             String desc, List<Map<String, Object>> run, int alignMin) {
        try {
            long st = parseTime(run.get(0).get("dataTime"));
            long en = parseTime(run.get(run.size() - 1).get("dataTime"));
            AiAnomalyRecord rec = new AiAnomalyRecord();
            rec.setId(UUID.randomUUID().toString().replace("-", ""));
            rec.setAnomalyCode(code);
            rec.setAnomalyName(code.equals(CODE_PURIFIER_OFF_OVER)
                    ? "净化器功率为0且油烟超阈值" : "油烟浓度持续超标");
            rec.setPointMac(pointMac);
            rec.setCompanyId(companyId);
            rec.setCompanyName(companyName);
            rec.setStartTime(new Date(st));
            rec.setEndTime(new Date(en));
            rec.setDurationMin(durationMin(run, alignMin));
            rec.setMaxLampblack(maxOf(run));
            rec.setAvgLampblack(avgOf(run));
            rec.setThresholdUsed(cfg.getDecimal("over.threshold.lampblack", new BigDecimal("2.0")));
            rec.setHandleStatus(0);
            rec.setAttributionStatus(0);
            rec.setDescription(desc);
            rec.setCreateTime(new Date());
            anomalyRecordMapper.insert(rec);
            // 异步触发LLM归因(失败自动降级规则,不影响主流程)
            try {
                attributionFor(rec);
            } catch (Exception e) {
                log.warn("异常归因调用失败(已降级), id={}", rec.getId());
            }
            return 1;
        } catch (Exception e) {
            // 唯一键冲突=已存在同测点同模式同起点,忽略
            log.debug("异常记录已存在忽略: {} code={}", pointMac, code);
            return 0;
        }
    }

    /** 对单条记录执行归因并回写 */
    public AiAnomalyRecord attributionFor(AiAnomalyRecord rec) {
        if (rec == null) {
            return null;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("pointMac", rec.getPointMac());
        ctx.put("companyName", rec.getCompanyName() == null ? "" : rec.getCompanyName());
        ctx.put("anomalyCode", rec.getAnomalyCode());
        ctx.put("anomalyName", rec.getAnomalyName());
        ctx.put("startTime", fmt(rec.getStartTime()));
        ctx.put("endTime", fmt(rec.getEndTime()));
        ctx.put("durationMin", rec.getDurationMin());
        ctx.put("maxConcentration", rec.getMaxLampblack());
        ctx.put("continuousOver", CODE_CONTINUOUS_OVER.equals(rec.getAnomalyCode()));
        String json = llmService.attribute(ctx);
        rec.setAttributionJson(json);
        // 判别引擎
        boolean fromLlm = json != null && json.contains("\"engine\":\"llm\"");
        rec.setAttributionStatus(fromLlm ? 1 : 2);
        rec.setUpdateTime(new Date());
        anomalyRecordMapper.updateById(rec);
        return rec;
    }

    /** 未归因记录批量处理 */
    public int attributionPending() {
        QueryWrapper<AiAnomalyRecord> qw = new QueryWrapper<>();
        qw.eq("attribution_status", 0).isNull("attribution_json").last("limit 50");
        List<AiAnomalyRecord> list = anomalyRecordMapper.selectList(qw);
        int n = 0;
        for (AiAnomalyRecord r : list) {
            try {
                attributionFor(r);
                n++;
            } catch (Exception e) {
                log.warn("归因处理异常 id={}", r.getId());
            }
        }
        return n;
    }

    private BigDecimal maxOf(List<Map<String, Object>> run) {
        BigDecimal m = BigDecimal.ZERO;
        for (Map<String, Object> s : run) {
            if (s.get("lampblackMax") != null) {
                try {
                    BigDecimal v = new BigDecimal(String.valueOf(s.get("lampblackMax")));
                    if (v.compareTo(m) > 0) {
                        m = v;
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return m;
    }

    private BigDecimal avgOf(List<Map<String, Object>> run) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (Map<String, Object> s : run) {
            if (s.get("lampblackAvg") != null) {
                try {
                    sum = sum.add(new BigDecimal(String.valueOf(s.get("lampblackAvg"))));
                    n++;
                } catch (Exception ignore) {
                }
            }
        }
        return n == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    private String fmt(Date d) {
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }
}
