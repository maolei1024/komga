<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col><span class="text-h5">Gorse 推荐系统设置</span></v-col>
    </v-row>

    <!-- Settings Section -->
    <v-row>
      <v-col cols="12" md="6">
        <v-card outlined>
          <v-card-title>基本配置</v-card-title>
          <v-card-text>
            <v-switch
              v-model="form.enabled"
              @change="formDirty = true"
              label="启用 Gorse 集成"
              hint="启用后将自动同步系列和阅读进度到 Gorse"
              persistent-hint
              class="mb-4"
            />

            <v-text-field
              v-model="form.apiUrl"
              @input="formDirty = true"
              label="Gorse API 地址"
              hint="例如：http://localhost:8087"
              persistent-hint
              :rules="[rules.required]"
              class="mb-4"
            />

            <v-text-field
              v-model="form.apiKey"
              @input="formDirty = true"
              label="Gorse API 密钥"
              :type="showApiKey ? 'text' : 'password'"
              :append-icon="showApiKey ? 'mdi-eye' : 'mdi-eye-off'"
              @click:append="showApiKey = !showApiKey"
              hint="用于 Gorse 认证的 API 密钥"
              persistent-hint
              class="mb-4"
            />

            <v-text-field
              v-model="form.feedbackType"
              @input="formDirty = true"
              label="已读反馈类型"
              hint="阅读完成后发送到 Gorse 的反馈类型（默认：read）"
              persistent-hint
              :rules="[rules.required]"
              class="mb-4"
            />

            <v-text-field
              v-model="form.positiveFeedbackType"
              @input="formDirty = true"
              label="正反馈类型"
              hint="点击喜欢按钮时发送到 Gorse 的反馈类型（默认：like）"
              persistent-hint
              :rules="[rules.required]"
              class="mb-4"
            />

            <v-text-field
              v-model="form.anonymousUserId"
              @input="formDirty = true"
              label="匿名用户 ID"
              hint="未登录时使用的默认用户 ID（留空则匿名接口不可用）"
              persistent-hint
              class="mb-4"
            />

            <v-slider
              v-model="readThresholdPercent"
              @input="formDirty = true"
              label="阅读反馈阈值"
              :min="10"
              :max="100"
              :step="5"
              thumb-label="always"
              :thumb-size="24"
              class="mb-4"
            >
              <template v-slot:append>
                <span class="text-body-2">{{ readThresholdPercent }}%</span>
              </template>
            </v-slider>
            <div class="text-caption grey--text mt-n4 mb-4">
              阅读进度达到此百分比时发送反馈到 Gorse（默认：50%）
            </div>
          </v-card-text>
          <v-card-actions>
            <v-spacer/>
            <v-btn
              text
              :disabled="!formDirty"
              @click="refreshSettings"
            >放弃修改
            </v-btn>
            <v-btn
              color="primary"
              :disabled="!formDirty"
              :loading="saving"
              @click="saveSettings"
            >保存设置
            </v-btn>
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>

    <!-- Batch Sync Section -->
    <v-row class="mt-4">
      <v-col cols="12" md="6">
        <v-card outlined>
          <v-card-title>批量同步</v-card-title>
          <v-card-subtitle>
            手动将所有数据同步到 Gorse。适用于初始设置或数据修改后的重新同步。
          </v-card-subtitle>
          <v-card-text>
            <v-list>
              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>同步系列 → 项目</v-list-item-title>
                  <v-list-item-subtitle>
                    将所有漫画系列同步为 Gorse 项目，标签和类型作为标签。
                  </v-list-item-subtitle>
                </v-list-item-content>
                <v-list-item-action>
                  <v-btn
                    color="primary"
                    outlined
                    :loading="syncingItems"
                    :disabled="!form.enabled"
                    @click="syncItems"
                  >
                    <v-icon left>mdi-sync</v-icon>
                    同步
                  </v-btn>
                </v-list-item-action>
              </v-list-item>

              <v-divider/>

              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>同步用户</v-list-item-title>
                  <v-list-item-subtitle>
                    将所有 Komga 用户同步到 Gorse。
                  </v-list-item-subtitle>
                </v-list-item-content>
                <v-list-item-action>
                  <v-btn
                    color="primary"
                    outlined
                    :loading="syncingUsers"
                    :disabled="!form.enabled"
                    @click="syncUsers"
                  >
                    <v-icon left>mdi-sync</v-icon>
                    同步
                  </v-btn>
                </v-list-item-action>
              </v-list-item>

              <v-divider/>

              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>同步阅读进度 → 反馈</v-list-item-title>
                  <v-list-item-subtitle>
                    将已完成的阅读进度同步为 Gorse 反馈（类型：{{ form.feedbackType }}）。
                  </v-list-item-subtitle>
                </v-list-item-content>
                <v-list-item-action>
                  <v-btn
                    color="primary"
                    outlined
                    :loading="syncingFeedback"
                    :disabled="!form.enabled"
                    @click="syncFeedback"
                  >
                    <v-icon left>mdi-sync</v-icon>
                    同步
                  </v-btn>
                </v-list-item-action>
              </v-list-item>
            </v-list>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <!-- Snackbar for notifications -->
    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="3000">
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">关闭</v-btn>
      </template>
    </v-snackbar>
  </v-container>

</template>

<script lang="ts">
import Vue from 'vue'
import {GorseSettingsDto} from '@/services/komga-gorse.service'

export default Vue.extend({
  name: 'GorseSettings',
  data: () => ({
    form: {
      enabled: false,
      apiUrl: 'http://localhost:8087',
      apiKey: '',
      feedbackType: 'read',
      positiveFeedbackType: 'like',
      anonymousUserId: '',
      readThreshold: 0.5,
    } as GorseSettingsDto,
    formDirty: false,
    saving: false,
    showApiKey: false,
    syncingItems: false,
    syncingUsers: false,
    syncingFeedback: false,
    snackbar: false,
    snackbarText: '',
    snackbarColor: 'success',
    rules: {
      required: (value: string) => !!value || '必填项',
    },
  }),
  mounted() {
    this.refreshSettings()
  },
  computed: {
    readThresholdPercent: {
      get(): number {
        return Math.round(this.form.readThreshold * 100)
      },
      set(val: number) {
        this.form.readThreshold = val / 100
      },
    },
  },
  methods: {
    async refreshSettings() {
      try {
        const settings = await this.$komgaGorse.getSettings()
        this.form = {...settings}
        this.formDirty = false
      } catch (e) {
        this.showSnackbar('加载 Gorse 设置失败', 'error')
      }
    },
    async saveSettings() {
      this.saving = true
      try {
        await this.$komgaGorse.updateSettings(this.form)
        await this.refreshSettings()
        this.showSnackbar('设置已保存', 'success')
      } catch (e) {
        this.showSnackbar('保存设置失败', 'error')
      } finally {
        this.saving = false
      }
    },
    async syncItems() {
      this.syncingItems = true
      try {
        const result = await this.$komgaGorse.syncItems()
        this.showSnackbar(`已同步 ${result.count} 个项目到 Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('同步项目失败', 'error')
      } finally {
        this.syncingItems = false
      }
    },
    async syncUsers() {
      this.syncingUsers = true
      try {
        const result = await this.$komgaGorse.syncUsers()
        this.showSnackbar(`已同步 ${result.count} 个用户到 Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('同步用户失败', 'error')
      } finally {
        this.syncingUsers = false
      }
    },
    async syncFeedback() {
      this.syncingFeedback = true
      try {
        const result = await this.$komgaGorse.syncFeedback()
        this.showSnackbar(`已同步 ${result.count} 条反馈到 Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('同步反馈失败', 'error')
      } finally {
        this.syncingFeedback = false
      }
    },
    showSnackbar(text: string, color: string) {
      this.snackbarText = text
      this.snackbarColor = color
      this.snackbar = true
    },
  },
})
</script>

<style scoped>
</style>
