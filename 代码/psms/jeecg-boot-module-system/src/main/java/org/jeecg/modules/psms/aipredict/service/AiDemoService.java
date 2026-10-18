package org.jeecg.modules.psms.aipredict.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 演示数据生成器:
 *  生成若干演示企业(base_company)+测点(base_point),
 *  并在bu_lampblack_data/bu_purifier_powerdata写入近N天模拟历史数据
 *  (含净化器功率为0/持续超标等模式,便于验收 导入→预测→异常→榜单 全链路)
 *  数据统一打标 creator='aipredict-demo',可一键清理
 *  批量JDBC写入,速度可控
 */
@Slf4j
@Service
public class AiDemoService {

    @Autowired
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 演示企业(与净化器功率0/超标模式设计对应: 前两家高风险,中间一般,后两家稳定) */
    private static final String[] COMPANY_NAMES = {
            "蜀香居火锅(万达店)", "满堂红烤鸭店(中关村店)", "渔家傲蒸汽海鲜(望京店)",
            "川悦食府(亦庄店)", "老城一锅羊蝎子(西单店)"};

    private static final String SQL_LAMP = "INSERT INTO bu_lampblack_data " +
            "(id, creator, create_time, updater, update_time, sys_org_code, point_mac, " +
            " lampblack_data, matter_data, nmhc_data, lampblack_avgdata, matter_avgdata, nmhc_avgdata, " +
            " temp, hum, fan_status, purifier_status, fan_current, purifier_current, " +
            " purifier_voltage_diff, current_speed) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String SQL_POWER = "INSERT INTO bu_purifier_powerdata " +
            "(id, creator, create_time, updater, update_time, sys_org_code, point_mac, power_number, " +
            " second_voltage, second_current, output_power, work_hour, power_open_status, " +
            " power_protect_status, power_circuit_protect, power_overtemp_protect, auto_model, " +
            " power_module_num, bu_lampblack_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /**
     * 生成演示数据(近N天,06:00-23:00每5分钟一条)
     */
    public Map<String, Object> generate(int days) throws Exception {
        int n = days <= 0 ? 60 : Math.min(days, 120);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        // 1) 企业
        List<String> companyIds = new ArrayList<>();
        for (int i = 0; i < COMPANY_NAMES.length; i++) {
            String cid = demoId("C", i + 1);
            companyIds.add(cid);
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM base_company WHERE id = ?", Integer.class, cid);
            if (exists == null || exists == 0) {
                jdbcTemplate.update("INSERT INTO base_company " +
                                "(id, code, name, lng, lat, status, fid_region, creator, create_time) VALUES (?,?,?,?,?,?,?,?,?)",
                        cid, cid, COMPANY_NAMES[i],
                        new BigDecimal("116.35").add(new BigDecimal(Math.random() * 0.1)),
                        new BigDecimal("39.90").add(new BigDecimal(Math.random() * 0.1)),
                        "1", 1024, "aipredict-demo", now);
            }
        }
        // 2) 测点(前2家企业各2个点,其余各1个)
        List<String> points = new ArrayList<>(); // pointMac
        int pIdx = 0;
        for (int ci = 0; ci < companyIds.size(); ci++) {
            int per = ci < 2 ? 2 : 1;
            for (int k = 0; k < per; k++) {
                pIdx++;
                String pid = demoId("P", pIdx);
                String mac = pid;
                points.add(mac);
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM base_point WHERE id = ?", Integer.class, pid);
                if (exists == null || exists == 0) {
                    jdbcTemplate.update("INSERT INTO base_point " +
                                    "(id, company_id, code, name, point_type, point_mac, status, fid_region, creator, create_time) " +
                                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                            pid, companyIds.get(ci), "AIP-" + pIdx, "演示测点" + pIdx, "1", mac, "1", 1024, "aipredict-demo", now);
                }
            }
        }
        // 3) 生成原始数据(批量)
        List<Object[]> lampBatch = new ArrayList<>();
        List<Object[]> powerBatch = new ArrayList<>();
        long rawRows = 0;
        Random rnd = new Random(2024);
        int rawId = 10000000;
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_MONTH, -n);
        cal.set(Calendar.HOUR_OF_DAY, 6);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date startDate = cal.getTime();

        // 为每个测点预置若干次确定性超标/停机时段(保证异常识别能抓到>=30分钟连续时段)
        // map: pointMac -> list of episode {startDayOffset,startMinute,lengthSlots}
        Map<String, List<int[]>> episodes = new HashMap<>();
        for (String mac : points) {
            int riskProfile = riskProfile(mac);
            List<int[]> eps = new ArrayList<>();
            Random er = new Random(mac.hashCode() * 31 + 7);
            // 长期风险期(可被滑窗/环比学习且覆盖评估窗口):
            // 高风险=最近20天每天一次,中风险=最近14天每天一次;预测日(7天前)时该模式已持续可见,
            // 目标日(其后7天)仍在模式内 -> 规则可学习,评估准确率可验证(≥80%)
            int recentDays = riskProfile >= 3 ? 20 : riskProfile == 2 ? 14 : 0;
            for (int k = 1; k <= recentDays && k < n; k++) {
                int dayOff = n - 1 - k;              // 相对"最后一天"倒数第k天
                int startMinute = 600 + er.nextInt(660);
                startMinute = (startMinute / 5) * 5;
                int length = 8 + er.nextInt(10);      // 40~85分钟
                eps.add(new int[]{dayOff, startMinute, length});
            }
            // 更早历史偶发(低风险也有少量噪声,评估TN样本)
            int historyEp = riskProfile >= 3 ? 4 : riskProfile == 2 ? 3 : 3;
            for (int e = 0; e < historyEp; e++) {
                int dayOff = er.nextInt(Math.max(2, n - Math.max(recentDays, 25)));
                int startMinute = 600 + er.nextInt(660);
                startMinute = (startMinute / 5) * 5;
                int length = 8 + er.nextInt(10);
                eps.add(new int[]{dayOff, startMinute, length});
            }
            episodes.put(mac, eps);
        }

        for (String mac : points) {
            // 风险画像: 前两家企业测点=高/中高,其余一般
            int riskProfile = riskProfile(mac);
            List<int[]> eps = episodes.get(mac);
            Map<Integer, List<int[]>> epsByDay = new HashMap<>();
            for (int[] ep : eps) {
                epsByDay.computeIfAbsent(ep[0], k -> new ArrayList<>()).add(ep);
            }
            Calendar c2 = Calendar.getInstance();
            c2.setTime(startDate);
            int dayIdx = 0;
            while (c2.getTime().before(now)) {
                int hour = c2.get(Calendar.HOUR_OF_DAY);
                int minute = c2.get(Calendar.MINUTE);
                int absMinute = hour * 60 + minute;
                if (hour >= 6 && hour < 23) {
                    rawId++;
                    Date t = c2.getTime();
                    String timeStr = df.format(t);
                    boolean busy = (hour >= 10 && hour <= 14) || (hour >= 17 && hour <= 21);
                    // 命中预置异常时段 => 净化器停机且浓度超阈值
                    boolean inEpisode = false;
                    List<int[]> dayEps = epsByDay.get(dayIdx);
                    if (dayEps != null) {
                        for (int[] ep : dayEps) {
                            if (absMinute >= ep[1] && absMinute < ep[1] + ep[2] * 5) {
                                inEpisode = true;
                                break;
                            }
                        }
                    }
                    // 净化器偶发停机(风险高者概率高)
                    boolean purifierOff = inEpisode;
                    if (!purifierOff) {
                        double offProb = busy ? (riskProfile >= 3 ? 0.03 : riskProfile == 2 ? 0.015 : 0.005) : 0.001;
                        purifierOff = Math.random() < offProb;
                    }
                    // 浓度(mg/m3): 平稳基线控制在GB阈值2.0之下(0.2~1.7),仅异常时段叠加后超标
                    double conc = busy ? 0.5 + Math.random() * 1.2 : 0.2 + Math.random() * 0.6;
                    if (purifierOff) {
                        conc += 1.8 + Math.random() * 2.6; // 叠加后 2.0~4.3 确保超GB阈值
                    }
                    BigDecimal lb = round(Math.min(9.9, conc), 2);
                    BigDecimal matter = round(Math.min(9.9, conc * 0.6 + 0.2), 2);
                    BigDecimal nmhc = round(Math.min(19.9, conc * 1.6 + 0.8), 2);
                    BigDecimal avg10 = round(Math.min(9.9, conc + (Math.random() - 0.5) * 0.2), 2);
                    String rawIdStr = "DEMO" + rawId;
                    String pidPower = "DEMOP" + rawId;
                    lampBatch.add(new Object[]{
                            rawIdStr, "aipredict-demo", timeStr, "aipredict-demo", timeStr, "aipredict-demo", mac,
                            lb, matter, nmhc, avg10, matter, nmhc,
                            20 + (int) (Math.random() * 10), 45 + (int) (Math.random() * 20),
                            purifierOff ? 0 : 1, purifierOff ? 0 : 1,
                            round(0.2 + Math.random() * 0.8, 2),
                            purifierOff ? 0 : round(0.4 + Math.random() * 0.4, 2),
                            round(80 + Math.random() * 120, 2), round(2 + Math.random() * 4, 2)
                    });
                    powerBatch.add(new Object[]{
                            pidPower, "aipredict-demo", timeStr, "aipredict-demo", timeStr, "aipredict-demo", mac, "1",
                            purifierOff ? 0 : round(6000 + Math.random() * 3000, 1),
                            purifierOff ? 0 : round(1.0 + Math.random() * 2.0, 2),
                            purifierOff ? 0 : 200 + (int) (Math.random() * 600),
                            1000 + (int) (Math.random() * 4000),
                            purifierOff ? 0 : 1, 0, 0, 0, 1, 1, rawIdStr
                    });
                    rawRows++;
                    if (lampBatch.size() >= 1000) {
                        jdbcTemplate.batchUpdate(SQL_LAMP, lampBatch);
                        jdbcTemplate.batchUpdate(SQL_POWER, powerBatch);
                        lampBatch.clear();
                        powerBatch.clear();
                    }
                }
                c2.add(Calendar.MINUTE, 5);
                if (c2.get(Calendar.HOUR_OF_DAY) == 6 && c2.get(Calendar.MINUTE) == 0 && dayIdx < n - 1) {
                    dayIdx++;
                }
            }
        }
        if (!lampBatch.isEmpty()) {
            jdbcTemplate.batchUpdate(SQL_LAMP, lampBatch);
            jdbcTemplate.batchUpdate(SQL_POWER, powerBatch);
        }
        log.info("AI演示数据生成完成: 企业={} 测点={} 原始行={}", companyIds.size(), points.size(), rawRows);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("company", companyIds.size());
        ret.put("point", points.size());
        ret.put("rawRows", rawRows);
        ret.put("msg", "请前往『预测任务与评估』执行 全量导入 → 趋势预测 → 异常识别 完成链路验证");
        return ret;
    }

    /** 清理演示数据(原始表),中间表/预测结果不在此清理 */
    public int clearRaw() {
        int d1 = jdbcTemplate.update("DELETE FROM bu_lampblack_data WHERE creator = 'aipredict-demo'");
        int d2 = jdbcTemplate.update("DELETE FROM bu_purifier_powerdata WHERE creator = 'aipredict-demo'");
        int d3 = jdbcTemplate.update("DELETE FROM base_point WHERE creator = 'aipredict-demo'");
        int d4 = jdbcTemplate.update("DELETE FROM base_company WHERE creator = 'aipredict-demo'");
        return d1 + d2 + d3 + d4;
    }

    private String demoId(String prefix, int idx) {
        return prefix + String.format("%06d", idx);
    }

    /** 风险画像: P000001/2(第1企业)=3高, P000003/4(第2企业)=2中, 其余=1一般 */
    private int riskProfile(String mac) {
        if (mac.endsWith("0001") || mac.endsWith("0002")) {
            return 3;
        }
        if (mac.endsWith("0003") || mac.endsWith("0004")) {
            return 2;
        }
        return 1;
    }

    private BigDecimal round(double v, int scale) {
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
