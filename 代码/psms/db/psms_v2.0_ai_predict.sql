-- =====================================================================
-- 餐饮业油烟监控平台 V2.0 - A方向「AI预测分析」增量数据库脚本
-- 适用环境 : MySQL 8.x / 数据库 psms（在 psms.sql(V1.0) 基础上执行）
-- 字符集   : utf8mb3(utf8)，与 V1.0 业务表(base_*/bu_*)保持一致，避免联表字符集冲突
-- 说明     : 脚本可重复执行(幂等,以 IF NOT EXISTS / 主键唯一约束保护)
-- 内容     : 1. AI预测 8 张业务表
--            2. 预测参数初始数据(默认对齐 GB 18483-2001)
--            3. 平台菜单(sys_permission)与管理员角色授权(sys_role_permission)
-- 日期     : 2025-06  V2.0
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
-- 1. ai_predict_config  预测参数配置表(管理员可调)
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_predict_config` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `cfg_group` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '参数分组(over/import/forecast/anomaly/llm/eval)',
  `cfg_key` varchar(100) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '参数键',
  `cfg_name` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '参数名称',
  `cfg_value` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '参数值',
  `cfg_type` varchar(20) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT 'string' COMMENT '值类型 int/decimal/string/bool',
  `cfg_remark` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '参数说明',
  `sort_no` int NULL DEFAULT 0 COMMENT '排序',
  `enabled` tinyint(1) NULL DEFAULT 1 COMMENT '是否启用',
  `create_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_cfg_key`(`cfg_key` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-参数配置' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 2. ai_import_log  历史数据导入任务执行日志
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_import_log` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `task_type` varchar(20) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '任务类型 full-全量/increment-增量',
  `task_status` varchar(20) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '状态 running/success/fail',
  `src_table` varchar(100) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '来源表',
  `read_total` bigint NULL DEFAULT 0 COMMENT '读取原始行数',
  `outlier_total` bigint NULL DEFAULT 0 COMMENT '异常过滤行数',
  `write_total` bigint NULL DEFAULT 0 COMMENT '写入中间表行数',
  `start_time` datetime NULL DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `cost_ms` bigint NULL DEFAULT NULL COMMENT '耗时毫秒',
  `msg` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '执行信息/错误信息',
  `create_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_import_log_ctime`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-历史数据导入日志' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 3. ai_predict_input  预测输入中间表(清洗/时间对齐后)
--    对齐粒度默认5分钟:同 point_mac + 同一对齐时槽聚合为一行
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_predict_input` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `point_mac` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '测点(point_mac,与base_point.point_mac关联)',
  `company_id` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '所属企业id',
  `data_time` datetime NOT NULL COMMENT '对齐后时槽开始时间',
  `minute_no` int NULL DEFAULT NULL COMMENT '时槽内分钟序号(0/5/10...)',
  `sample_cnt` int NULL DEFAULT 0 COMMENT '时槽内样本数',
  `lampblack_avg` decimal(20,4) NULL DEFAULT NULL COMMENT '油烟浓度均值(mg/m3)',
  `lampblack_max` decimal(20,4) NULL DEFAULT NULL COMMENT '油烟浓度最大值',
  `matter_avg` decimal(20,4) NULL DEFAULT NULL COMMENT '颗粒物均值',
  `nmhc_avg` decimal(20,4) NULL DEFAULT NULL COMMENT '非甲烷总烃均值',
  `fan_on_cnt` int NULL DEFAULT 0 COMMENT '风机开启样本数',
  `purifier_on_cnt` int NULL DEFAULT 0 COMMENT '净化器运行样本数',
  `purifier_power_zero_cnt` int NULL DEFAULT 0 COMMENT '净化器功率为0样本数',
  `off_over_cnt` int NULL DEFAULT 0 COMMENT '功率0且浓度超阈值样本数(复合异常判定)',
  `over_cnt` int NULL DEFAULT 0 COMMENT '超阈值样本数(按GB18483油烟2.0mg/m3等)',
  `over_flag` tinyint(1) NULL DEFAULT 0 COMMENT '该时槽是否判定超标',
  `source_table` varchar(100) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '来源表',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_point_slot`(`point_mac` ASC, `data_time` ASC) USING BTREE,
  INDEX `idx_input_company`(`company_id` ASC) USING BTREE,
  INDEX `idx_input_time`(`data_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-预测输入中间表(清洗对齐)' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 4. ai_forecast_result  超标趋势预测结果(每日:每测点未来7天)
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_forecast_result` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `point_mac` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '测点',
  `company_id` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '企业id',
  `forecast_date` date NOT NULL COMMENT '预测生成日期(每日任务执行日)',
  `target_date` date NOT NULL COMMENT '预测目标日期',
  `exceed_prob` decimal(8,4) NULL DEFAULT NULL COMMENT '当日超标概率0~1',
  `risk_level` varchar(10) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '风险等级 low/mid/high/veryhigh',
  `method` varchar(30) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT 'RULE' COMMENT '方法: RULE规则基线',
  `data_sufficient` tinyint(1) NULL DEFAULT 1 COMMENT '数据是否充分(不足时降级)',
  `remark` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '备注(贡献因子/降级原因)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_forecast`(`point_mac` ASC, `forecast_date` ASC, `target_date` ASC) USING BTREE,
  INDEX `idx_forecast_fdate`(`forecast_date` ASC) USING BTREE,
  INDEX `idx_forecast_company`(`company_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-超标趋势预测结果' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 5. ai_company_risk  风险企业榜单(每日按企业聚合,含TOP5排行字段)
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_company_risk` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `company_id` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '企业id',
  `company_name` varchar(300) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '企业名称',
  `forecast_date` date NOT NULL COMMENT '榜单日期(预测生成日)',
  `risk_score` decimal(8,4) NULL DEFAULT NULL COMMENT '企业风险分(按企业测点未来7天最大超标概率聚合)',
  `risk_level` varchar(10) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '风险等级 low/mid/high/veryhigh',
  `point_cnt` int NULL DEFAULT 0 COMMENT '参与评估测点数',
  `rank_no` int NULL DEFAULT NULL COMMENT '当日前N排名',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_company_risk`(`company_id` ASC, `forecast_date` ASC) USING BTREE,
  INDEX `idx_cr_date`(`forecast_date` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-风险企业榜单' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 6. ai_anomaly_record  异常识别预警记录
--    模式: 净化器功率为0且油烟浓度超阈值持续>=30分钟 等
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_anomaly_record` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `anomaly_code` varchar(40) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '模式编码 PURIFIER_OFF_OVER/CONTINUOUS_OVER/...',
  `anomaly_name` varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '模式名称',
  `point_mac` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '测点',
  `company_id` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '企业id',
  `company_name` varchar(300) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '企业名称',
  `start_time` datetime NULL DEFAULT NULL COMMENT '异常开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '异常结束时间',
  `duration_min` int NULL DEFAULT NULL COMMENT '持续分钟',
  `max_lampblack` decimal(20,4) NULL DEFAULT NULL COMMENT '期间油烟浓度峰值',
  `avg_lampblack` decimal(20,4) NULL DEFAULT NULL COMMENT '期间油烟浓度均值',
  `threshold_used` decimal(20,4) NULL DEFAULT NULL COMMENT '使用的判定阈值',
  `handle_status` tinyint(1) NULL DEFAULT 0 COMMENT '处理状态 0-待处理 1-已确认 2-已办结 3-误报',
  `attribution_status` tinyint(1) NULL DEFAULT 0 COMMENT '归因状态 0-未归因 1-LLM成功 2-规则降级 3-失败',
  `attribution_json` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '大模型归因结构化JSON',
  `description` varchar(500) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '描述',
  `create_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_anomaly`(`point_mac` ASC, `anomaly_code` ASC, `start_time` ASC) USING BTREE,
  INDEX `idx_anomaly_time`(`start_time` ASC) USING BTREE,
  INDEX `idx_anomaly_status`(`handle_status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-异常识别预警记录' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 7. ai_label_record  人工标注集(预测准确率评估用)
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_label_record` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `point_mac` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '测点',
  `company_id` varchar(64) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '企业id',
  `target_date` date NOT NULL COMMENT '被预测目标日',
  `forecast_prob` decimal(8,4) NULL DEFAULT NULL COMMENT '当时预测概率',
  `forecast_level` varchar(10) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '当时预测等级',
  `actual_over` tinyint(1) NULL DEFAULT NULL COMMENT '人工标注实际是否超标 1是0否',
  `label_source` varchar(20) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT 'manual' COMMENT 'manual人工/auto自动回填',
  `label_by` varchar(50) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL DEFAULT NULL COMMENT '标注人',
  `label_time` datetime NULL DEFAULT NULL COMMENT '标注时间',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniq_label`(`point_mac` ASC, `target_date` ASC) USING BTREE,
  INDEX `idx_label_date`(`target_date` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-人工标注集' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 8. ai_eval_report  预测准确率自动评估报告
-- =====================================================================
CREATE TABLE IF NOT EXISTS `ai_eval_report` (
  `id` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT NULL COMMENT '主键',
  `eval_date` date NOT NULL COMMENT '评估生成日',
  `window_start` date NULL DEFAULT NULL COMMENT '评估窗口开始(预测生成日)',
  `window_end` date NULL DEFAULT NULL COMMENT '评估窗口结束',
  `sample_cnt` int NULL DEFAULT 0 COMMENT '样本数',
  `correct_cnt` int NULL DEFAULT 0 COMMENT '预测正确数',
  `accuracy` decimal(8,4) NULL DEFAULT NULL COMMENT '准确率0~1',
  `target_acc` decimal(8,4) NULL DEFAULT 0.8 COMMENT '目标准确率',
  `pass_flag` tinyint(1) NULL DEFAULT 0 COMMENT '是否达标(>=目标)',
  `report_json` text CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NULL COMMENT '明细(混淆矩阵等JSON)',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_eval_date`(`eval_date` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb3 COLLATE = utf8mb3_general_ci COMMENT = 'AI预测-准确率评估报告' ROW_FORMAT = DYNAMIC;

-- =====================================================================
-- 2. 预测参数初始数据(默认对齐 GB 18483-2001 饮食业油烟排放标准)
--    GB 18483-2001 表2: 最高允许排放浓度 油烟 2.0 mg/m3
--    (颗粒物/非甲烷总烃字段沿用平台采集列,阈值默认参考常见地方标准,可配)
-- =====================================================================
INSERT INTO `ai_predict_config` (`id`,`cfg_group`,`cfg_key`,`cfg_name`,`cfg_value`,`cfg_type`,`cfg_remark`,`sort_no`,`enabled`,`create_time`) VALUES
('aipcfg0000000000000000000000001','over','over.threshold.lampblack','油烟浓度超标阈值(mg/m3)','2.0','decimal','GB 18483-2001 最高允许排放浓度 2.0mg/m3',1,1,NOW()),
('aipcfg0000000000000000000000002','over','over.threshold.matter','颗粒物浓度超标阈值(mg/m3)','5.0','decimal','平台采集列判定阈值(可调)',2,1,NOW()),
('aipcfg0000000000000000000000003','over','over.threshold.nmhc','非甲烷总烃超标阈值(mg/m3)','10.0','decimal','平台采集列判定阈值(可调)',3,1,NOW()),
('aipcfg0000000000000000000000004','over','over.judgeAvgFirst','优先用10分钟均值判超标','1','bool','1-用lampblack_avgdata判定,0-用瞬时值',4,1,NOW()),
('aipcfg0000000000000000000000005','over','over.continuousMinutes','连续超阈值判定分钟数','30','int','用于持续超标类异常模式',5,1,NOW()),
('aipcfg0000000000000000000000006','import','import.batch.size','导入分页大小','2000','int','历史数据分页读取每页行数',1,1,NOW()),
('aipcfg0000000000000000000000007','import','import.align.minutes','时间对齐粒度(分钟)','5','int','历史数据时间对齐时槽分钟数',2,1,NOW()),
('aipcfg0000000000000000000000008','import','import.outlier.max','油烟浓度物理上限(mg/m3)','100','decimal','超过视为异常值过滤',3,1,NOW()),
('aipcfg0000000000000000000000009','forecast','forecast.horizon.days','预测未来天数','7','int','未来7天超标概率',1,1,NOW()),
('aipcfg0000000000000000000000010','forecast','forecast.window.days','滑窗统计窗口天数','30','int','规则基线滑动窗口(天)',2,1,NOW()),
('aipcfg0000000000000000000000011','forecast','forecast.ring.days','环比对比窗口天数','3','int','环比窗口(天)',3,1,NOW()),
('aipcfg0000000000000000000000012','forecast','forecast.min.days','最小有效历史天数','7','int','低于此天数标记数据不足并降级',4,1,NOW()),
('aipcfg0000000000000000000000013','risk','risk.level.high','高风险阈值概率','0.60','decimal','>=[high]判高风险',1,1,NOW()),
('aipcfg0000000000000000000000014','risk','risk.level.mid','中风险阈值概率','0.30','decimal','>=[mid]判中风险',2,1,NOW()),
('aipcfg0000000000000000000000015','risk','risk.level.low','低风险阈值概率','0.10','decimal','>=[low]判低风险,其余无风险',3,1,NOW()),
('aipcfg0000000000000000000000016','anomaly','anomaly.scan.window.hours','异常扫描回看小时数','2','int','定时异常扫描最近N小时',1,1,NOW()),
('aipcfg0000000000000000000000017','anomaly','anomaly.min.duration','异常最小持续分钟','30','int','净化器停机超标等模式最短持续(分钟)',2,1,NOW()),
('aipcfg0000000000000000000000018','llm','llm.enabled','大模型归因开关','0','bool','0-关闭(规则降级) 1-开启',1,1,NOW()),
('aipcfg0000000000000000000000019','llm','llm.base.url','大模型API地址','','string','OpenAI兼容 /v1/chat/completions 根地址',2,1,NOW()),
('aipcfg0000000000000000000000020','llm','llm.api.key','大模型API Key','','string','留空时自动规则降级',3,1,NOW()),
('aipcfg0000000000000000000000021','llm','llm.model','大模型模型名','gpt-4o-mini','string','模型名',4,1,NOW()),
('aipcfg0000000000000000000000022','llm','llm.timeout.ms','大模型超时毫秒','15000','int','HTTP超时',5,1,NOW()),
('aipcfg0000000000000000000000023','eval','eval.target.accuracy','评估目标准确率','0.80','decimal','达标线>=80%',1,1,NOW());

-- =====================================================================
-- 3. 菜单注入: AI预测分析(一级) + 4个页面
--    菜单id固定字符串,保证幂等
-- =====================================================================
INSERT INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_route`,`is_leaf`,`keep_alive`,`hidden`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`)
SELECT * FROM (SELECT 'aipmenu000000000000000000000001' id, '' parent_id, 'AI预测分析' name, '/aiPredict' url, 'layouts/RouteView' component, NULL component_name, NULL redirect, 0 menu_type, NULL perms, '1' perms_type, 5.00 sort_no, 1 always_show, 'fund' icon, 1 is_route, 0 is_leaf, 0 keep_alive, 0 hidden, 'AI预测分析(超标趋势预测/风险榜单/异常识别)' description, 'admin' create_by, NOW() create_time, 'admin' update_by, NOW() update_time, 0 del_flag, 0 rule_flag, '1' status, 0 internal_or_external) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id='aipmenu000000000000000000000001');

INSERT INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_route`,`is_leaf`,`keep_alive`,`hidden`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`)
SELECT * FROM (SELECT 'aipmenu000000000000000000000002' id, 'aipmenu000000000000000000000001' parent_id, '预测看板' name, '/aiPredict/PredictBoard' url, 'aiPrediction/PredictBoard' component, NULL component_name, NULL redirect, 1 menu_type, NULL perms, '1' perms_type, 1.00 sort_no, 0 always_show, 'fund' icon, 1 is_route, 1 is_leaf, 0 keep_alive, 0 hidden, '趋势折线图/风险企业TOP5/异常事件时间线' description, 'admin' create_by, NOW() create_time, 'admin' update_by, NOW() update_time, 0 del_flag, 0 rule_flag, '1' status, 0 internal_or_external) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id='aipmenu000000000000000000000002');

INSERT INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_route`,`is_leaf`,`keep_alive`,`hidden`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`)
SELECT * FROM (SELECT 'aipmenu000000000000000000000003' id, 'aipmenu000000000000000000000001' parent_id, '异常事件' name, '/aiPredict/Anomaly' url, 'aiPrediction/AnomalyRecord' component, NULL component_name, NULL redirect, 1 menu_type, NULL perms, '1' perms_type, 2.00 sort_no, 0 always_show, 'alert' icon, 1 is_route, 1 is_leaf, 0 keep_alive, 0 hidden, '异常识别预警记录与LLM归因' description, 'admin' create_by, NOW() create_time, 'admin' update_by, NOW() update_time, 0 del_flag, 0 rule_flag, '1' status, 0 internal_or_external) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id='aipmenu000000000000000000000003');

INSERT INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_route`,`is_leaf`,`keep_alive`,`hidden`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`)
SELECT * FROM (SELECT 'aipmenu000000000000000000000004' id, 'aipmenu000000000000000000000001' parent_id, '预测参数配置' name, '/aiPredict/Config' url, 'aiPrediction/ParamConfig' component, NULL component_name, NULL redirect, 1 menu_type, NULL perms, '1' perms_type, 3.00 sort_no, 0 always_show, 'setting' icon, 1 is_route, 1 is_leaf, 0 keep_alive, 0 hidden, '阈值/窗口/大模型等参数管理' description, 'admin' create_by, NOW() create_time, 'admin' update_by, NOW() update_time, 0 del_flag, 0 rule_flag, '1' status, 0 internal_or_external) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id='aipmenu000000000000000000000004');

INSERT INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_route`,`is_leaf`,`keep_alive`,`hidden`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`)
SELECT * FROM (SELECT 'aipmenu000000000000000000000005' id, 'aipmenu000000000000000000000001' parent_id, '预测任务与评估' name, '/aiPredict/Task' url, 'aiPrediction/DataTask' component, NULL component_name, NULL redirect, 1 menu_type, NULL perms, '1' perms_type, 4.00 sort_no, 0 always_show, 'database' icon, 1 is_route, 1 is_leaf, 0 keep_alive, 0 hidden, '历史导入/预测执行/演示数据/准确率评估' description, 'admin' create_by, NOW() create_time, 'admin' update_by, NOW() update_time, 0 del_flag, 0 rule_flag, '1' status, 0 internal_or_external) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id='aipmenu000000000000000000000005');

-- 管理员角色(与V1.0 psms.sql一致 f6817f48af4fb3af11b9e8bf182f618b)绑定新菜单
INSERT INTO `sys_role_permission` (`id`,`role_id`,`permission_id`)
SELECT * FROM (
  SELECT 'aiprp00000000000000000000000001' id, 'f6817f48af4fb3af11b9e8bf182f618b' role_id, 'aipmenu000000000000000000000001' permission_id UNION ALL
  SELECT 'aiprp00000000000000000000000002', 'f6817f48af4fb3af11b9e8bf182f618b', 'aipmenu000000000000000000000002' UNION ALL
  SELECT 'aiprp00000000000000000000000003', 'f6817f48af4fb3af11b9e8bf182f618b', 'aipmenu000000000000000000000003' UNION ALL
  SELECT 'aiprp00000000000000000000000004', 'f6817f48af4fb3af11b9e8bf182f618b', 'aipmenu000000000000000000000004' UNION ALL
  SELECT 'aiprp00000000000000000000000005', 'f6817f48af4fb3af11b9e8bf182f618b', 'aipmenu000000000000000000000005'
) t WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id='f6817f48af4fb3af11b9e8bf182f618b' AND rp.permission_id=t.permission_id);

SET FOREIGN_KEY_CHECKS = 1;
