package org.jeecg.modules.psms.aipredict.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jeecg.modules.psms.aipredict.entity.AiPredictConfig;
import org.jeecg.modules.psms.aipredict.mapper.AiPredictConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 预测参数配置(管理员可调,保存后热生效)
 */
@Service
public class AiConfigService {

    @Autowired
    private AiPredictConfigMapper configMapper;
    @Autowired
    private AiConfigHolder configHolder;

    /** 配置分组顺序 */
    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();

    static {
        GROUP_NAMES.put("over", "超标判定(GB18483)");
        GROUP_NAMES.put("import", "历史导入与清洗");
        GROUP_NAMES.put("forecast", "趋势预测窗口");
        GROUP_NAMES.put("risk", "风险分级");
        GROUP_NAMES.put("anomaly", "异常识别");
        GROUP_NAMES.put("llm", "大模型归因");
        GROUP_NAMES.put("eval", "准确率评估");
    }

    /** 按分组返回配置,供配置页展示 */
    public JSONArray listGrouped() {
        List<AiPredictConfig> list = configMapper.selectList(
                new QueryWrapper<AiPredictConfig>().orderByAsc("cfg_group", "sort_no"));
        Map<String, JSONArray> byGroup = new LinkedHashMap<>();
        for (Map.Entry<String, String> g : GROUP_NAMES.entrySet()) {
            byGroup.put(g.getKey(), new JSONArray());
        }
        for (AiPredictConfig c : list) {
            String g = c.getCfgGroup() == null ? "other" : c.getCfgGroup();
            byGroup.computeIfAbsent(g, k -> new JSONArray());
            JSONObject o = new JSONObject();
            o.put("id", c.getId());
            o.put("cfgGroup", c.getCfgGroup());
            o.put("cfgKey", c.getCfgKey());
            o.put("cfgName", c.getCfgName());
            o.put("cfgValue", c.getCfgValue());
            o.put("cfgType", c.getCfgType());
            o.put("cfgRemark", c.getCfgRemark());
            o.put("sortNo", c.getSortNo());
            o.put("enabled", c.getEnabled());
            byGroup.get(g).add(o);
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String> g : GROUP_NAMES.entrySet()) {
            JSONObject grp = new JSONObject();
            grp.put("group", g.getKey());
            grp.put("groupName", g.getValue());
            grp.put("items", byGroup.get(g.getKey()));
            out.add(grp);
        }
        return out;
    }

    /** 批量保存(仅更新cfg_value/cfg_remark/enabled),保存后刷新缓存 */
    public int saveBatch(List<AiPredictConfig> items) {
        int n = 0;
        for (AiPredictConfig item : items) {
            if (item.getId() == null) {
                continue;
            }
            AiPredictConfig db = configMapper.selectById(item.getId());
            if (db == null) {
                continue;
            }
            db.setCfgValue(item.getCfgValue());
            if (item.getCfgRemark() != null) {
                db.setCfgRemark(item.getCfgRemark());
            }
            if (item.getEnabled() != null) {
                db.setEnabled(item.getEnabled());
            }
            db.setUpdateTime(new Date());
            configMapper.updateById(db);
            n++;
        }
        configHolder.reload();
        return n;
    }

    /** 恢复默认值占位(重置为初始内置值,由controller提供默认map) */
    public int resetDefaults() {
        return 0;
    }
}
