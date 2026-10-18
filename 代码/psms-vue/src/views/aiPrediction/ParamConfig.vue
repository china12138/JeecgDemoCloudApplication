<template>
  <a-card :bordered="false" title="预测参数配置(保存后立即热生效)">
    <template slot="extra">
      <a-button type="primary" icon="save" :loading="saving" @click="saveAll">保存全部</a-button>
    </template>
    <a-spin :spinning="loading">
      <a-row :gutter="16">
        <a-col :span="8" v-for="grp in groups" :key="grp.group" style="margin-bottom: 16px">
          <a-card size="small" :title="grp.groupName" :bordered="true">
            <a-form layout="vertical" v-for="it in grp.items" :key="it.id">
              <a-form-item :label="it.cfgName" style="margin-bottom: 8px">
                <a-tooltip :title="it.cfgRemark" placement="topLeft">
                  <a-input v-model="it.cfgValue" :suffix="typeSuffix(it.cfgType)" allowClear />
                </a-tooltip>
              </a-form-item>
            </a-form>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>
  </a-card>
</template>

<script>
import { fetchConfigList, saveConfig } from '@/api/aipredict'

export default {
  name: 'ParamConfig',
  data() {
    return {
      loading: false,
      saving: false,
      groups: []
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      fetchConfigList().then((res) => {
        if (res.success) {
          this.groups = res.result || []
        } else {
          this.$message.error(res.message)
        }
      }).finally(() => {
        this.loading = false
      })
    },
    typeSuffix(type) {
      return { int: 'int', decimal: '数', string: 'text', bool: '0/1' }[type] || ''
    },
    saveAll() {
      const items = []
      this.groups.forEach((g) => {
        ;(g.items || []).forEach((it) => {
          items.push({
            id: it.id,
            cfgValue: it.cfgValue,
            cfgRemark: it.cfgRemark,
            enabled: 1
          })
        })
      })
      this.saving = true
      saveConfig(JSON.stringify(items)).then((res) => {
        if (res.success) {
          this.$message.success(res.result || '保存成功')
        } else {
          this.$message.error(res.message)
        }
      }).finally(() => {
        this.saving = false
      })
    }
  }
}
</script>
