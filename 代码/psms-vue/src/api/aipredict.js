import { getAction, postAction } from '@/api/manage'

// ============ AI预测看板 ============
// 看板总览
export const fetchOverview = (params) => getAction('/aipredict/dashboard/overview', params)
// 超标趋势折线
export const fetchTrend = (params) => getAction('/aipredict/dashboard/trend', params)
// 风险企业TOP榜
export const fetchTopRisk = (params) => getAction('/aipredict/dashboard/topRisk', params)
// 异常事件时间线
export const fetchAnomalyTimeline = (params) => getAction('/aipredict/dashboard/anomalyTimeline', params)
// 企业每日统计
export const fetchCompanyDaily = (params) => getAction('/aipredict/dashboard/companyDaily', params)

// 下拉
export const fetchPointOptions = () => getAction('/aipredict/options/points')
export const fetchCompanyOptions = () => getAction('/aipredict/options/companies')

// ============ 任务触发(GET) ============
export const runImport = (params) => getAction('/aipredict/import/run', params)
export const fetchImportLogs = (params) => getAction('/aipredict/import/logs', params)
export const runForecast = (params) => getAction('/aipredict/forecast/run', params)
export const runAnomalyScan = (params) => getAction('/aipredict/anomaly/scan', params)
export const fetchForecastDates = () => getAction('/aipredict/forecast/dates')
export const fetchForecastResultList = (params) => getAction('/aipredict/forecast/resultList', params)

// ============ 异常事件 ============
export const fetchAnomalyList = (params) => getAction('/aipredict/anomaly/list', params)
export const handleAnomaly = (params) => getAction('/aipredict/anomaly/handle', params)
export const anomalyAttribution = (params) => getAction('/aipredict/anomaly/attribution', params)

// ============ 参数配置 ============
export const fetchConfigList = () => getAction('/aipredict/config/list')
export const saveConfig = (json) => postAction('/aipredict/config/save', json)

// ============ 评估 & 标注 ============
export const runEval = (params) => getAction('/aipredict/eval/run', params)
export const fetchEvalReports = (params) => getAction('/aipredict/eval/reports', params)
export const fetchLabelPending = (params) => getAction('/aipredict/label/pending', params)
export const saveLabel = (json) => postAction('/aipredict/label/save', json)
export const fetchLabelList = (params) => getAction('/aipredict/label/list', params)

// ============ 演示数据 ============
export const demoGenerate = (params) => getAction('/aipredict/demo/generate', params)
export const demoClear = () => getAction('/aipredict/demo/clear', {})
