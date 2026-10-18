package org.jeecg.modules.psms.aipredict.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 大模型辅助归因(结构化JSON输出)
 *  - 使用OpenAI兼容 /chat/completions 协议
 *  - llm.enabled=false 或未配置 base.url/api.key 或调用失败 => 自动降级为规则归因(纯本地),
 *    降级也保证输出同一结构的JSON,功能始终可用
 */
@Slf4j
@Service
public class AiLlmAttributionService {

    @Autowired
    private AiConfigHolder cfg;
    @Autowired
    private RestTemplate restTemplate;

    /**
     * 对一次异常事件做归因
     *
     * @param eventContext 异常事件上下文(测点/企业/时间/持续/浓度等键值)
     * @return 结构化归因JSON文本
     */
    public String attribute(Map<String, Object> eventContext) {
        boolean enabled = cfg.getBool("llm.enabled", false);
        String baseUrl = cfg.getStr("llm.base.url", "");
        String apiKey = cfg.getStr("llm.api.key", "");
        String model = cfg.getStr("llm.model", "gpt-4o-mini");
        int timeout = cfg.getInt("llm.timeout.ms", 15000);
        if (!enabled || baseUrl.trim().isEmpty() || apiKey.trim().isEmpty()) {
            return ruleAttribution(eventContext);
        }
        try {
            String url = baseUrl.trim();
            if (!url.endsWith("/")) {
                url += "/";
            }
            url += "chat/completions";
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是餐饮油烟在线监控领域的环保数据分析专家。"
                    + "根据给出的异常事件信息做根因归因。只输出JSON,结构固定:"
                    + "{\"possibleCauses\":[{\"cause\":\"...\",\"evidence\":\"...\"}],\"suggestion\":\"...\",\"confidence\":0-1}");
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "异常事件上下文:" + JSON.toJSONString(eventContext));
            messages.add(sys);
            messages.add(user);
            body.put("messages", messages);
            body.put("temperature", 0.3);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("LLM归因非2xx响应,降级规则归因. status={}", resp.getStatusCode());
                return ruleAttribution(eventContext);
            }
            JSONObject root = JSON.parseObject(resp.getBody());
            JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String content = msg.getString("content");
            if (content == null || content.trim().isEmpty()) {
                return ruleAttribution(eventContext);
            }
            content = content.trim();
            // 剥掉 markdown 代码围栏
            if (content.startsWith("```")) {
                int firstNl = content.indexOf('\n');
                int lastFence = content.lastIndexOf("```");
                if (firstNl > 0 && lastFence > firstNl) {
                    content = content.substring(firstNl + 1, lastFence).trim();
                }
            }
            // 校验是合法JSON,否则降级
            JSON.parseObject(content);
            JSONObject out = JSON.parseObject(content);
            out.put("engine", "llm");
            out.put("raw", eventContext);
            return out.toJSONString();
        } catch (Exception e) {
            log.warn("LLM归因失败,降级规则归因: {}", e.getMessage());
            return ruleAttribution(eventContext);
        }
    }

    /** 规则降级归因: 依据规则特征给结构化解释,无外部依赖 */
    public String ruleAttribution(Map<String, Object> ctx) {
        JSONObject out = new JSONObject();
        out.put("engine", "rule");
        out.put("summary", buildSummary(ctx));
        JSONArray causes = new JSONArray();
        addCause(causes, "净化器未运行/功率为0,油烟未经净化直接排放",
                "期间净化器运行状态为停机,样本净化器功率为0占比高,实测油烟浓度超过GB18483阈值");
        if ("true".equals(String.valueOf(ctx.get("continuousOver")))) {
            addCause(causes, "油烟浓度持续超阈值,净化效率不足或超标排放",
                    "连续超阈值时段大于等于判定阈值分钟,最大浓度" + ctx.getOrDefault("maxConcentration", "-")
                            + "mg/m³,超国家标准限值");
        }
        out.put("possibleCauses", causes);
        out.put("suggestion", "建议执法/监测人员现场核查净化设施运行状况与排放口,必要时责令立即整改并复测;"
                + "若属设备故障请安排运维检修并保留运行日志,作为事后处罚与整改依据。");
        out.put("confidence", 0.7);
        out.put("raw", ctx);
        return out.toJSONString();
    }

    private String buildSummary(Map<String, Object> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("监测点").append(ctx.getOrDefault("pointMac", "-"))
                .append("(").append(ctx.getOrDefault("companyName", "")).append(")")
                .append("于").append(ctx.getOrDefault("startTime", "-")).append("至")
                .append(ctx.getOrDefault("endTime", "-"))
                .append("检测到异常: ").append(ctx.getOrDefault("anomalyName", ""));
        return sb.toString();
    }

    private void addCause(JSONArray arr, String cause, String evidence) {
        JSONObject o = new JSONObject();
        o.put("cause", cause);
        o.put("evidence", evidence);
        arr.add(o);
    }

    public Map<String, Object> defaultContext() {
        return new HashMap<>();
    }
}
