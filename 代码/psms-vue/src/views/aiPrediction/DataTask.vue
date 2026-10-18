<template>
  <div>
    <!-- 任务触发区 -->
    <a-card :bordered="false" title="预测任务中心" style="margin-bottom: 12px">
      <a-space>
        <a-button type="primary" icon="import" :loading="importLoading" @click="doImport('increment')">
          增量导入(昨天)
        </a-button>
        <a-button type="primary" icon="database" :loading="importLoading" @click="doImport('full')">
          全量重导入
        </a-button>
        <a-button type="primary" icon="line-chart" :loading="fcLoading" @click="doForecast">执行趋势预测</a-button>
        <a-button type="primary" ghost icon="history" :loading="fcLoading" @click="doBacktestForecast">回测补跑(7天前,供评估)</a-button>
        <a-button type="primary" icon="alert" :loading="anLoading" @click="doScan">异常识别(近30天)</a-button>
        <a-divider type="vertical" />
        <a-button type="danger" icon="experiment" :loading="demoLoading" @click="doDemoGenerate">生成演示数据(60天)</a-button>
        <a-button type="danger" ghost icon="delete" :loading="demoLoading" @click="doDemoClear">清理演示数据</a-button>
        <a-divider type="vertical" />
        <a-button type="primary" ghost icon="audit" :loading="evalLoading" @click="doEval">生成准确率评估报告</a-button>
      </a-space>
      <div style="margin-top: 8px; color: #999; font-size: 12px">
        链路建议: 生成演示数据 → 全量重导入(清洗对齐到中间表) → 执行趋势预测 → 异常识别;大模型未配置时自动规则降级。
      </div>
    </a-card>

    <a-row :gutter="12">
      <a-col :span="12">
        <a-card :bordered="false" title="导入任务日志">
          <a-table
            size="small"
            :columns="logColumns"
            :dataSource="logs"
            :pagination="false"
            rowKey="id"
            :loading="logLoading"
          >
            <template slot="taskStatus" slot-scope="text">
              <a-tag :color="text === 'success' ? 'green' : text === 'fail' ? 'red' : 'blue'">{{ statusName(text) }}</a-tag>
            </template>
          </a-table>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card :bordered="false" title="评估报告">
          <a-table
            size="small"
            :columns="evalColumns"
            :dataSource="evalReports"
            :pagination="false"
            rowKey="id"
            :loading="evalLoading"
          >
            <template slot="pass" slot-scope="text, record">
              <a-tag :color="record.passFlag === 1 ? 'green' : 'red'">
                {{ record.accuracy ? (record.accuracy * 100).toFixed(1) : '-' }}% {{ record.passFlag === 1 ? '达标' : '未达标' }}
              </a-tag>
            </template>
          </a-table>
        </a-card>
      </a-col>
    </a-row>

    <!-- 人工标注 -->
    <a-card :bordered="false" title="人工标注(准确率评估标注集)" style="margin-top: 12px">
      <a-space style="margin-bottom: 10px">
        <a-select style="width: 220px" v-model="labelDate" placeholder="选择预测日">
          <a-select-option v-for="d in forecastDates" :key="d" :value="d">{{ d }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="loadLabelPending" icon="search">加载待标注</a-button>
        <a-tag color="blue">{{ labelPending.length }} 行</a-tag>
      </a-space>
      <a-table
        size="small"
        :columns="labelColumns"
        :dataSource="labelPending"
        :pagination="false"
        rowKey="rowKey"
      >
        <template slot="actual" slot-scope="text, record">
          <a-radio-group :value="record.actualOver" @change="(e) => { record.actualOver = e.target.value }">
            <a-radio :value="1">超标</a-radio>
            <a-radio :value="0">未超标</a-radio>
          </a-radio-group>
          <a-button v-if="record.actualOver !== null && record.actualOver !== undefined" size="small" type="link" @click="saveOneLabel(record)">保存标注</a-button>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script>
import {
  runImport, runForecast, runAnomalyScan, demoGenerate, demoClear, runEval,
  fetchImportLogs, fetchEvalReports, fetchForecastDates, fetchLabelPending, saveLabel
} from '@/api/aipredict'

export default {
  name: 'DataTask',
  data() {
    return {
      importLoading: false,
      fcLoading: false,
      anLoading: false,
      demoLoading: false,
      evalLoading: false,
      logLoading: false,
      logs: [],
      logColumns: [
        { title: '类型', dataIndex: 'taskType', width: 90 },
        { title: '状态', dataIndex: 'taskStatus', width: 90, scopedSlots: { customRender: 'taskStatus' } },
        { title: '读取', dataIndex: 'readTotal', width: 80 },
        { title: '异常过滤', dataIndex: 'outlierTotal', width: 90 },
        { title: '写入时槽', dataIndex: 'writeTotal', width: 90 },
        { title: '来源', dataIndex: 'srcTable', ellipsis: true },
        { title: '耗时ms', dataIndex: 'costMs', width: 90 },
        { title: '信息', dataIndex: 'msg', ellipsis: true }
      ],
      evalReports: [],
      evalColumns: [
        { title: '评估日', dataIndex: 'createTime', width: 160 },
        { title: '预测日', dataIndex: 'windowStart', width: 110 },
        { title: '样本数', dataIndex: 'sampleCnt', width: 80 },
        { title: '正确数', dataIndex: 'correctCnt', width: 80 },
        { title: '准确率/达标', dataIndex: 'accuracy', scopedSlots: { customRender: 'pass' } }
      ],
      forecastDates: [],
      labelDate: undefined,
      labelPending: [],
      labelColumns: [
        { title: '测点', dataIndex: 'pointMac', width: 120 },
        { title: '预测日', dataIndex: 'targetDate', width: 110 },
        { title: '预测概率', dataIndex: 'forecastProb', width: 100 },
        { title: '预测等级', dataIndex: 'forecastLevel', width: 90 },
        { title: '实际是否超标', dataIndex: 'actualOver', scopedSlots: { customRender: 'actual' } }
      ]
    }
  },
  created() {
    this.loadLogs()
    this.loadEval()
    this.loadForecastDates()
    this.loadLabelPending()
  },
  methods: {
    statusName(s) {
      return { success: '成功', fail: '失败', running: '执行中' }[s] || s
    },
    doImport(mode) {
      this.importLoading = true
      runImport({ mode }).then((res) => {
        if (res.success) {
          this.$message.success('导入完成: 读取' + res.result.readTotal + ' 写入' + res.result.writeTotal)
          this.loadLogs()
        } else this.$message.error(res.message)
      }).finally(() => { this.importLoading = false })
    },
    doForecast() {
      this.fcLoading = true
      runForecast({}).then((res) => {
        if (res.success) {
          this.$message.success('预测完成,写入 ' + res.result.inserted + ' 条')
        } else this.$message.error(res.message)
      }).finally(() => { this.fcLoading = false })
    },
    doBacktestForecast() {
      // 以7天前为预测日回测一次,使目标日已到期,便于『准确率评估』产生真实结果
      const d = new Date()
      d.setDate(d.getDate() - 7)
      const pad = (x) => (x < 10 ? '0' + x : '' + x)
      const fd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
      this.fcLoading = true
      runForecast({ forecastDate: fd }).then((res) => {
        if (res.success) {
          this.$message.success('回测预测完成(预测日' + fd + '),写入 ' + res.result.inserted + ' 条')
        } else this.$message.error(res.message)
      }).finally(() => { this.fcLoading = false })
    },
    doScan() {
      this.anLoading = true
      runAnomalyScan({}).then((res) => {
        if (res.success) {
          this.$message.success('扫描完成,新增异常 ' + res.result.created + ' 条')
        } else this.$message.error(res.message)
      }).finally(() => { this.anLoading = false })
    },
    doDemoGenerate() {
      const that = this
      this.$confirm({
        title: '生成演示数据',
        content: '将写入演示企业与近60天油烟/净化器模拟数据(打标aipredict-demo,可清理)。确认生成?',
        onOk() {
          that.demoLoading = true
          return demoGenerate({ days: 60 }).then((res) => {
            if (res.success) {
              that.$message.success('演示数据生成完成:' + res.result.rawRows + ' 行')
            } else {
              that.$message.error(res.message)
              return Promise.reject(res.message)
            }
          }).finally(() => { that.demoLoading = false })
        }
      })
    },
    doDemoClear() {
      const that = this
      this.$confirm({
        title: '清理演示数据',
        content: '将删除演示企业与数据,并清空AI预测结果表。确认?',
        onOk() {
          that.demoLoading = true
          return demoClear().then((res) => {
            if (res.success) {
              that.$message.success('已清理')
              that.loadLogs()
              that.loadEval()
            } else {
              that.$message.error(res.message)
              return Promise.reject(res.message)
            }
          }).finally(() => { that.demoLoading = false })
        }
      })
    },
    doEval() {
      this.evalLoading = true
      runEval({}).then((res) => {
        if (res.success) {
          this.$message.success('评估完成,样本' + res.result.sampleCnt + ' 准确率' + (res.result.accuracy * 100).toFixed(1) + '%')
          this.loadEval()
        } else this.$message.error(res.message)
      }).finally(() => { this.evalLoading = false })
    },
    loadLogs() {
      this.logLoading = true
      fetchImportLogs({ pageNo: 1, pageSize: 8 }).then((res) => {
        if (res.success) this.logs = res.result.records || []
      }).finally(() => { this.logLoading = false })
    },
    loadEval() {
      fetchEvalReports({ limit: 8 }).then((res) => {
        if (res.success) this.evalReports = res.result || []
      })
    },
    loadForecastDates() {
      fetchForecastDates().then((res) => {
        if (res.success) {
          this.forecastDates = res.result || []
          if (this.forecastDates.length > 0 && !this.labelDate) {
            this.labelDate = this.forecastDates[0]
            this.loadLabelPending()
          }
        }
      })
    },
    loadLabelPending() {
      if (!this.labelDate) {
        this.labelPending = []
        return
      }
      fetchLabelPending({ forecastDate: this.labelDate }).then((res) => {
        if (res.success) {
          const rows = (res.result && res.result.rows) || []
          this.labelPending = rows.map((r) => Object.assign({ rowKey: r.pointMac + r.targetDate }, r))
        }
      })
    },
    saveOneLabel(record) {
      if (record.actualOver === null || record.actualOver === undefined) {
        this.$message.warning('请先选择实际是否超标')
        return
      }
      saveLabel(JSON.stringify({ pointMac: record.pointMac, targetDate: record.targetDate, actualOver: record.actualOver })).then((res) => {
        if (res.success) this.$message.success(res.result || '标注成功')
        else this.$message.error(res.message)
      })
    }
  }
}
</script>
