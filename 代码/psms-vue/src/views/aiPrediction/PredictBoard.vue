<template>
  <div>
    <a-row :gutter="12" style="margin-bottom: 12px">
      <a-col :span="4" v-for="card in statCards" :key="card.title">
        <a-card :bordered="false" class="stat-card">
          <div class="stat-title">{{ card.title }}</div>
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="12">
      <a-col :span="16">
        <a-card :bordered="false" title="超标趋势预测(历史实际 vs 未来7天概率)">
          <template slot="extra">
            <a-select
              style="width: 220px"
              placeholder="选择监测点"
              allowClear
              v-model="trendPoint"
              @change="loadTrend"
            >
              <a-select-option v-for="p in pointOptions" :key="p.pointMac" :value="p.pointMac">
                {{ p.pointName || p.pointMac }}({{ p.companyName || '-' }})
              </a-select-option>
            </a-select>
          </template>
          <div id="trendChart" style="height: 340px"></div>
        </a-card>
      </a-col>
      <a-col :span="8">
        <a-card :bordered="false" title="风险企业TOP5">
          <a-table
            size="small"
            :columns="riskColumns"
            :dataSource="riskList"
            :pagination="false"
            rowKey="companyId"
          >
            <template slot="rank" slot-scope="text, record, index">
              <span class="rank-no">{{ index + 1 }}</span>
            </template>
            <template slot="riskLevel" slot-scope="text">
              <a-tag :color="levelColor(text)">{{ levelName(text) }}</a-tag>
            </template>
          </a-table>
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="12" style="margin-top: 12px">
      <a-col :span="24">
        <a-card :bordered="false" title="异常事件时间线(最近14天)">
          <a-timeline v-if="timeline.length > 0">
            <a-timeline-item
              v-for="ev in timeline"
              :key="ev.id"
              :color="timelineColor(ev.anomalyCode)"
            >
              <div class="tl-row">
                <span class="tl-time">{{ ev.startTime }}</span>
                <a-tag :color="levelColor('high')">{{ ev.anomalyName }}</a-tag>
                <span class="tl-mac">{{ ev.pointMac }}</span>
                <span class="tl-company">{{ ev.companyName || '' }}</span>
                <span class="tl-desc">{{ ev.description }}</span>
                <a-tag v-if="ev.handleStatus === 0" color="orange">待处理</a-tag>
                <a-tag v-else-if="ev.handleStatus === 1" color="green">已确认</a-tag>
                <a-tag v-else-if="ev.handleStatus === 2" color="blue">已办结</a-tag>
                <a-tag v-else color="default">误报</a-tag>
              </div>
            </a-timeline-item>
          </a-timeline>
          <a-empty v-else description="暂无异常事件,请先在『预测任务与评估』中生成/扫描" />
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { fetchOverview, fetchTrend, fetchTopRisk, fetchAnomalyTimeline, fetchPointOptions } from '@/api/aipredict'

export default {
  name: 'PredictBoard',
  data() {
    return {
      statCards: [
        { title: '接入企业数', value: 0, color: '#1890ff' },
        { title: '监测点总数', value: 0, color: '#1890ff' },
        { title: '高风险企业', value: 0, color: '#f5222d' },
        { title: '中风险企业', value: 0, color: '#fa8c16' },
        { title: '待处理异常', value: 0, color: '#f5222d' },
        { title: '最新预测日', value: '-', color: '#52c41a' }
      ],
      pointOptions: [],
      trendPoint: undefined,
      riskList: [],
      timeline: [],
      riskColumns: [
        { title: '排名', dataIndex: 'rank', width: 60, align: 'center', scopedSlots: { customRender: 'rank' } },
        { title: '企业名称', dataIndex: 'companyName', ellipsis: true },
        { title: '风险分', dataIndex: 'riskScore', width: 80, align: 'center' },
        { title: '等级', dataIndex: 'riskLevel', width: 80, align: 'center', scopedSlots: { customRender: 'riskLevel' } }
      ],
      chart: null
    }
  },
  created() {
    this.loadAll()
  },
  beforeDestroy() {
    if (this.chart) {
      this.chart.dispose()
      this.chart = null
    }
  },
  methods: {
    async loadAll() {
      await this.loadOverview()
      this.loadPointOptions()
      this.loadTrend()
      this.loadTopRisk()
      this.loadTimeline()
    },
    async loadOverview() {
      const res = await fetchOverview()
      if (res && res.success) {
        const d = res.result || {}
        const risk = d.riskStat || {}
        this.statCards = [
          { title: '接入企业数', value: d.companyTotal || 0, color: '#1890ff' },
          { title: '监测点总数', value: d.pointTotal || 0, color: '#1890ff' },
          { title: '高风险企业', value: risk.high || 0, color: '#f5222d' },
          { title: '中风险企业', value: risk.mid || 0, color: '#fa8c16' },
          { title: '待处理异常', value: d.pendingAnomaly || 0, color: '#f5222d' },
          { title: '最新预测日', value: d.lastForecastDay || '-', color: '#52c41a' }
        ]
      }
    },
    async loadPointOptions() {
      const res = await fetchPointOptions()
      if (res && res.success) {
        this.pointOptions = res.result || []
      }
    },
    async loadTrend() {
      const res = await fetchTrend({ pointMac: this.trendPoint, backDays: 14 })
      if (!res || !res.success) return
      const d = res.result || {}
      this.$nextTick(() => {
        if (!document.getElementById('trendChart')) return
        if (this.chart) this.chart.dispose()
        this.chart = echarts.init(document.getElementById('trendChart'))
        this.chart.setOption({
          tooltip: { trigger: 'axis' },
          legend: { data: ['实际超标率', '预测超标概率'] },
          grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
          xAxis: { type: 'category', data: d.x || [] },
          yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
          series: [
            {
              name: '实际超标率',
              type: 'line',
              smooth: true,
              symbolSize: 6,
              data: (d.actual || []).map(v => (v === null || v === undefined ? null : (v * 100).toFixed(1)))
            },
            {
              name: '预测超标概率',
              type: 'line',
              smooth: true,
              symbolSize: 8,
              lineStyle: { type: 'dashed', width: 2 },
              itemStyle: { color: '#f5222d' },
              data: (d.forecast || []).map(v => (v === null || v === undefined ? null : (v * 100).toFixed(1)))
            }
          ]
        })
      })
    },
    async loadTopRisk() {
      const res = await fetchTopRisk({ top: 5 })
      if (res && res.success) {
        this.riskList = res.result || []
      }
    },
    async loadTimeline() {
      const res = await fetchAnomalyTimeline({ days: 14 })
      if (res && res.success) {
        this.timeline = res.result || []
      }
    },
    levelName(level) {
      return { high: '高风险', mid: '中风险', low: '低风险', verylow: '极低' }[level] || level
    },
    levelColor(level) {
      return { high: 'red', mid: 'orange', low: 'blue', verylow: 'green' }[level] || 'default'
    },
    timelineColor(code) {
      return code === 'PURIFIER_OFF_OVER' ? 'red' : 'orange'
    }
  }
}
</script>

<style lang="less" scoped>
.stat-card {
  text-align: center;
  border-radius: 8px;
  .stat-title {
    color: #666;
    font-size: 13px;
  }
  .stat-value {
    font-size: 24px;
    font-weight: 600;
    margin-top: 6px;
  }
}
.rank-no {
  display: inline-block;
  width: 22px;
  height: 22px;
  line-height: 22px;
  border-radius: 50%;
  background: #1890ff;
  color: #fff;
  text-align: center;
}
.tl-row {
  font-size: 13px;
  .tl-time {
    color: #999;
    margin-right: 8px;
  }
  .tl-mac {
    margin: 0 8px;
    font-weight: 600;
  }
  .tl-company {
    color: #1890ff;
    margin-right: 8px;
  }
  .tl-desc {
    color: #333;
    margin: 0 8px;
  }
}
</style>
