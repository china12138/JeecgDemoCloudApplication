package org.jeecg.modules.psms.aipredict.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.psms.aipredict.entity.AiImportLog;
import org.jeecg.modules.psms.aipredict.entity.AiPredictInput;
import org.jeecg.modules.psms.aipredict.mapper.AiBizMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiImportLogMapper;
import org.jeecg.modules.psms.aipredict.mapper.AiPredictInputMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 历史数据导入任务:
 *  1)分页读取bu_lampblack_data历史数据
 *  2)清洗: 异常值过滤(负值/物理上限/空值)
 *  3)时间对齐: 按import.align.minutes对齐到统一时槽,同(测点+时槽)聚合
 *  4)净化器功率0与超标样本计数(供异常识别)
 *  5)写入预测输入中间表ai_predict_input
 */
@Slf4j
@Service
public class AiImportService {

    @Autowired
    private AiBizMapper aiBizMapper;
    @Autowired
    private AiPredictInputMapper inputMapper;
    @Autowired
    private AiImportLogMapper importLogMapper;
    @Autowired
    private AiConfigHolder cfg;

    /** 默认来源表(可在参数配置页 import.src.tables 覆写,支持逗号分隔多个区域表) */
    private static final String DEFAULT_SRC = "bu_lampblack_data";

    /**
     * 执行历史导入
     *
     * @param mode      full-全量重新导入 / increment-增量(导入源表中超过中间表最新时间的部分)
     * @param endDayStr 导入数据截止日(含, yyyy-MM-dd), 为空默认昨天
     * @return 导入日志实体
     */
    public AiImportLog importHistory(String mode, String endDayStr) {
        String srcTables = cfg.getStr("import.src.tables", DEFAULT_SRC);
        List<String> tables = Arrays.stream(srcTables.split(","))
                .map(String::trim).filter(StringUtils::isNotBlank).collect(Collectors.toList());
        if (tables.isEmpty()) {
            tables.add(DEFAULT_SRC);
        }
        String m = ("full".equalsIgnoreCase(mode)) ? "full" : "increment";
        Date start = new Date();
        AiImportLog logBean = new AiImportLog();
        logBean.setId(UUID.randomUUID().toString().replace("-", ""));
        logBean.setTaskType(m);
        logBean.setTaskStatus("running");
        logBean.setSrcTable(String.join(",", tables));
        logBean.setStartTime(start);
        logBean.setCreateTime(start);
        importLogMapper.insert(logBean);

        long readTotal = 0, outlierTotal = 0, writeTotal = 0;
        StringBuilder msg = new StringBuilder();
        try {
            for (String table : tables) {
                if (!table.matches("[A-Za-z0-9_]+")) {
                    msg.append("非法表名[").append(table).append("]跳过;");
                    continue;
                }
                long[] stat;
                try {
                    stat = doImportOneTable(table, m, endDayStr);
                } catch (Exception e1) {
                    // 偶发连接中断(长事务/连接回收),整体重试一次;任务本身可重入(先清范围再写)
                    log.warn("AI导入表{}首次失败,自动重试: {}", table, e1.getMessage());
                    stat = doImportOneTable(table, m, endDayStr);
                }
                readTotal += stat[0];
                outlierTotal += stat[1];
                writeTotal += stat[2];
            }
            logBean.setTaskStatus("success");
            msg.append("导入成功");
        } catch (Exception e) {
            logBean.setTaskStatus("fail");
            msg.append("导入失败:").append(e.getMessage());
            log.error("AI历史导入失败", e);
        }
        Date end = new Date();
        logBean.setEndTime(end);
        logBean.setCostMs(end.getTime() - start.getTime());
        logBean.setReadTotal(readTotal);
        logBean.setOutlierTotal(outlierTotal);
        logBean.setWriteTotal(writeTotal);
        logBean.setMsg(msg.toString());
        importLogMapper.updateById(logBean);
        return logBean;
    }

    /**
     * 单表导入
     *
     * @return [readTotal,outlierTotal,writeTotal]
     */
    private long[] doImportOneTable(String table, String mode, String endDayStr) throws Exception {
        long readTotal = 0, outlierTotal = 0, writeTotal = 0;
        // 增量起点: 该源表中间数据最新时槽 (时间对齐粒度下的最新时间)
        String startTimeStr = null;
        String endTimeStr = null;
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat dtf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Calendar cal = Calendar.getInstance();
        if (StringUtils.isNotBlank(endDayStr)) {
            Date ed = df.parse(endDayStr.trim());
            cal.setTime(ed);
            cal.add(Calendar.DAY_OF_MONTH, 1);
            endTimeStr = dtf.format(cal.getTime());
        } else if ("full".equals(mode)) {
            // 全量导入: 无上界(含今天)
        } else {
            // 增量默认到今天零点,即导入至昨天
            cal.setTime(new Date());
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            endTimeStr = dtf.format(cal.getTime());
        }
        if (!"full".equals(mode)) {
            QueryWrapper<AiPredictInput> qw = new QueryWrapper<>();
            qw.eq("source_table", table).orderByDesc("data_time").last("limit 1");
            AiPredictInput newest = inputMapper.selectOne(qw);
            if (newest != null && newest.getDataTime() != null) {
                cal.setTime(newest.getDataTime());
                cal.add(Calendar.MINUTE, 1);
                startTimeStr = dtf.format(cal.getTime());
            }
        }
        log.info("AI导入 表={} mode={} range=[{} , {})", table, mode, startTimeStr, endTimeStr);

        int alignMin = Math.max(1, cfg.getInt("import.align.minutes", 5));
        BigDecimal maxCap = cfg.getDecimal("import.outlier.max", new BigDecimal("100"));
        BigDecimal lampblackTh = cfg.getDecimal("over.threshold.lampblack", new BigDecimal("2.0"));
        boolean judgeAvgFirst = cfg.getBool("over.judgeAvgFirst", true);
        int batchSize = Math.max(100, cfg.getInt("import.batch.size", 2000));
        int pageSize = 5000;   // 原始行分页大小
        int pageNo = 1;
        // 先清理本范围旧中间数据,保证可重复执行/增量幂等
        if ("full".equals(mode)) {
            aiBizMapper.clearInputBySource(table);
        } else {
            aiBizMapper.clearInputByRange(startTimeStr, endTimeStr, table);
        }
        // 分批清洗聚合: key = point_mac + "|" + slotTime
        Map<String, AiPredictInput> acc = new LinkedHashMap<>();
        int sqlBatch = 0;

        while (true) {
            Page<Map<String, Object>> page = new Page<>(pageNo, pageSize);
            aiBizMapper.pageLampblackRaw(page, table, startTimeStr, endTimeStr);
            List<Map<String, Object>> rows = page.getRecords();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            readTotal += rows.size();
            // 取原始油烟记录id → 净化器功率聚合
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object idv = r.get("id");
                if (idv != null) {
                    ids.add(String.valueOf(idv));
                }
            }
            Map<String, Map<String, Object>> powerMap = new HashMap<>();
            if (!ids.isEmpty()) {
                String powerTable = cfg.getStr("import.src.power.tables", "bu_purifier_powerdata");
                if (powerTable.matches("[A-Za-z0-9_]+")) {
                    for (int i = 0; i < ids.size(); i += 500) {
                        List<String> sub = ids.subList(i, Math.min(i + 500, ids.size()));
                        List<Map<String, Object>> pm = aiBizMapper.listPurifierPowerByLampblackIds(sub, powerTable);
                        for (Map<String, Object> mp : pm) {
                            powerMap.put(String.valueOf(mp.get("buLampblackId")), mp);
                        }
                    }
                }
            }
            // 测点→企业映射
            Set<String> macSet = rows.stream().map(r -> r.get("pointMac"))
                    .filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toSet());
            Map<String, Map<String, Object>> pointMap = new HashMap<>();
            if (!macSet.isEmpty()) {
                List<Map<String, Object>> pm = aiBizMapper.listPointCompanyByMacs(new ArrayList<>(macSet));
                for (Map<String, Object> mp : pm) {
                    pointMap.put(String.valueOf(mp.get("pointMac")), mp);
                }
            }
            for (Map<String, Object> r : rows) {
                if (outlierTotal == 0 && readTotal <= 1) {
                    log.info("AI导入-首行字段调试: {}", r);
                }
                String pointMac = getStr(r, "pointMac", "point_mac");
                Date ctime = getDate(r, "createTime", "create_time");
                if (StringUtils.isBlank(pointMac) || ctime == null) {
                    if (outlierTotal < 3) {
                        log.info("AI导入-异常过滤(缺失主键/时间) point={} time={} row={}", pointMac, ctime, r);
                    }
                    outlierTotal++;
                    continue;
                }
                BigDecimal inst = toDec(get(r, "lampblackData", "lampblack_data"));
                BigDecimal avgd = toDec(get(r, "lampblackAvgdata", "lampblack_avgdata"));
                BigDecimal judge = inst;
                if (judgeAvgFirst && avgd != null && avgd.compareTo(BigDecimal.ZERO) > 0) {
                    judge = avgd;
                }
                // 异常值过滤: 浓度为负 / 超过物理上限 / 瞬时与均值均无有效值
                BigDecimal val = (inst != null && inst.compareTo(BigDecimal.ZERO) >= 0) ? inst : avgd;
                if (val == null || val.compareTo(BigDecimal.ZERO) < 0 || val.compareTo(maxCap) > 0) {
                    if (outlierTotal < 3) {
                        log.info("AI导入-异常过滤(数值越界) inst={} avgd={} cap={}", inst, avgd, maxCap);
                    }
                    outlierTotal++;
                    continue;
                }
                // 净化器功率为0: 优先取净化器电源模块聚合(max output_power<=0或开关关闭), 无功率数据则按净化器状态
                boolean powerZero;
                Map<String, Object> pw = powerMap.get(String.valueOf(r.get("id")));
                Object purifierStatus = get(r, "purifierStatus", "purifier_status");
                int purifierOn = (purifierStatus != null && "1".equals(String.valueOf(purifierStatus))) ? 1 : 0;
                int fanOn = (get(r, "fanStatus", "fan_status") != null && "1".equals(String.valueOf(get(r, "fanStatus", "fan_status")))) ? 1 : 0;
                if (pw != null) {
                    long maxPower = pw.get("maxPower") == null ? 0L : Long.parseLong(String.valueOf(pw.get("maxPower")));
                    long maxOpen = pw.get("maxOpen") == null ? 0L : Long.parseLong(String.valueOf(pw.get("maxOpen")));
                    powerZero = maxPower <= 0 || maxOpen <= 0;
                    // 若功率表有开但电源输出为0,判为功率为0
                } else {
                    powerZero = purifierOn == 0;
                }
                // 时间对齐: floor到 alignMin
                Calendar c2 = Calendar.getInstance();
                c2.setTime(ctime);
                int minuteOfDay = c2.get(Calendar.HOUR_OF_DAY) * 60 + c2.get(Calendar.MINUTE);
                int minute = (c2.get(Calendar.MINUTE) / alignMin) * alignMin;
                c2.set(Calendar.MINUTE, minute);
                c2.set(Calendar.SECOND, 0);
                c2.set(Calendar.MILLISECOND, 0);
                Date slot = c2.getTime();
                // 超标判定(油烟浓度为主判,对齐GB18483 2.0阈值可配)
                boolean over = judge != null && judge.compareTo(lampblackTh) > 0;
                // 复合异常: 功率为0 且 油烟超阈值
                boolean offOver = powerZero && over;

                BigDecimal matterV = toDec(r.get("matterData"));
                BigDecimal nmhcV = toDec(r.get("nmhcData"));
                String key = pointMac + "|" + slot.getTime();
                AiPredictInput ai = acc.get(key);
                if (ai == null) {
                    ai = new AiPredictInput();
                    ai.setId(UUID.randomUUID().toString().replace("-", ""));
                    ai.setPointMac(pointMac);
                    ai.setCompanyId(pointMap.containsKey(pointMac) ? String.valueOf(pointMap.get(pointMac).get("companyId")) : null);
                    ai.setDataTime(slot);
                    ai.setMinuteNo(minuteOfDay);
                    ai.setSampleCnt(0);
                    ai.setLampblackAvg(BigDecimal.ZERO);
                    ai.setLampblackMax(BigDecimal.ZERO);
                    ai.setMatterAvg(BigDecimal.ZERO);
                    ai.setNmhcAvg(BigDecimal.ZERO);
                    ai.setFanOnCnt(0);
                    ai.setPurifierOnCnt(0);
                    ai.setPurifierPowerZeroCnt(0);
                    ai.setOffOverCnt(0);
                    ai.setOverCnt(0);
                    ai.setOverFlag(0);
                    ai.setSourceTable(table);
                    ai.setCreateTime(new Date());
                    acc.put(key, ai);
                }
                ai.setSampleCnt(ai.getSampleCnt() + 1);
                BigDecimal m0 = ai.getLampblackAvg();
                BigDecimal instV = inst != null ? inst : avgd;
                ai.setLampblackAvg((instV == null ? BigDecimal.ZERO : instV).add(m0));
                if (instV != null && instV.compareTo(ai.getLampblackMax()) > 0) {
                    ai.setLampblackMax(instV);
                }
                if (matterV != null) {
                    ai.setMatterAvg(ai.getMatterAvg().add(matterV));
                }
                if (nmhcV != null) {
                    ai.setNmhcAvg(ai.getNmhcAvg().add(nmhcV));
                }
                if (over) {
                    ai.setOverCnt(ai.getOverCnt() + 1);
                    ai.setOverFlag(1);
                }
                if (powerZero) {
                    ai.setPurifierPowerZeroCnt(ai.getPurifierPowerZeroCnt() + 1);
                }
                if (offOver) {
                    ai.setOffOverCnt(ai.getOffOverCnt() + 1);
                }
                ai.setFanOnCnt(ai.getFanOnCnt() + fanOn);
                ai.setPurifierOnCnt(ai.getPurifierOnCnt() + purifierOn);
            }
            // 聚合满batch刷盘
            if (acc.size() >= batchSize) {
                long[] r2 = flush(acc);
                writeTotal += r2[0];
                sqlBatch += r2[1];
            }
            pageNo++;
            if ((long) pageNo * pageSize > page.getTotal() && rows.size() < pageSize) {
                break;
            }
        }
        long[] r3 = flush(acc);
        writeTotal += r3[0];
        sqlBatch += r3[1];
        log.info("AI导入完成 表={} 读{} 异常{} 写{} sqlBatch={}", table, readTotal, outlierTotal, writeTotal, sqlBatch);
        return new long[]{readTotal, outlierTotal, writeTotal};
    }

    /** 先删旧中间数据,再批量写(INSERT IGNORE,重复时保留旧值) */
    private long[] flush(Map<String, AiPredictInput> acc) {
        if (acc == null || acc.isEmpty()) {
            return new long[]{0, 0};
        }
        List<AiPredictInput> list = new ArrayList<>(acc.values());
        // 求均值
        for (AiPredictInput ai : list) {
            int cnt = ai.getSampleCnt() == null ? 0 : ai.getSampleCnt();
            if (cnt > 0) {
                ai.setLampblackAvg(div(ai.getLampblackAvg(), cnt));
                ai.setMatterAvg(div(ai.getMatterAvg(), cnt));
                ai.setNmhcAvg(div(ai.getNmhcAvg(), cnt));
            }
        }
        int batch = 0;
        for (int i = 0; i < list.size(); i += 500) {
            List<AiPredictInput> sub = list.subList(i, Math.min(i + 500, list.size()));
            aiBizMapper.batchInsertInput(sub);
            batch++;
        }
        acc.clear();
        return new long[]{list.size(), batch};
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

    /** Map取值: 兼容驼峰/下划线两种键名 */
    private Object get(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private String getStr(Map<String, Object> m, String... keys) {
        Object v = get(m, keys);
        return v == null ? null : String.valueOf(v);
    }

    private Date getDate(Map<String, Object> m, String... keys) {
        Object v = get(m, keys);
        return toDate(v);
    }

    private Date toDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Date) {
            return (Date) v;
        }
        // MySQL 8 驱动可能直接返回 java.time 类型
        if (v instanceof java.time.LocalDateTime) {
            return Date.from(((java.time.LocalDateTime) v).atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
        if (v instanceof java.time.LocalDate) {
            return Date.from(((java.time.LocalDate) v).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.length() <= 10) {
                return new SimpleDateFormat("yyyy-MM-dd").parse(s);
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal div(BigDecimal v, int n) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v.divide(BigDecimal.valueOf(n), 4, BigDecimal.ROUND_HALF_UP);
    }
}
