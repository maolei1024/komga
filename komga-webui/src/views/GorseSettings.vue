<template>
  <v-container fluid class="pa-6">
    <v-row>
      <v-col><span class="text-h5">Gorse Recommendation Settings</span></v-col>
    </v-row>

    <!-- Settings Section -->
    <v-row>
      <v-col cols="12" md="6">
        <v-card outlined>
          <v-card-title>Configuration</v-card-title>
          <v-card-text>
            <v-switch
              v-model="form.enabled"
              @change="formDirty = true"
              label="Enable Gorse Integration"
              hint="Enable automatic sync of series and read progress to Gorse"
              persistent-hint
              class="mb-4"
            />

            <v-text-field
              v-model="form.apiUrl"
              @input="formDirty = true"
              label="Gorse API URL"
              hint="e.g. http://localhost:8087"
              persistent-hint
              :rules="[rules.required]"
              class="mb-4"
            />

            <v-text-field
              v-model="form.apiKey"
              @input="formDirty = true"
              label="Gorse API Key"
              :type="showApiKey ? 'text' : 'password'"
              :append-icon="showApiKey ? 'mdi-eye' : 'mdi-eye-off'"
              @click:append="showApiKey = !showApiKey"
              hint="API key for Gorse authentication"
              persistent-hint
              class="mb-4"
            />

            <v-text-field
              v-model="form.feedbackType"
              @input="formDirty = true"
              label="Feedback Type"
              hint="The feedback type sent to Gorse when a book is completed (default: read)"
              persistent-hint
              :rules="[rules.required]"
            />
          </v-card-text>
          <v-card-actions>
            <v-spacer/>
            <v-btn
              text
              :disabled="!formDirty"
              @click="refreshSettings"
            >Discard
            </v-btn>
            <v-btn
              color="primary"
              :disabled="!formDirty"
              :loading="saving"
              @click="saveSettings"
            >Save Changes
            </v-btn>
          </v-card-actions>
        </v-card>
      </v-col>
    </v-row>

    <!-- Batch Sync Section -->
    <v-row class="mt-4">
      <v-col cols="12" md="6">
        <v-card outlined>
          <v-card-title>Batch Sync</v-card-title>
          <v-card-subtitle>
            Manually sync all data to Gorse. Use this for initial setup or to re-sync after changes.
          </v-card-subtitle>
          <v-card-text>
            <v-list>
              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>Sync Series → Items</v-list-item-title>
                  <v-list-item-subtitle>
                    Sync all manga series as Gorse items with tags and genres as labels.
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
                    Sync
                  </v-btn>
                </v-list-item-action>
              </v-list-item>

              <v-divider/>

              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>Sync Users</v-list-item-title>
                  <v-list-item-subtitle>
                    Sync all Komga users to Gorse.
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
                    Sync
                  </v-btn>
                </v-list-item-action>
              </v-list-item>

              <v-divider/>

              <v-list-item>
                <v-list-item-content>
                  <v-list-item-title>Sync Read Progress → Feedback</v-list-item-title>
                  <v-list-item-subtitle>
                    Sync completed read progress as Gorse feedback (type: {{ form.feedbackType }}).
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
                    Sync
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
        <v-btn text v-bind="attrs" @click="snackbar = false">Close</v-btn>
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
      required: (value: string) => !!value || 'Required.',
    },
  }),
  mounted() {
    this.refreshSettings()
  },
  methods: {
    async refreshSettings() {
      try {
        const settings = await this.$komgaGorse.getSettings()
        this.form = {...settings}
        this.formDirty = false
      } catch (e) {
        this.showSnackbar('Failed to load Gorse settings', 'error')
      }
    },
    async saveSettings() {
      this.saving = true
      try {
        await this.$komgaGorse.updateSettings(this.form)
        await this.refreshSettings()
        this.showSnackbar('Settings saved successfully', 'success')
      } catch (e) {
        this.showSnackbar('Failed to save settings', 'error')
      } finally {
        this.saving = false
      }
    },
    async syncItems() {
      this.syncingItems = true
      try {
        const result = await this.$komgaGorse.syncItems()
        this.showSnackbar(`Synced ${result.count} items to Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('Failed to sync items', 'error')
      } finally {
        this.syncingItems = false
      }
    },
    async syncUsers() {
      this.syncingUsers = true
      try {
        const result = await this.$komgaGorse.syncUsers()
        this.showSnackbar(`Synced ${result.count} users to Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('Failed to sync users', 'error')
      } finally {
        this.syncingUsers = false
      }
    },
    async syncFeedback() {
      this.syncingFeedback = true
      try {
        const result = await this.$komgaGorse.syncFeedback()
        this.showSnackbar(`Synced ${result.count} feedback entries to Gorse`, 'success')
      } catch (e) {
        this.showSnackbar('Failed to sync feedback', 'error')
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
