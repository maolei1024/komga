<template>
  <v-container fluid class="pa-4 pa-md-6 enrichment-page">
    <div class="d-flex flex-wrap align-center mb-4">
      <div>
        <h1 class="text-h5 mb-1">元数据增强</h1>
        <p class="text-body-2 grey--text text--darken-1 mb-0 section-copy">
          增强结果仅写入 Komga 数据库。CBZ 原文件不会被修改、重压或回写。
        </p>
      </div>
      <v-spacer/>
      <v-btn icon :loading="refreshing" :disabled="refreshing" aria-label="刷新" @click="refreshAll">
        <v-icon>mdi-refresh</v-icon>
      </v-btn>
    </div>

    <v-tabs v-model="tab" show-arrows class="mb-4">
      <v-tab>配置</v-tab>
      <v-tab>处理状态</v-tab>
      <v-tab>未翻译 Tag</v-tab>
    </v-tabs>

    <v-tabs-items v-model="tab" class="transparent">
      <v-tab-item>
        <v-skeleton-loader v-if="initialLoading" type="article, article, table"/>

        <template v-else>
          <div class="d-flex flex-wrap align-center mb-4">
            <span class="text-body-2 grey--text text--darken-1">
              AI 设置变化只会标记旧结果过期；pageSize/tagSize 分桶变化会自动低优先级重算。
            </span>
            <v-spacer/>
            <v-btn text :disabled="!settingsDirty || settingsSaving" @click="loadSettings">放弃修改</v-btn>
            <v-btn
              color="primary"
              :disabled="!settingsDirty"
              :loading="settingsSaving"
              @click="saveSettings"
            >保存配置</v-btn>
          </div>

          <v-row>
            <v-col cols="12" lg="6">
              <v-card outlined height="100%">
                <v-card-title>AI 标题</v-card-title>
                <v-card-subtitle>
                  仅对新书自动执行一次，固定全局并发 1；扫描和模型变化不会自动产生新的 AI 调用。
                </v-card-subtitle>
                <v-card-text>
                  <v-switch
                    v-model="settings.aiEnabled"
                    label="启用 AI 标题"
                    :hint="aiConfigurationHint"
                    persistent-hint
                    @change="markSettingsDirty"
                  />
                  <v-switch
                    v-model="settings.aiAutoOnNew"
                    label="新书自动执行一次"
                    hint="关闭后只能从状态页手动触发"
                    persistent-hint
                    :disabled="!settings.aiEnabled"
                    class="mb-3"
                    @change="markSettingsDirty"
                  />
                  <v-text-field
                    v-model="settings.aiBaseUrl"
                    label="OpenAI 兼容地址"
                    hint="例如 https://example.com；根地址会自动使用 /v1"
                    persistent-hint
                    class="mb-3"
                    @input="markSettingsDirty"
                  />
                  <v-text-field
                    v-model="settings.aiModel"
                    label="模型"
                    persistent-hint
                    hint="切换模型时请同步调整下方 Prompt"
                    class="mb-3"
                    @input="markSettingsDirty"
                  />
                  <v-textarea
                    v-model="settings.aiPrompt"
                    label="System Prompt"
                    hint="Prompt 与当前模型一起使用；模型、地址或 Prompt 变化只会标记旧结果过期，不会自动调用 AI"
                    persistent-hint
                    auto-grow
                    rows="6"
                    :counter="20000"
                    class="mb-3"
                    @input="markSettingsDirty"
                  />
                  <v-text-field
                    v-model="newApiKey"
                    label="API Key"
                    :type="showApiKey ? 'text' : 'password'"
                    :append-icon="showApiKey ? 'mdi-eye-off' : 'mdi-eye'"
                    :placeholder="settings.apiKeyConfigured ? '已配置；留空保持不变' : '尚未配置'"
                    :disabled="clearApiKey"
                    autocomplete="new-password"
                    @click:append="showApiKey = !showApiKey"
                    @input="markSettingsDirty"
                  />
                  <v-checkbox
                    v-model="clearApiKey"
                    label="清除已保存的 API Key"
                    :disabled="!settings.apiKeyConfigured"
                    class="mt-0"
                    @change="onClearApiKeyChanged"
                  />
                  <v-row>
                    <v-col cols="12" sm="6">
                      <v-text-field
                        v-model.number="settings.aiTimeoutSeconds"
                        type="number"
                        min="1"
                        max="600"
                        label="超时（秒）"
                        @input="markSettingsDirty"
                      />
                    </v-col>
                    <v-col cols="12" sm="6">
                      <v-text-field
                        v-model.number="settings.aiMaxRetries"
                        type="number"
                        min="0"
                        max="10"
                        label="失败重试次数"
                        @input="markSettingsDirty"
                      />
                    </v-col>
                  </v-row>
                </v-card-text>
              </v-card>
            </v-col>

            <v-col cols="12" lg="6">
              <v-card outlined height="100%">
                <v-card-title>分桶</v-card-title>
                <v-card-subtitle>
                  区间必须连续、不重叠，最后一档留空表示无上限。标签名保持现有兼容格式。
                </v-card-subtitle>
                <v-card-text>
                  <div class="text-subtitle-2 mb-2">pageSize</div>
                  <bucket-editor
                    :buckets="settings.pageSizeBuckets"
                    @changed="markSettingsDirty"
                  />

                  <div class="text-subtitle-2 mt-6 mb-2">tagSize</div>
                  <bucket-editor
                    :buckets="settings.tagSizeBuckets"
                    @changed="markSettingsDirty"
                  />
                </v-card-text>
              </v-card>
            </v-col>
          </v-row>

          <v-card outlined class="mt-4">
            <v-card-title>Tag 字典</v-card-title>
            <v-card-subtitle>
              overrides 优先于基础字典。默认更新策略只标记相关书籍过期，不会自动重翻。
            </v-card-subtitle>
            <v-card-text>
              <v-row align="end">
                <v-col cols="12" md="5">
                  <v-select
                    v-model="settings.dictionaryUpdatePolicy"
                    :items="dictionaryPolicyItems"
                    label="字典更新策略"
                    @change="markSettingsDirty"
                  />
                </v-col>
                <v-col cols="12" md="7">
                  <div class="text-body-2 mb-1">
                    基础词条 {{ settings.baseDictionaryEntryCount.toLocaleString() }}，覆盖词条
                    {{ settings.overrideEntryCount.toLocaleString() }}
                  </div>
                  <div class="text-caption grey--text text--darken-1 text-truncate">
                    指纹：{{ settings.dictionaryFingerprint }}
                  </div>
                </v-col>
              </v-row>

              <v-divider class="my-4"/>

              <div class="text-subtitle-2 mb-2">替换基础字典</div>
              <v-row align="center">
                <v-col cols="12" md="8">
                  <v-file-input
                    v-model="baseDictionaryFile"
                    accept="application/json,.json"
                    label="选择 ehtags-cn.json"
                    prepend-icon="mdi-file-code-outline"
                    show-size
                    hide-details
                  />
                </v-col>
                <v-col cols="12" md="4" class="d-flex justify-md-end">
                  <v-btn
                    color="primary"
                    outlined
                    :disabled="!baseDictionaryFile"
                    :loading="dictionaryUploading"
                    @click="uploadBaseDictionary"
                  >上传并原子替换</v-btn>
                </v-col>
              </v-row>

              <v-divider class="my-5"/>

              <div class="text-subtitle-2 mb-2">自定义覆盖</div>
              <v-row align="start">
                <v-col cols="12" sm="3" md="2">
                  <v-select v-model="overrideForm.t" :items="tagTypeItems" label="类型"/>
                </v-col>
                <v-col cols="12" sm="9" md="4">
                  <v-text-field v-model="overrideForm.k" label="原文 Tag"/>
                </v-col>
                <v-col cols="12" sm="8" md="4">
                  <v-text-field v-model="overrideForm.v" label="翻译"/>
                </v-col>
                <v-col cols="12" sm="4" md="2" class="d-flex flex-column ga-2">
                  <v-btn text small :disabled="!overrideForm.k" @click="keepOriginal">保留原文</v-btn>
                  <v-btn
                    color="primary"
                    :disabled="!canSaveOverride"
                    :loading="overrideSaving"
                    @click="saveOverride"
                  >保存覆盖</v-btn>
                </v-col>
              </v-row>

              <v-data-table
                :headers="overrideHeaders"
                :items="overrides"
                :loading="overridesLoading"
                :items-per-page="10"
                :footer-props="{itemsPerPageOptions: [10, 25, 50]}"
                class="mt-3"
              >
                <template v-slot:item.actions="{ item }">
                  <v-btn icon small aria-label="编辑覆盖" @click="editOverride(item)">
                    <v-icon small>mdi-pencil</v-icon>
                  </v-btn>
                  <v-btn icon small aria-label="删除覆盖" @click="deleteOverride(item)">
                    <v-icon small>mdi-delete-outline</v-icon>
                  </v-btn>
                </template>
                <template v-slot:no-data>
                  尚无自定义覆盖。可在上方添加，或从“未翻译 Tag”页直接维护。
                </template>
              </v-data-table>
            </v-card-text>
          </v-card>
        </template>
      </v-tab-item>

      <v-tab-item>
        <v-card outlined class="mb-4">
          <v-card-title>状态总览</v-card-title>
          <v-card-subtitle>
            成功表示当前 revision 已完成；过期表示输入或配置已经变化、尚未重跑，最后一次成功结果仍继续生效。点击数量可带入下方筛选。
          </v-card-subtitle>
          <v-card-text>
            <v-simple-table>
              <thead>
              <tr>
                <th>处理器</th>
                <th v-for="status in statuses" :key="status">{{ statusLabel(status) }}</th>
              </tr>
              </thead>
              <tbody>
              <tr v-for="processor in processors" :key="processor">
                <td class="font-weight-medium">{{ processorLabel(processor) }}</td>
                <td v-for="status in statuses" :key="status">
                  <v-btn text small color="primary" @click="applyStatusFilter(processor, status)">
                    {{ statusCount(processor, status).toLocaleString() }}
                  </v-btn>
                </td>
              </tr>
              </tbody>
            </v-simple-table>
          </v-card-text>
        </v-card>

        <v-card outlined>
          <v-card-title class="d-flex flex-wrap align-center">
            <span>书籍状态</span>
            <v-spacer/>
            <v-btn
              color="primary"
              outlined
              :disabled="!stateProcessor || stateTotal === 0 || statesLoading"
              @click="openBulkDialog"
            >批量重跑当前结果（{{ stateTotal.toLocaleString() }}）</v-btn>
          </v-card-title>
          <v-card-text>
            <v-row>
              <v-col cols="12" sm="4">
                <v-select
                  v-model="stateProcessor"
                  :items="processorItems"
                  clearable
                  label="处理器"
                  @change="onStateFilterChanged"
                />
              </v-col>
              <v-col cols="12" sm="4">
                <v-select
                  v-model="stateStatus"
                  :items="statusItems"
                  clearable
                  label="状态"
                  @change="onStateFilterChanged"
                />
              </v-col>
              <v-col cols="12" sm="4">
                <v-select
                  v-model="stateLibraryId"
                  :items="libraryItems"
                  clearable
                  label="书库"
                  @change="onStateFilterChanged"
                />
              </v-col>
            </v-row>

            <v-data-table
              :headers="stateHeaders"
              :items="stateItems"
              :options.sync="stateOptions"
              :server-items-length="stateTotal"
              :loading="statesLoading"
              :footer-props="{itemsPerPageOptions: [20, 50, 100]}"
            >
              <template v-slot:item.bookTitle="{ item }">
                <router-link :to="{name: 'browse-book', params: {bookId: item.bookId}}" class="link-underline">
                  {{ item.bookTitle || item.bookName || item.bookId }}
                </router-link>
              </template>
              <template v-slot:item.processor="{ item }">{{ processorLabel(item.processor) }}</template>
              <template v-slot:item.status="{ item }">
                <v-chip small :color="statusColor(item.status)" :text-color="statusTextColor(item.status)">
                  {{ statusLabel(item.status) }}
                </v-chip>
                <span v-if="item.hasResult && item.status !== 'SUCCESS'" class="text-caption ml-2">旧结果仍生效</span>
              </template>
              <template v-slot:item.lastModifiedDate="{ item }">{{ formatDate(item.lastModifiedDate) }}</template>
              <template v-slot:item.error="{ item }">
                <v-btn v-if="item.lastError" icon small aria-label="查看错误" @click="showError(item)">
                  <v-icon small color="error">mdi-alert-circle-outline</v-icon>
                </v-btn>
                <span v-else>—</span>
              </template>
              <template v-slot:item.actions="{ item }">
                <v-btn
                  small
                  text
                  color="primary"
                  :loading="rerunningBookId === item.bookId && rerunningProcessor === item.processor"
                  @click="rerunOne(item)"
                >重跑</v-btn>
              </template>
              <template v-slot:no-data>
                没有符合筛选条件的状态。首次启动登记任务可能仍在最低优先级队列中。
              </template>
            </v-data-table>
          </v-card-text>
        </v-card>
      </v-tab-item>

      <v-tab-item>
        <v-card outlined>
          <v-card-title>未翻译 Tag</v-card-title>
          <v-card-subtitle>
            列表从当前书籍源 Tag 动态聚合。保存覆盖后，该 Tag 会立即从列表消失，并按字典策略处理相关书籍。
          </v-card-subtitle>
          <v-card-text>
            <v-row align="start">
              <v-col cols="12" sm="3" md="2">
                <v-select v-model="missingOverrideForm.t" :items="tagTypeItems" label="类型"/>
              </v-col>
              <v-col cols="12" sm="9" md="4">
                <v-text-field v-model="missingOverrideForm.k" label="原文 Tag" readonly/>
              </v-col>
              <v-col cols="12" sm="8" md="4">
                <v-text-field v-model="missingOverrideForm.v" label="翻译"/>
              </v-col>
              <v-col cols="12" sm="4" md="2" class="d-flex flex-column ga-2">
                <v-btn text small :disabled="!missingOverrideForm.k" @click="keepMissingOriginal">保留原文</v-btn>
                <v-btn
                  color="primary"
                  :disabled="!canSaveMissingOverride"
                  :loading="missingOverrideSaving"
                  @click="saveMissingOverride"
                >保存翻译</v-btn>
              </v-col>
            </v-row>

            <v-divider class="my-4"/>

            <v-row align="center">
              <v-col cols="12" sm="8">
                <v-text-field
                  v-model="missingSearch"
                  clearable
                  label="搜索原文 Tag"
                  prepend-inner-icon="mdi-magnify"
                  hide-details
                  @keyup.enter="searchMissingTags"
                  @click:clear="searchMissingTags"
                />
              </v-col>
              <v-col cols="12" sm="4">
                <v-select
                  v-model="missingType"
                  :items="tagTypeItems"
                  clearable
                  label="类型"
                  hide-details
                  @change="onMissingFilterChanged"
                />
              </v-col>
            </v-row>

            <v-data-table
              :headers="missingHeaders"
              :items="missingItems"
              :options.sync="missingOptions"
              :server-items-length="missingTotal"
              :loading="missingLoading"
              :footer-props="{itemsPerPageOptions: [20, 50, 100]}"
              class="mt-4"
              @click:row="selectMissingTag"
            >
              <template v-slot:item.type="{ item }">{{ tagTypeLabel(item.type) }}</template>
              <template v-slot:item.bookCount="{ item }">{{ item.bookCount.toLocaleString() }}</template>
              <template v-slot:item.actions="{ item }">
                <v-btn small text color="primary" @click.stop="selectMissingTag(item)">维护翻译</v-btn>
              </template>
              <template v-slot:no-data>
                当前筛选下没有未翻译 Tag。
              </template>
            </v-data-table>
          </v-card-text>
        </v-card>
      </v-tab-item>
    </v-tabs-items>

    <v-dialog v-model="bulkDialog" max-width="560">
      <v-card>
        <v-card-title>确认批量重跑</v-card-title>
        <v-card-text>
          将提交 {{ stateTotal.toLocaleString() }} 本书的“{{ stateProcessor ? processorLabel(stateProcessor) : '' }}”任务。
          <p v-if="stateProcessor === 'AI_TITLE'" class="font-weight-medium error--text mt-3 mb-0">
            AI 标题会产生外部模型费用，且任务固定串行执行。请确认本次范围无误。
          </p>
          <p v-else class="mt-3 mb-0 grey--text text--darken-1">
            批量任务使用低优先级，不会抢占单书手动操作。
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer/>
          <v-btn text :disabled="bulkSubmitting" @click="bulkDialog = false">取消</v-btn>
          <v-btn color="primary" :loading="bulkSubmitting" @click="confirmBulkRun">确认提交</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="errorDialog" max-width="720">
      <v-card>
        <v-card-title>处理错误</v-card-title>
        <v-card-text>
          <div v-if="errorItem" class="text-body-2 mb-3">
            {{ errorItem.bookTitle || errorItem.bookName || errorItem.bookId }} · {{ processorLabel(errorItem.processor) }}
          </div>
          <pre class="error-detail">{{ errorItem && errorItem.lastError }}</pre>
        </v-card-text>
        <v-card-actions>
          <v-spacer/>
          <v-btn text @click="errorDialog = false">关闭</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar v-model="snackbar" :color="snackbarColor" :timeout="4000">
      {{ snackbarText }}
      <template v-slot:action="{ attrs }">
        <v-btn text v-bind="attrs" @click="snackbar = false">关闭</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {
  DictionaryUpdatePolicy,
  MetadataEnrichmentBucket,
  MetadataEnrichmentMissingTagDto,
  MetadataEnrichmentOverrideDto,
  MetadataEnrichmentProcessor,
  MetadataEnrichmentSettingsDto,
  MetadataEnrichmentStateDto,
  MetadataEnrichmentStatus,
  MetadataEnrichmentStatusCountDto,
} from '@/services/komga-metadata-enrichment.service'
import BucketEditor from '@/components/MetadataEnrichmentBucketEditor.vue'

const emptySettings = (): MetadataEnrichmentSettingsDto => ({
  aiEnabled: false,
  aiAutoOnNew: true,
  aiBaseUrl: '',
  aiModel: '',
  aiPrompt: '',
  apiKeyConfigured: false,
  aiTimeoutSeconds: 60,
  aiMaxRetries: 3,
  dictionaryUpdatePolicy: 'MARK_STALE',
  pageSizeBuckets: [],
  tagSizeBuckets: [],
  baseDictionaryEntryCount: 0,
  overrideEntryCount: 0,
  dictionaryFingerprint: '',
})

export default Vue.extend({
  name: 'MetadataEnrichmentSettings',
  components: {BucketEditor},
  data: () => ({
    tab: 0,
    initialLoading: true,
    refreshing: false,
    settings: emptySettings(),
    settingsDirty: false,
    settingsSaving: false,
    newApiKey: '',
    clearApiKey: false,
    showApiKey: false,
    baseDictionaryFile: null as File | null,
    dictionaryUploading: false,
    overrides: [] as MetadataEnrichmentOverrideDto[],
    overridesLoading: false,
    overrideSaving: false,
    overrideForm: {t: 'tag', k: '', v: ''} as MetadataEnrichmentOverrideDto,
    stats: [] as MetadataEnrichmentStatusCountDto[],
    stateItems: [] as MetadataEnrichmentStateDto[],
    stateTotal: 0,
    stateLoadVersion: 0,
    statesLoading: false,
    stateProcessor: null as MetadataEnrichmentProcessor | null,
    stateStatus: null as MetadataEnrichmentStatus | null,
    stateLibraryId: null as string | null,
    stateOptions: {page: 1, itemsPerPage: 20, sortBy: [], sortDesc: []} as any,
    rerunningBookId: '',
    rerunningProcessor: null as MetadataEnrichmentProcessor | null,
    bulkDialog: false,
    bulkSubmitting: false,
    errorDialog: false,
    errorItem: null as MetadataEnrichmentStateDto | null,
    missingItems: [] as MetadataEnrichmentMissingTagDto[],
    missingTotal: 0,
    missingLoading: false,
    missingSearch: '',
    missingType: null as string | null,
    missingOptions: {page: 1, itemsPerPage: 20, sortBy: [], sortDesc: []} as any,
    missingOverrideSaving: false,
    missingOverrideForm: {t: 'tag', k: '', v: ''} as MetadataEnrichmentOverrideDto,
    snackbar: false,
    snackbarText: '',
    snackbarColor: 'success',
    processors: ['AI_TITLE', 'TAG_TRANSLATION', 'PAGE_SIZE', 'TAG_SIZE'] as MetadataEnrichmentProcessor[],
    statuses: ['WAITING', 'RUNNING', 'FAILED', 'STALE', 'SUCCESS'] as MetadataEnrichmentStatus[],
  }),
  computed: {
    processorItems(): object[] {
      return this.processors.map(value => ({value, text: this.processorLabel(value)}))
    },
    statusItems(): object[] {
      return this.statuses.map(value => ({value, text: this.statusLabel(value)}))
    },
    tagTypeItems(): object[] {
      return [
        {value: 'tag', text: '普通 Tag'},
        {value: 'character', text: '角色'},
        {value: 'artist', text: '作者'},
        {value: 'group', text: '社团'},
      ]
    },
    dictionaryPolicyItems(): object[] {
      return [
        {value: 'MARK_STALE' as DictionaryUpdatePolicy, text: '仅标记过期（默认）'},
        {value: 'AUTO_LOW_PRIORITY' as DictionaryUpdatePolicy, text: '自动低优先级重翻'},
      ]
    },
    libraryItems(): object[] {
      const libraries = (this.$store.state as any).komgaLibraries.libraries || []
      return libraries.map((library: any) => ({value: library.id, text: library.name}))
    },
    aiConfigurationHint(): string {
      if (this.settings.apiKeyConfigured) return 'API Key 已安全保存，页面不会回显明文'
      return '需要同时配置地址、模型和 API Key 才能启用'
    },
    canSaveOverride(): boolean {
      return this.overrideForm.k.trim().length > 0 && this.overrideForm.v.trim().length > 0
    },
    canSaveMissingOverride(): boolean {
      return this.missingOverrideForm.k.trim().length > 0 && this.missingOverrideForm.v.trim().length > 0
    },
    overrideHeaders(): object[] {
      return [
        {text: '类型', value: 't'},
        {text: '原文', value: 'k'},
        {text: '翻译', value: 'v'},
        {text: '操作', value: 'actions', sortable: false, align: 'end'},
      ]
    },
    stateHeaders(): object[] {
      return [
        {text: '书籍', value: 'bookTitle', sortable: false},
        {text: '处理器', value: 'processor', sortable: false},
        {text: '状态', value: 'status', sortable: false},
        {text: '更新时间', value: 'lastModifiedDate', sortable: false},
        {text: '错误', value: 'error', sortable: false, align: 'center'},
        {text: '操作', value: 'actions', sortable: false, align: 'end'},
      ]
    },
    missingHeaders(): object[] {
      return [
        {text: '类型', value: 'type', sortable: false},
        {text: '原文', value: 'value', sortable: false},
        {text: '涉及书籍', value: 'bookCount', sortable: false, align: 'end'},
        {text: '操作', value: 'actions', sortable: false, align: 'end'},
      ]
    },
  },
  watch: {
    stateOptions: {
      handler() {
        this.loadStates()
      },
      deep: true,
    },
    missingOptions: {
      handler() {
        this.loadMissingTags()
      },
      deep: true,
    },
  },
  mounted() {
    this.refreshAll(true)
  },
  methods: {
    async refreshAll(initial = false) {
      if (!initial && this.settingsDirty && !window.confirm('刷新会放弃尚未保存的配置，继续吗？')) return
      if (initial) this.initialLoading = true
      else this.refreshing = true
      try {
        await Promise.all([
          this.loadSettings(),
          this.loadStats(),
          this.loadOverrides(),
          this.loadStates(),
          this.loadMissingTags(),
        ])
      } finally {
        this.initialLoading = false
        this.refreshing = false
      }
    },
    async loadSettings() {
      try {
        const settings = await this.$komgaMetadataEnrichment.getSettings()
        this.settings = JSON.parse(JSON.stringify(settings))
        this.newApiKey = ''
        this.clearApiKey = false
        this.settingsDirty = false
      } catch (e) {
        this.notify(this.errorMessage(e, '加载元数据增强设置失败'), 'error')
      }
    },
    markSettingsDirty() {
      this.settingsDirty = true
    },
    onClearApiKeyChanged() {
      if (this.clearApiKey) this.newApiKey = ''
      this.markSettingsDirty()
    },
    normalizedBuckets(buckets: MetadataEnrichmentBucket[]): MetadataEnrichmentBucket[] {
      return buckets.map((bucket: any) => ({
        min: Number(bucket.min),
        max: bucket.max === null || bucket.max === '' || typeof bucket.max === 'undefined' ? null : Number(bucket.max),
        label: String(bucket.label || '').trim(),
      }))
    },
    async saveSettings() {
      this.settingsSaving = true
      try {
        const update: any = {
          aiEnabled: this.settings.aiEnabled,
          aiAutoOnNew: this.settings.aiAutoOnNew,
          aiBaseUrl: this.settings.aiBaseUrl,
          aiModel: this.settings.aiModel,
          aiPrompt: this.settings.aiPrompt,
          aiTimeoutSeconds: Number(this.settings.aiTimeoutSeconds),
          aiMaxRetries: Number(this.settings.aiMaxRetries),
          dictionaryUpdatePolicy: this.settings.dictionaryUpdatePolicy,
          pageSizeBuckets: this.normalizedBuckets(this.settings.pageSizeBuckets),
          tagSizeBuckets: this.normalizedBuckets(this.settings.tagSizeBuckets),
        }
        if (this.newApiKey.trim()) update.aiApiKey = this.newApiKey.trim()
        if (this.clearApiKey) update.clearAiApiKey = true
        await this.$komgaMetadataEnrichment.updateSettings(update)
        await Promise.all([this.loadSettings(), this.loadStats(), this.loadStates()])
        this.notify('配置已保存', 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '保存配置失败'), 'error')
      } finally {
        this.settingsSaving = false
      }
    },
    async uploadBaseDictionary() {
      if (!this.baseDictionaryFile) return
      this.dictionaryUploading = true
      try {
        const result = await this.$komgaMetadataEnrichment.replaceBaseDictionary(this.baseDictionaryFile)
        this.baseDictionaryFile = null
        this.applyDictionaryResult(result)
        await this.refreshOperationalData()
        this.notify(`基础字典已替换，${result.invalidatedBooks.toLocaleString()} 本书已按策略处理`, 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '基础字典上传失败'), 'error')
      } finally {
        this.dictionaryUploading = false
      }
    },
    keepOriginal() {
      this.overrideForm.v = this.overrideForm.k
    },
    editOverride(item: MetadataEnrichmentOverrideDto) {
      this.overrideForm = {...item}
    },
    async saveOverride() {
      if (!this.canSaveOverride) return
      this.overrideSaving = true
      try {
        const result = await this.$komgaMetadataEnrichment.putOverride({
          t: this.overrideForm.t,
          k: this.overrideForm.k.trim(),
          v: this.overrideForm.v.trim(),
        })
        this.overrideForm = {t: 'tag', k: '', v: ''}
        this.applyDictionaryResult(result)
        await this.refreshOperationalData()
        this.notify(`覆盖已保存，影响 ${result.invalidatedBooks.toLocaleString()} 本书`, 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '保存覆盖失败'), 'error')
      } finally {
        this.overrideSaving = false
      }
    },
    async deleteOverride(item: MetadataEnrichmentOverrideDto) {
      if (!window.confirm(`删除 ${item.t}:${item.k} 的覆盖翻译？`)) return
      try {
        const result = await this.$komgaMetadataEnrichment.deleteOverride(item.t, item.k)
        this.applyDictionaryResult(result)
        await this.refreshOperationalData()
        this.notify(`覆盖已删除，影响 ${result.invalidatedBooks.toLocaleString()} 本书`, 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '删除覆盖失败'), 'error')
      }
    },
    async loadOverrides() {
      this.overridesLoading = true
      try {
        this.overrides = await this.$komgaMetadataEnrichment.getOverrides()
      } catch (e) {
        this.notify(this.errorMessage(e, '加载字典覆盖失败'), 'error')
      } finally {
        this.overridesLoading = false
      }
    },
    async loadStats() {
      try {
        this.stats = await this.$komgaMetadataEnrichment.getStats()
      } catch (e) {
        this.notify(this.errorMessage(e, '加载状态统计失败'), 'error')
      }
    },
    statusCount(processor: MetadataEnrichmentProcessor, status: MetadataEnrichmentStatus): number {
      return this.stats.find(item => item.processor === processor && item.status === status)?.count || 0
    },
    applyStatusFilter(processor: MetadataEnrichmentProcessor, status: MetadataEnrichmentStatus) {
      this.stateProcessor = processor
      this.stateStatus = status
      this.tab = 1
      this.onStateFilterChanged()
    },
    onStateFilterChanged() {
      this.stateItems = []
      this.stateTotal = 0
      if (this.stateOptions.page !== 1) this.stateOptions.page = 1
      else this.loadStates()
    },
    async loadStates() {
      if (!this.stateOptions.page || !this.stateOptions.itemsPerPage) return
      const loadVersion = ++this.stateLoadVersion
      this.statesLoading = true
      try {
        const page = await this.$komgaMetadataEnrichment.getStates({
          processor: this.stateProcessor || undefined,
          status: this.stateStatus || undefined,
          libraryId: this.stateLibraryId || undefined,
          page: this.stateOptions.page - 1,
          size: this.stateOptions.itemsPerPage,
        })
        if (loadVersion === this.stateLoadVersion) {
          this.stateItems = page.content
          this.stateTotal = page.totalElements
        }
      } catch (e) {
        if (loadVersion === this.stateLoadVersion) this.notify(this.errorMessage(e, '加载书籍状态失败'), 'error')
      } finally {
        if (loadVersion === this.stateLoadVersion) this.statesLoading = false
      }
    },
    async rerunOne(item: MetadataEnrichmentStateDto) {
      this.rerunningBookId = item.bookId
      this.rerunningProcessor = item.processor
      try {
        const result = await this.$komgaMetadataEnrichment.requestRuns({processor: item.processor, bookIds: [item.bookId]})
        await Promise.all([this.loadStats(), this.loadStates()])
        this.notify(result.accepted === 1 ? '已提交单书重跑' : '任务未提交，请检查 AI 配置或书籍源数据', result.accepted === 1 ? 'success' : 'warning')
      } catch (e) {
        this.notify(this.errorMessage(e, '提交重跑失败'), 'error')
      } finally {
        this.rerunningBookId = ''
        this.rerunningProcessor = null
      }
    },
    openBulkDialog() {
      if (!this.stateProcessor || this.stateTotal === 0 || this.statesLoading) return
      this.bulkDialog = true
    },
    async confirmBulkRun() {
      if (!this.stateProcessor) return
      this.bulkSubmitting = true
      try {
        const result = await this.$komgaMetadataEnrichment.requestRuns({
          processor: this.stateProcessor,
          status: this.stateStatus || undefined,
          libraryId: this.stateLibraryId || undefined,
        })
        this.bulkDialog = false
        await Promise.all([this.loadStats(), this.loadStates()])
        this.notify(`已接受 ${result.accepted.toLocaleString()} 个低优先级任务`, 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '批量重跑提交失败'), 'error')
      } finally {
        this.bulkSubmitting = false
      }
    },
    showError(item: MetadataEnrichmentStateDto) {
      this.errorItem = item
      this.errorDialog = true
    },
    searchMissingTags() {
      if (this.missingOptions.page !== 1) this.missingOptions.page = 1
      else this.loadMissingTags()
    },
    onMissingFilterChanged() {
      this.searchMissingTags()
    },
    async loadMissingTags() {
      if (!this.missingOptions.page || !this.missingOptions.itemsPerPage) return
      this.missingLoading = true
      try {
        const page = await this.$komgaMetadataEnrichment.getUntranslatedTags({
          search: this.missingSearch || undefined,
          type: this.missingType || undefined,
          page: this.missingOptions.page - 1,
          size: this.missingOptions.itemsPerPage,
        })
        this.missingItems = page.content
        this.missingTotal = page.totalElements
      } catch (e) {
        this.notify(this.errorMessage(e, '加载未翻译 Tag 失败'), 'error')
      } finally {
        this.missingLoading = false
      }
    },
    selectMissingTag(item: MetadataEnrichmentMissingTagDto) {
      this.missingOverrideForm = {t: item.type, k: item.value, v: ''}
    },
    keepMissingOriginal() {
      this.missingOverrideForm.v = this.missingOverrideForm.k
    },
    async saveMissingOverride() {
      if (!this.canSaveMissingOverride) return
      this.missingOverrideSaving = true
      try {
        const result = await this.$komgaMetadataEnrichment.putOverride({
          t: this.missingOverrideForm.t,
          k: this.missingOverrideForm.k.trim(),
          v: this.missingOverrideForm.v.trim(),
        })
        this.missingOverrideForm = {t: 'tag', k: '', v: ''}
        this.applyDictionaryResult(result)
        await this.refreshOperationalData()
        this.notify(`翻译已保存，影响 ${result.invalidatedBooks.toLocaleString()} 本书`, 'success')
      } catch (e) {
        this.notify(this.errorMessage(e, '保存 Tag 翻译失败'), 'error')
      } finally {
        this.missingOverrideSaving = false
      }
    },
    async refreshOperationalData() {
      await Promise.all([
        this.loadOverrides(),
        this.loadStats(),
        this.loadStates(),
        this.loadMissingTags(),
      ])
    },
    applyDictionaryResult(result: any) {
      this.settings.baseDictionaryEntryCount = result.baseDictionaryEntryCount
      this.settings.overrideEntryCount = result.overrideEntryCount
      this.settings.dictionaryFingerprint = result.dictionaryFingerprint
    },
    processorLabel(processor: MetadataEnrichmentProcessor): string {
      return ({AI_TITLE: 'AI 标题', TAG_TRANSLATION: 'Tag 翻译', PAGE_SIZE: 'pageSize', TAG_SIZE: 'tagSize'} as any)[processor]
    },
    statusLabel(status: MetadataEnrichmentStatus): string {
      return ({WAITING: '等待', RUNNING: '运行', FAILED: '失败', STALE: '过期', SUCCESS: '成功'} as any)[status]
    },
    tagTypeLabel(type: string): string {
      return ({tag: '普通 Tag', character: '角色', artist: '作者', group: '社团'} as any)[type] || type
    },
    statusColor(status: MetadataEnrichmentStatus): string {
      return ({WAITING: 'blue-grey lighten-4', RUNNING: 'info', FAILED: 'error', STALE: 'warning', SUCCESS: 'success'} as any)[status]
    },
    statusTextColor(status: MetadataEnrichmentStatus): string | undefined {
      return status === 'WAITING' ? undefined : 'white'
    },
    formatDate(value: string | null): string {
      if (!value) return '—'
      const normalized = /(?:Z|[+-]\d\d:\d\d)$/.test(value) ? value : `${value}Z`
      const date = new Date(normalized)
      return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(this.$i18n.locale, {dateStyle: 'medium', timeStyle: 'short'}).format(date)
    },
    errorMessage(error: any, fallback: string): string {
      return error?.response?.data?.message || error?.message || fallback
    },
    notify(text: string, color: string) {
      this.snackbarText = text
      this.snackbarColor = color
      this.snackbar = true
    },
  },
})
</script>

<style scoped>
.enrichment-page {
  max-width: 1600px;
}

.section-copy {
  max-width: 72ch;
}

.error-detail {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 0.875rem;
  line-height: 1.5;
}

.ga-2 {
  gap: 8px;
}

@media (prefers-reduced-motion: reduce) {
  * {
    scroll-behavior: auto !important;
  }
}
</style>
