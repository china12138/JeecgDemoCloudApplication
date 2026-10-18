package org.jeecg.modules.psms.aipredict.task;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.psms.aipredict.service.AiAnomalyService;
import org.jeecg.modules.psms.aipredict.service.AiForecastService;
import org.jeecg.modules.psms.aipredict.service.AiImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * AI预测每日定时任务:
 *  01:20 历史数据增量导入(清洗→中间表)
 *  02:30 超标趋势预测(未来7天概率/风险等级/企业榜单)
 *  03:00 异常识别回扫最近一天并生成预警记录(模式:净化器停机超标等)
 * 大模型不可用时自动降级,不影响主链路;任务本身纯规则可运行
 */
@Slf4j
@Component
public class AiPredictScheduledTask {

    @Autowired
    private AiImportService aiImportService;
    @Autowired
    private AiForecastService aiForecastService;
    @Autowired
    private AiAnomalyService aiAnomalyService;

    /** 每日01:20 增量导入(截止昨天) */
    @Scheduled(cron = "0 20 1 * * ?")
    public void dailyImport() {
        log.info("AI预测-定时增量导入 start");
        try {
            aiImportService.importHistory("increment", null);
        } catch (Exception e) {
            log.error("AI预测-定时增量导入失败", e);
        }
        log.info("AI预测-定时增量导入 end");
    }

    /** 每日02:30 超标趋势预测(基于中间表) */
    @Scheduled(cron = "0 30 2 * * ?")
    public void dailyForecast() {
        log.info("AI预测-定时趋势预测 start");
        try {
            aiForecastService.runDailyForecast(null);
        } catch (Exception e) {
            log.error("AI预测-定时趋势预测失败", e);
        }
        log.info("AI预测-定时趋势预测 end");
    }

    /** 每日03:10 异常识别: 回扫近2天(覆盖刚导入的数据),防重复由唯一约束保证 */
    @Scheduled(cron = "0 10 3 * * ?")
    public void dailyAnomalyScan() {
        log.info("AI预测-定时异常识别 start");
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_MONTH, -2);
            aiAnomalyService.scanRange(df.format(c.getTime()), df.format(new Date()));
        } catch (Exception e) {
            log.error("AI预测-定时异常识别失败", e);
        }
        log.info("AI预测-定时异常识别 end");
    }
}
