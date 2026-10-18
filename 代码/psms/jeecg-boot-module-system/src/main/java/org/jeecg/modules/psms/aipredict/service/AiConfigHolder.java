package org.jeecg.modules.psms.aipredict.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.psms.aipredict.entity.AiPredictConfig;
import org.jeecg.modules.psms.aipredict.mapper.AiPredictConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI预测-参数读取(带默认值,支持运行期热更新)
 */
@Slf4j
@Service
public class AiConfigHolder {

    @Autowired
    private AiPredictConfigMapper configMapper;

    /** 参数缓存 key=cfg_key value=cfg_value */
    private final Map<String, String> cfgCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从数据库重载(参数配置页保存后调用) */
    public void reload() {
        cfgCache.clear();
        List<AiPredictConfig> list = configMapper.selectList(new QueryWrapper<AiPredictConfig>().eq("enabled", 1));
        for (AiPredictConfig c : list) {
            if (StringUtils.isNotBlank(c.getCfgKey())) {
                cfgCache.put(c.getCfgKey().trim(), c.getCfgValue() == null ? "" : c.getCfgValue().trim());
            }
        }
        log.info("AI预测参数加载完成,共 {} 项", cfgCache.size());
    }

    public String getStr(String key, String def) {
        String v = cfgCache.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        String v = cfgCache.get(key);
        if (StringUtils.isBlank(v)) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal def) {
        String v = cfgCache.get(key);
        if (StringUtils.isBlank(v)) {
            return def;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = cfgCache.get(key);
        if (StringUtils.isBlank(v)) {
            return def;
        }
        return "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim());
    }
}
