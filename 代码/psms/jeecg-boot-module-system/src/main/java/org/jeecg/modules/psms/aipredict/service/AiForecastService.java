package org.jeecg.modules.psms.aipredict.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.psms.aipredict.entity.AiCompanyRisk;
import org.jeecg.modules.psms.aipredict.entity.AiForecastResult;
import org.jeecg.modules.psms.aipredict.mapper.AiBizMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiCompanyRiskMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiForecastResultMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 超标趋势预测(每日定时):
 *  规则基线 = 阈值基线(历史超标频率) + 环比(近端与更早超标水平差异) + 滑动窗口统计
 *  输出: 每个监测点未来7天超标概率 + 风险等级 + 企业风险聚合TOP榜单
 * 数据不足时自动降级(纯规则保证可用),大模型不可用时本服务不依赖外部调用
 */
@Slf4j
@Service
public class AiForecastService {

    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiForecastResultMapper forecastResultMapper;
    @Autowired
    private AiCompanyRiskMapper companyRiskMapper;
    @Autowired
    private AiConfigHolder cfg;

    /** 规则基线-阈值概率(GB18483按2.0mg/m3可配,此处为超阈值频率历史基线权重) */
    public static final BigDecimal PROB_MIN = new BigDecimal("0.01");
    public static final BigDecimal PROB_MAX = new BigDecimal("0.98");

    /**
     * 对全量测点执行每日预测
     *
     * @param forecastDateStr 预测生成日 yyyy-MM-dd,默认今天
     * @return 预测行数
     */
    public int runDailyForecast(String forecastDateStr) throws Exception {
        Date forecastDate = new Date();
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        if (StringUtils.isNotBlank(forecastDateStr)) {
            forecastDate = df.parse(forecastDateStr.trim());
        }
        String fDay = df.format(forecastDate);
        // 历史统计窗口 = forecast.window.days
        int windowDays = Math.max(5, cfg.getInt("forecast.window.days", 30));
        int horizon = Math.max(1, cfg.getInt("forecast.horizon.days", 7));
        int ringDays = Math.max(1, cfg.getInt("forecast.ring.days", 3));
        int minDays = Math.max(3, cfg.getInt("forecast.min.days", 7));
        String srcTable = cfg.getStr("import.src.tables", "bu_lampblack_data").split(",")[0].trim();

        Calendar c = Calendar.getInstance();
        c.setTime(forecastDate);
        c.add(Calendar.DAY_OF_MONTH, -windowDays - ringDays);
        String startDay = df.format(c.getTime());
        String endDay = df.format(forecastDate);
        // 每日每测点超标统计
        List<Map<String, Object>> dayStats = aiBizMapper.statDailyByPoint(startDay, endDay, srcTable);
        Map<String, List<Map<String, Object>>> byPoint = new LinkedHashMap<>();
        for (Map<String, Object> row : dayStats) {
            byPoint.computeIfAbsent(String.valueOf(row.get("pointMac")), k -> new ArrayList<>()).add(row);
        }
        // 只保留与预测日同天/历史的数据,目标日为未来
        int inserted = 0;
        // 删除该预测日旧结果,支持重复执行覆盖
        QueryWrapper<AiForecastResult> delQw = new QueryWrapper<>();
        delQw.eq("forecast_date", df.format(forecastDate));
        forecastResultMapper.delete(delQw);
        // 企业风险聚合
        Map<String, AiCompanyRiskAcc> companyAcc = new LinkedHashMap<>();

        for (Map.Entry<String, List<Map<String, Object>>> e : byPoint.entrySet()) {
            String pointMac = e.getKey();
            List<Map<String, Object>> days = e.getValue().stream()
                    .sorted((a, b) -> String.valueOf(a.get("statDate")).compareTo(String.valueOf(b.get("statDate"))))
                    .collect(Collectors.toList());
            if (days.isEmpty()) {
                continue;
            }
            // 构建 日期->当日是否超标(当日存在超标时槽=1) 序列(过滤无数据日)
            Map<String, BigDecimal> rateMap = new TreeMap<>();
            for (Map<String, Object> d : days) {
                BigDecimal slotCnt = toDec(d.get("slotCnt"));
                BigDecimal overSlot = toDec(d.get("overSlotCnt"));
                if (slotCnt != null && slotCnt.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal dayOver = (overSlot != null && overSlot.compareTo(BigDecimal.ZERO) > 0)
                            ? BigDecimal.ONE : BigDecimal.ZERO;
                    rateMap.put(String.valueOf(d.get("statDate")), dayOver);
                }
            }
            if (rateMap.isEmpty()) {
                continue;
            }
            String companyId = days.get(0).get("companyId") == null ? null : String.valueOf(days.get(0).get("companyId"));

            // ---------- 规则基线特征(以"当日是否发生超标"二值序列为基) ----------
            List<BigDecimal> rates = new ArrayList<>(rateMap.values());
            BigDecimal baseRate = avg(rates);                       // 阈值基线: 窗口内超标日占比
            int n = rates.size();
            int slideN = Math.min(ringDays, n);
            List<BigDecimal> recent = rates.subList(n - slideN, n); // 近端环比窗口
            List<BigDecimal> prev = n - 2 * slideN >= 0
                    ? rates.subList(n - 2 * slideN, n - slideN) : new ArrayList<>();
            BigDecimal recentRate = avg(recent);
            BigDecimal prevRate = prev.isEmpty() ? recentRate : avg(prev);
            BigDecimal momentum = recentRate.subtract(prevRate);    // 环比动量(近端-更早)
            // 滑动窗口统计(最近7日)超标日占比
            int slideWin = Math.min(7, n);
            List<BigDecimal> slide = rates.subList(n - slideWin, n);
            BigDecimal slideRate = avg(slide);

            boolean sufficient = n >= minDays;
            List<AiForecastResult> batch = new ArrayList<>();
            Calendar tc = Calendar.getInstance();
            tc.setTime(forecastDate);
            for (int i = 1; i <= horizon; i++) {
                tc.add(Calendar.DAY_OF_MONTH, 1);
                Date target = tc.getTime();
                // 综合: 基线*0.5 + 滑窗*0.3 + 环比动量(随时间衰减)*0.2
                BigDecimal decay = BigDecimal.ONE.subtract(new BigDecimal(i - 1).multiply(new BigDecimal("0.1")));
                if (decay.compareTo(BigDecimal.ZERO) < 0) {
                    decay = BigDecimal.ZERO;
                }
                BigDecimal p = baseRate.multiply(new BigDecimal("0.5"))
                        .add(slideRate.multiply(new BigDecimal("0.3")))
                        .add(momentum.multiply(new BigDecimal("0.2")).multiply(decay));
                if (p.compareTo(PROB_MIN) < 0) {
                    p = PROB_MIN;
                }
                if (p.compareTo(PROB_MAX) > 0) {
                    p = PROB_MAX;
                }
                if (!sufficient) {
                    // 数据不足降级: 压缩到保守区间并标注
                    p = p.multiply(new BigDecimal("0.6")).setScale(4, RoundingMode.HALF_UP);
                }
                p = p.setScale(4, RoundingMode.HALF_UP);
                String level = riskLevel(p);
                AiForecastResult r = new AiForecastResult();
                r.setId(UUID.randomUUID().toString().replace("-", ""));
                r.setPointMac(pointMac);
                r.setCompanyId(companyId);
                r.setForecastDate(forecastDate);
                r.setTargetDate(target);
                r.setExceedProb(p);
                r.setRiskLevel(level);
                r.setMethod("RULE");
                r.setDataSufficient(sufficient ? 1 : 0);
                r.setRemark(sufficient
                        ? "规则基线(阈值频率" + rateOf(baseRate) + ",滑窗" + rateOf(slideRate) + ",环比" + signed(momentum) + ")"
                        : "历史有效天数不足(" + n + "/" + minDays + "),概率已降级收缩");
                r.setCreateTime(new Date());
                batch.add(r);
            }
            // 批量插入
            for (AiForecastResult r : batch) {
                try {
                    forecastResultMapper.insert(r);
                    inserted++;
                } catch (Exception ex) {
                    log.warn("预测结果插入冲突忽略 point={} target={}", pointMac, r.getTargetDate());
                }
            }
            // 企业聚合: 风险分=该企业各测点未来7天最大概率的均值(取最大值测点为主)
            BigDecimal maxP = batch.stream().map(AiForecastResult::getExceedProb)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            if (StringUtils.isNotBlank(companyId)) {
                AiCompanyRiskAcc accItem = companyAcc.computeIfAbsent(companyId, k -> new AiCompanyRiskAcc());
                accItem.pointCnt++;
                if (maxP.compareTo(accItem.maxProb) > 0) {
                    accItem.maxProb = maxP;
                }
                accItem.points.add(maxP);
            }
        }
        // 写入企业风险榜单(仅对有预测的企业),补企业名
        int rank = 0;
        SimpleDateFormat df2 = new SimpleDateFormat("yyyy-MM-dd");
        QueryWrapper<AiCompanyRisk> delCr = new QueryWrapper<>();
        delCr.eq("forecast_date", df2.format(forecastDate));
        companyRiskMapper.delete(delCr);
        // 企业名称映射
        List<String> allCompanyIds = new ArrayList<>(companyAcc.keySet());
        Map<String, String> companyNameMap = new HashMap<>();
        if (!allCompanyIds.isEmpty()) {
            List<Map<String, Object>> cns = aiBizMapper.listCompanyName(allCompanyIds);
            for (Map<String, Object> cn : cns) {
                companyNameMap.put(String.valueOf(cn.get("companyId")),
                        cn.get("companyName") == null ? "" : String.valueOf(cn.get("companyName")));
            }
        }
        List<Map.Entry<String, AiCompanyRiskAcc>> sorted = companyAcc.entrySet().stream()
                .sorted((a, b) -> b.getValue().maxProb.compareTo(a.getValue().maxProb))
                .collect(Collectors.toList());
        for (Map.Entry<String, AiCompanyRiskAcc> en : sorted) {
            rank++;
            AiCompanyRiskAcc v = en.getValue();
            // 企业风险分 = 各测点worst-day概率的均值,单测点即该值
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal p : v.points) {
                sum = sum.add(p);
            }
            BigDecimal score = v.points.isEmpty() ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(v.points.size()), 4, RoundingMode.HALF_UP);
            AiCompanyRisk cr = new AiCompanyRisk();
            cr.setId(UUID.randomUUID().toString().replace("-", ""));
            cr.setCompanyId(en.getKey());
            cr.setCompanyName(companyNameMap.getOrDefault(en.getKey(), en.getKey()));
            cr.setForecastDate(forecastDate);
            cr.setRiskScore(score);
            cr.setRiskLevel(riskLevel(v.maxProb));
            cr.setPointCnt(v.pointCnt);
            cr.setRankNo(rank);
            cr.setCreateTime(new Date());
            try {
                companyRiskMapper.insert(cr);
            } catch (Exception ex) {
                log.warn("企业风险榜插入冲突忽略 company={}", en.getKey());
            }
        }
        log.info("AI预测完成 预测日={} 测点={} 结果行={} 企业={}", fDay, byPoint.size(), inserted, sorted.size());
        return inserted;
    }

    /** 名称补全: 预测/榜单后统一从base_company补企业名(由controller调用) */
    public void fillCompanyName(List<?> rows) {
        // 简单防呆: 单测点流程直接join,此处供扩展
    }

    private String riskLevel(BigDecimal p) {
        BigDecimal high = cfg.getDecimal("risk.level.high", new BigDecimal("0.60"));
        BigDecimal mid = cfg.getDecimal("risk.level.mid", new BigDecimal("0.30"));
        BigDecimal low = cfg.getDecimal("risk.level.low", new BigDecimal("0.10"));
        if (p.compareTo(high) >= 0) {
            return "high";
        }
        if (p.compareTo(mid) >= 0) {
            return "mid";
        }
        if (p.compareTo(low) >= 0) {
            return "low";
        }
        return "verylow";
    }

    private BigDecimal avg(List<BigDecimal> list) {
        if (list == null || list.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal b : list) {
            s = s.add(b);
        }
        return s.divide(BigDecimal.valueOf(list.size()), 6, RoundingMode.HALF_UP);
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

    private String rateOf(BigDecimal b) {
        return b == null ? "0" : b.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private String signed(BigDecimal b) {
        if (b == null) {
            return "0";
        }
        return (b.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + rateOf(b);
    }

    static class AiCompanyRiskAcc {
        int pointCnt = 0;
        BigDecimal maxProb = BigDecimal.ZERO;
        List<BigDecimal> points = new ArrayList<>();
    }
}
