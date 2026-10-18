<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline">
        <a-form-item label="异常模式">
          <a-select
            style="width: 220px"
            allowClear
            placeholder="全部模式"
            v-model="queryParam.code"
            @change="searchQuery"
          >
            <a-select-option value="PURIFIER_OFF_OVER">净化器功率为0且油烟超阈值</a-select-option>
            <a-select-option value="CONTINUOUS_OVER">油烟浓度持续超标</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="处理状态">
          <a-select
            style="width: 160px"
            allowClear
            placeholder="全部"
            v-model="queryParam.handleStatus"
            @change="searchQuery"
          >
            <a-select-option :value="0">待处理</a-select-option>
            <a-select-option :value="1">已确认</a-select-option>
            <a-select-option :value="2">已办结</a-select-option>
            <a-select-option :value="3">误报</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="searchQuery" icon="search">查询</a-button>
          <a-button style="margin-left: 8px" @click="searchReset" icon="redo">重置</a-button>
          <a-button style="margin-left: 8px" type="dashed" icon="sync" :loading="scanLoading" @click="scanAndLoad">
            立即扫描(近7天)
          </a-button>
        </a-form-item>
      </a-form>
    </div>

    <a-table
      ref="table"
      size="middle"
      bordered
      rowKey="id"
      :columns="columns"
      :dataSource="dataSource"
      :pagination="ipagination"
      :loading="loading"
      @change="handleTableChange"
    >
      <template slot="anomalyCode" slot-scope="text">
        <a-tag :color="text === 'PURIFIER_OFF_OVER' ? 'red' : 'orange'">
          {{ text === 'PURIFIER_OFF_OVER' ? '净化器停机超标' : '持续超标' }}
        </a-tag>
      </template>
      <template slot="handleStatus" slot-scope="text, record">
        <a-select
          :value="text"
          style="width: 110px"
          @change="(v) => changeStatus(record, v)"
        >
          <a-select-option :value="0">待处理</a-select-option>
          <a-select-option :value="1">已确认</a-select-option>
          <a-select-option :value="2">已办结</a-select-option>
          <a-select-option :value="3">误报</a-select-option>
        </a-select>
      </template>
      <template slot="attribution" slot-scope="text, record">
        <a-tag v-if="record.attributionStatus === 1" color="green">LLM归因</a-tag>
        <a-tag v-else-if="record.attributionStatus === 2" color="blue">规则降级</a-tag>
        <a-tag v-else-if="record.attributionStatus === 3" color="red">失败</a-tag>
        <a-tag v-else color="default">未归因</a-tag>
        <a style="margin-left: 6px" @click="showAttribution(record)">归因</a>
        <a v-if="record.attributionJson" style="margin-left: 8px" @click="viewAttribution(record)">查看</a>
      </template>
    </a-table>

    <!-- 归因执行弹窗 -->
    <a-modal title="异常归因" :visible="attrModal" @ok="runAttr" @cancel="attrModal = false" :confirmLoading="attrLoading">
      <p>对监测点 <b>{{ currentRecord && currentRecord.pointMac }}</b> 的「{{ currentRecord && currentRecord.anomalyName }}」事件调用大模型进行根因归因。</p>
      <p style="color: #999">若未配置大模型(或调用失败)将自动降级为本地规则归因,仍输出结构化JSON,功能可用。</p>
    </a-modal>

    <!-- 归因结果查看 -->
    <a-modal title="归因结果(结构化JSON)" :visible="viewModal" width="760px" footer="" @cancel="viewModal = false">
      <pre style="white-space: pre-wrap; background: #f6f8fa; padding: 12px; border-radius: 6px; max-height: 480px; overflow:auto">{{ viewJson }}</pre>
    </a-modal>
  </a-card>
</template>

<script>
import { JeecgListMixin } from '@/mixins/JeecgListMixin'
import { mixinDevice } from '@/utils/mixin'
import { fetchAnomalyList, handleAnomaly, anomalyAttribution, runAnomalyScan } from '@/api/aipredict'

export default {
  name: 'AnomalyRecordList',
  mixins: [JeecgListMixin, mixinDevice],
  data() {
    return {
      description: 'AI异常事件',
      url: { list: '/aipredict/anomaly/list' },
      columns: [
        { title: '异常模式', dataIndex: 'anomalyCode', width: 150, align: 'center', scopedSlots: { customRender: 'anomalyCode' } },
        { title: '监测点', dataIndex: 'pointMac', width: 120, align: 'center' },
        { title: '企业', dataIndex: 'companyName', ellipsis: true },
        { title: '开始时间', dataIndex: 'startTime', width: 160, align: 'center' },
        { title: '结束时间', dataIndex: 'endTime', width: 160, align: 'center' },
        { title: '持续(分钟)', dataIndex: 'durationMin', width: 90, align: 'center' },
        { title: '浓度峰值', dataIndex: 'maxLampblack', width: 100, align: 'center' },
        { title: '判定阈值', dataIndex: 'thresholdUsed', width: 90, align: 'center' },
        { title: '描述', dataIndex: 'description', ellipsis: true },
        { title: '处理状态', dataIndex: 'handleStatus', width: 130, align: 'center', scopedSlots: { customRender: 'handleStatus' } },
        { title: '归因', dataIndex: 'attributionStatus', width: 150, align: 'center', scopedSlots: { customRender: 'attribution' } }
      ],
      scanLoading: false,
      attrModal: false,
      attrLoading: false,
      viewModal: false,
      viewJson: '',
      currentRecord: null
    }
  },
  methods: {
    loadData(arg) {
      const that = this
      if (arg === 1) this.ipagination.current = 1
      const params = Object.assign({ pageNo: this.ipagination.current, pageSize: this.ipagination.pageSize }, this.queryParam)
      this.loading = true
      fetchAnomalyList(params).then((res) => {
        if (res.success) {
          this.dataSource = res.result.records || res.result
          if (res.result.total) this.ipagination.total = res.result.total
          else this.ipagination.total = 0
        } else {
          this.$message.warning(res.message)
        }
      }).finally(() => {
        this.loading = false
      })
    },
    changeStatus(record, status) {
      handleAnomaly({ id: record.id, status }).then((res) => {
        if (res.success) {
          this.$message.success('处理状态已更新')
          this.loadData()
        } else {
          this.$message.error(res.message)
        }
      })
    },
    showAttribution(record) {
      this.currentRecord = record
      this.attrModal = true
    },
    runAttr() {
      this.attrLoading = true
      anomalyAttribution({ id: this.currentRecord.id }).then((res) => {
        if (res.success) {
          this.$message.success('归因完成:' + (res.result.attributionStatus === 1 ? '大模型' : '规则降级'))
          this.attrModal = false
          this.loadData()
        } else {
          this.$message.error(res.message)
        }
      }).finally(() => {
        this.attrLoading = false
      })
    },
    viewAttribution(record) {
      try {
        const obj = JSON.parse(record.attributionJson)
        this.viewJson = JSON.stringify(obj, null, 2)
      } catch (e) {
        this.viewJson = record.attributionJson
      }
      this.viewModal = true
    },
    scanAndLoad() {
      this.scanLoading = true
      runAnomalyScan({}).then((res) => {
        if (res.success) {
          this.$message.success('扫描完成,新增异常 ' + res.result.created + ' 条')
          this.loadData(1)
        } else {
          this.$message.error(res.message)
        }
      }).finally(() => {
        this.scanLoading = false
      })
    }
  }
}
</script>
<style lang="less" scoped>
@import '~@assets/less/common.less';
</style>
