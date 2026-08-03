<template>
  <v-container fluid class="dedup-page pa-4 pa-md-6">
    <div class="d-flex flex-column flex-md-row align-md-start justify-space-between mb-5">
      <div class="dedup-heading">
        <h1 class="text-h5 text-md-h4 font-weight-medium mb-1">{{ $t('dedup_management.title') }}</h1>
        <p class="text-body-2 text--secondary mb-0">{{ $t('dedup_management.subtitle') }}</p>
      </div>
      <div class="d-flex mt-4 mt-md-0">
        <v-btn text :loading="loading" @click="refreshAll">
          <v-icon left>mdi-refresh</v-icon>
          {{ $t('dedup_management.actions.refresh') }}
        </v-btn>
        <v-btn color="primary" class="ml-2" :loading="scanning" @click="requestScan">
          <v-icon left>mdi-radar</v-icon>
          {{ $t('dedup_management.actions.scan') }}
        </v-btn>
      </div>
    </div>

    <v-sheet outlined class="status-strip mb-5" aria-live="polite">
      <div v-if="loadingStatus" class="pa-4">
        <v-skeleton-loader type="text@4"/>
      </div>
      <div v-else class="status-grid">
        <div v-for="item in statusItems" :key="item.label" class="status-item">
          <div class="text-h6 font-weight-medium">{{ item.value }}</div>
          <div class="text-caption text--secondary">{{ item.label }}</div>
        </div>
      </div>
    </v-sheet>

    <v-tabs v-model="tab" show-arrows class="mb-4">
      <v-tab>{{ $t('dedup_management.tabs.cases') }}</v-tab>
      <v-tab>{{ $t('dedup_management.tabs.settings') }}</v-tab>
      <v-tab>{{ $t('dedup_management.tabs.tasks') }}</v-tab>
    </v-tabs>

    <v-tabs-items v-model="tab" class="transparent">
      <v-tab-item>
        <v-sheet outlined class="filter-bar pa-3 mb-4">
          <div class="d-flex flex-column flex-md-row align-md-center">
            <v-select
              v-model="filters.libraryId"
              :items="libraryFilterItems"
              :label="$t('dedup_management.filters.library')"
              clearable
              dense
              outlined
              hide-details
              class="filter-control"
              :disabled="bulkVerifying || loadingCases"
              @change="onFiltersChanged"
            />
            <v-select
              v-model="filters.origin"
              :items="originItems"
              :label="$t('dedup_management.filters.source')"
              clearable
              dense
              outlined
              hide-details
              class="filter-control mt-3 mt-md-0 ml-md-3"
              :disabled="bulkVerifying || loadingCases"
              @change="onFiltersChanged"
            />
            <v-spacer/>
            <span class="text-caption text--secondary mt-3 mt-md-0 mr-md-2">
              {{ $t('dedup_management.case_count', {count: totalCases}) }}
            </span>
            <page-size-select
              v-model="casePageSize"
              :items="[20, 50, 100]"
              :disabled="bulkVerifying || loadingCases"
            />
            <v-btn
              outlined
              color="primary"
              class="mt-3 mt-md-0 ml-md-2"
              :disabled="bulkVerifying || loadingCases || bulkVerificationRequests.length === 0"
              :loading="bulkVerifying"
              @click="openBulkVerificationDialog"
            >
              <v-icon left>mdi-file-compare</v-icon>
              {{ $t('dedup_management.actions.verify_page', {count: bulkVerificationRequests.length}) }}
            </v-btn>
          </div>
        </v-sheet>

        <v-pagination
          v-if="casePageCount > 1"
          v-model="casePage"
          :length="casePageCount"
          :total-visible="paginationVisible"
          :disabled="bulkVerifying || loadingCases"
          class="mb-5"
          @input="loadCases"
        />

        <div v-if="loadingCases">
          <v-skeleton-loader v-for="index in 3" :key="index" type="list-item-avatar-three-line" class="mb-3"/>
        </div>

        <v-sheet v-else-if="cases.length === 0" outlined class="empty-state pa-8 text-center">
          <v-icon size="44" color="primary" class="mb-3">mdi-check-decagram-outline</v-icon>
          <h2 class="text-h6 mb-2">{{ $t('dedup_management.empty.title') }}</h2>
          <p class="text-body-2 text--secondary mx-auto mb-4">{{ $t('dedup_management.empty.body') }}</p>
          <v-btn outlined color="primary" @click="requestScan">{{ $t('dedup_management.actions.scan') }}</v-btn>
        </v-sheet>

        <v-expansion-panels v-else accordion flat focusable>
          <v-expansion-panel v-for="reviewCase in cases" :key="reviewCase.id" class="case-panel mb-3">
            <v-expansion-panel-header>
              <div class="d-flex flex-column flex-sm-row align-sm-center case-summary">
                <div class="cover-stack mr-sm-4 mb-3 mb-sm-0" aria-hidden="true">
                  <v-img
                    v-for="member in reviewCase.members.slice(0, 3)"
                    :key="member.bookId"
                    :src="thumbnailUrl(member.bookId)"
                    width="44"
                    height="62"
                    contain
                    class="cover-thumb"
                  />
                </div>
                <div class="flex-grow-1 min-width-0">
                  <div class="d-flex flex-wrap align-center mb-1">
                    <v-chip small label :color="relationColor(reviewCase.relationType)" class="mr-2 mb-1">
                      {{ relationLabel(reviewCase.relationType) }}
                    </v-chip>
                    <span v-if="reviewCase.coverDistance != null" class="text-caption text--secondary mb-1">
                      {{ $t('dedup_management.cover_distance', {distance: reviewCase.coverDistance}) }}
                    </span>
                  </div>
                  <div class="text-subtitle-1 font-weight-medium text-truncate">
                    {{ memberTitles(reviewCase) }}
                  </div>
                  <div class="text-caption text--secondary">
                    {{ libraryName(reviewCase.libraryId) }} · {{ reviewCase.members.length }}
                    {{ $t('dedup_management.members') }} · {{ formatDate(reviewCase.lastModified) }}
                  </div>
                </div>
                <v-chip
                  small
                  outlined
                  :color="reviewCase.eligibility.blockers.length ? 'error' : 'success'"
                  class="mt-3 mt-sm-0 mr-sm-3"
                >
                  {{ reviewCase.eligibility.blockers.length
                    ? $t('dedup_management.blocker_count', {count: reviewCase.eligibility.blockers.length})
                    : $t('dedup_management.ready') }}
                </v-chip>
              </div>
            </v-expansion-panel-header>

            <v-expansion-panel-content>
              <v-divider class="mb-4"/>
              <div class="member-grid mb-5">
                <v-sheet v-for="member in reviewCase.members" :key="member.bookId" outlined class="member-row pa-3">
                  <v-img :src="thumbnailUrl(member.bookId)" width="72" height="102" contain class="member-cover"/>
                  <div class="min-width-0">
                    <router-link
                      v-if="member.book"
                      :to="bookRoute(member)"
                      class="text-subtitle-2 font-weight-medium text-decoration-none"
                    >
                      {{ member.book.seriesTitle }} — {{ member.book.name }}
                    </router-link>
                    <div v-else class="text-subtitle-2">{{ member.bookId }}</div>
                    <div v-if="member.book" class="text-caption text--secondary text-truncate mt-1">
                      {{ member.book.url }}
                    </div>
                    <div v-if="member.book" class="text-body-2 mt-2">
                      {{ member.book.size }} · {{ member.book.media.pagesCount }} {{ $t('dedup_management.pages') }}
                    </div>
                    <v-chip v-if="!member.inMvpScope" x-small label color="warning" class="mt-2">
                      {{ $t('dedup_management.out_of_scope', {count: member.activeBookCountInSeries}) }}
                    </v-chip>
                    <v-chip
                      v-if="reviewCase.suggestedKeeperBookId === member.bookId"
                      x-small label color="primary" class="mt-2 ml-1"
                    >
                      {{ $t('dedup_management.keeper') }}
                    </v-chip>
                    <div class="d-flex flex-wrap mt-2">
                      <v-btn
                        x-small text color="primary"
                        :loading="caseActionId === `${reviewCase.id}-keeper-${member.bookId}`"
                        @click="setKeeper(reviewCase, member.bookId)"
                      >
                        {{ $t('dedup_management.actions.set_keeper') }}
                      </v-btn>
                      <v-btn
                        x-small text color="warning"
                        :loading="caseActionId === `${reviewCase.id}-protect-${member.bookId}`"
                        @click="applyOverride(reviewCase, 'PROTECTED', member.bookId)"
                      >
                        {{ $t('dedup_management.actions.protect') }}
                      </v-btn>
                    </div>
                  </div>
                </v-sheet>
              </div>

              <v-sheet v-if="hasPageEvidence(reviewCase)" outlined class="evidence-row pa-3 mb-5">
                <div>
                  <div class="text-caption text--secondary">{{ $t('dedup_management.evidence.coverage_left') }}</div>
                  <div class="text-subtitle-2">{{ percent(reviewCase.coverageLeft) }}</div>
                </div>
                <div>
                  <div class="text-caption text--secondary">{{ $t('dedup_management.evidence.coverage_right') }}</div>
                  <div class="text-subtitle-2">{{ percent(reviewCase.coverageRight) }}</div>
                </div>
                <div>
                  <div class="text-caption text--secondary">{{ $t('dedup_management.evidence.longest_run') }}</div>
                  <div class="text-subtitle-2">{{ optionalNumber(reviewCase.longestMatchedRun) }}</div>
                </div>
                <div>
                  <div class="text-caption text--secondary">{{ $t('dedup_management.evidence.unmatched') }}</div>
                  <div class="text-subtitle-2">
                    {{ optionalNumber(unmatchedPageCount(reviewCase)) }}
                  </div>
                </div>
              </v-sheet>
              <v-alert
                v-else-if="reviewCase.origin !== 'EXACT_FILE'"
                type="info"
                text
                dense
                class="mb-5"
              >
                {{ $t('dedup_management.evidence.not_analyzed') }}
              </v-alert>

              <section :aria-label="$t('dedup_management.eligibility.title').toString()">
                <h3 class="text-subtitle-1 font-weight-medium mb-3">{{ $t('dedup_management.eligibility.title') }}</h3>
                <v-alert
                  v-for="reason in groupedReasons(reviewCase)"
                  :key="reason.key"
                  dense
                  outlined
                  :type="reason.severity === 'BLOCKER' ? 'error' : 'warning'"
                  class="reason-alert"
                >
                  <div class="font-weight-medium">{{ reasonLabel(reason.code) }}</div>
                  <div class="d-flex flex-wrap mt-1">
                    <v-chip
                      v-for="effect in reason.effects"
                      :key="effect"
                      x-small
                      outlined
                      class="mr-1 mb-1"
                    >
                      {{ reasonEffectLabel(effect) }}
                    </v-chip>
                  </div>
                  <div v-if="reasonDetails(reason)" class="text-body-2 mt-1">{{ reasonDetails(reason) }}</div>
                  <div v-if="reason.pageRanges.length" class="text-caption mt-1">
                    {{ $t('dedup_management.eligibility.page_ranges') }}: {{ reason.pageRanges.join(' · ') }}
                  </div>
                  <div v-if="reason.actions.length" class="mt-1">
                    <span class="text-caption text--secondary mr-2">{{ $t('dedup_management.eligibility.next_action') }}:</span>
                    <span v-for="action in reason.actions" :key="action" class="reason-action">
                      <v-btn
                        v-if="reasonActionClickable(action, reason, reviewCase)"
                        x-small
                        text
                        color="primary"
                        class="px-0 mr-3"
                        :disabled="bulkVerifying"
                        :loading="reasonActionLoading(action, reviewCase)"
                        @click="runReasonAction(action, reviewCase, reason)"
                      >
                        {{ reasonActionLabel(action) }}
                      </v-btn>
                      <span v-else class="text-caption text--secondary mr-3">{{ reasonActionLabel(action) }}</span>
                    </span>
                  </div>
                </v-alert>
              </section>

              <div class="d-flex flex-column flex-sm-row justify-end mt-4">
                <v-btn
                  v-if="hasPageEvidence(reviewCase)"
                  text
                  :loading="caseActionId === `${reviewCase.id}-pages`"
                  @click="openPageComparison(reviewCase)"
                >
                  {{ $t('dedup_management.actions.compare_pages') }}
                </v-btn>
                <v-btn
                  v-if="reviewCase.origin !== 'EXACT_FILE'"
                  text
                  :loading="caseActionId === `${reviewCase.id}-reanalyze`"
                  @click="reanalyze(reviewCase)"
                >
                  {{ hasPageEvidence(reviewCase)
                    ? $t('dedup_management.actions.reverify')
                    : $t('dedup_management.actions.verify_now') }}
                </v-btn>
                <v-btn
                  text
                  :disabled="reviewCase.members.length !== 2 || reviewCase.relationType === 'EXACT_FILE'"
                  :loading="caseActionId === `${reviewCase.id}-UNRELATED`"
                  @click="applyOverride(reviewCase, 'UNRELATED')"
                >
                  {{ $t('dedup_management.actions.unrelated') }}
                </v-btn>
                <v-btn
                  text
                  :disabled="reviewCase.members.length !== 2 || reviewCase.relationType === 'EXACT_FILE'"
                  :loading="caseActionId === `${reviewCase.id}-ALT_EDITION`"
                  @click="applyOverride(reviewCase, 'ALT_EDITION')"
                >
                  {{ $t('dedup_management.actions.alt_edition') }}
                </v-btn>
                <v-btn
                  outlined
                  class="mb-2 mb-sm-0"
                  :disabled="!reviewCase.eligibility.manualDeleteEligible || !reviewCase.suggestedKeeperBookId"
                  @click="openDecisionDialog(reviewCase, 'MANUAL')"
                >
                  {{ $t('dedup_management.actions.custom') }}
                </v-btn>
                <v-btn
                  color="primary"
                  class="ml-sm-2"
                  :disabled="!reviewCase.eligibility.suggestedPlanEligible"
                  @click="openDecisionDialog(reviewCase, 'SUGGESTED')"
                >
                  {{ reviewCase.eligibility.suggestedPlanEligible
                    ? $t('dedup_management.actions.accept')
                    : $t('dedup_management.actions.accept_blocked', {count: blockerCount(reviewCase, 'SUGGESTED')}) }}
                </v-btn>
              </div>
            </v-expansion-panel-content>
          </v-expansion-panel>
        </v-expansion-panels>

        <v-pagination
          v-if="casePageCount > 1"
          v-model="casePage"
          :length="casePageCount"
          :total-visible="paginationVisible"
          :disabled="bulkVerifying || loadingCases"
          class="mt-5"
          @input="loadCases"
        />
      </v-tab-item>

      <v-tab-item>
        <v-alert type="info" text class="mb-4">{{ $t('dedup_management.settings.calibration_notice') }}</v-alert>
        <div v-if="loadingSettings">
          <v-skeleton-loader type="article, article"/>
        </div>
        <v-form v-else ref="settingsForm" v-model="settingsValid" @submit.prevent="saveSettings">
          <v-sheet
            v-for="library in settings.libraries"
            :key="library.libraryId"
            outlined
            class="settings-section pa-4 mb-4"
          >
            <div class="d-flex flex-column flex-sm-row align-sm-center mb-3">
              <div class="flex-grow-1">
                <h2 class="text-subtitle-1 font-weight-medium">{{ libraryName(library.libraryId) }}</h2>
                <div class="text-caption text--secondary">{{ library.libraryId }}</div>
              </div>
              <div class="d-flex align-center mt-2 mt-sm-0">
                <v-switch
                  v-model="library.enabled"
                  :label="$t('dedup_management.settings.enabled')"
                  hide-details
                  class="mt-0 mr-4"
                  @change="settingsDirty = true"
                />
                <v-switch
                  v-model="library.paused"
                  :label="$t('dedup_management.settings.paused')"
                  :disabled="!library.enabled"
                  hide-details
                  class="mt-0"
                  @change="settingsDirty = true"
                />
              </div>
            </div>
            <v-row>
              <v-col cols="12" sm="6" lg="3">
                <v-select
                  v-model="library.scanInterval"
                  :items="intervalItems"
                  :label="$t('dedup_management.settings.interval')"
                  outlined dense
                  :disabled="!library.enabled"
                  @change="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.batchSize"
                  type="number" min="1"
                  :label="$t('dedup_management.settings.batch_size')"
                  outlined dense
                  :rules="[positiveRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.maxDurationSeconds"
                  type="number" min="1"
                  :label="$t('dedup_management.settings.max_duration')"
                  suffix="s"
                  outlined dense
                  :rules="[positiveRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.quietPeriodSeconds"
                  type="number" min="0"
                  :label="$t('dedup_management.settings.quiet_period')"
                  suffix="s"
                  outlined dense
                  :rules="[nonNegativeRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.completionStabilitySeconds"
                  type="number" min="0"
                  :label="$t('dedup_management.settings.stability_period')"
                  suffix="s"
                  outlined dense
                  :rules="[nonNegativeRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.coverCandidateDistance"
                  type="number" min="0" max="256"
                  :label="$t('dedup_management.settings.cover_distance')"
                  outlined dense
                  :rules="[hashDistanceRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
              <v-col cols="12" sm="6" lg="3">
                <v-text-field
                  v-model.number="library.coverTopK"
                  type="number" min="1"
                  :label="$t('dedup_management.settings.top_k')"
                  outlined dense
                  :rules="[positiveRule]"
                  :disabled="!library.enabled"
                  @input="settingsDirty = true"
                />
              </v-col>
            </v-row>
          </v-sheet>
          <div class="d-flex justify-end">
            <v-btn text :disabled="!settingsDirty" @click="loadSettings">{{ $t('common.cancel') }}</v-btn>
            <v-btn
              type="submit"
              color="primary"
              class="ml-2"
              :disabled="!settingsDirty || !settingsValid"
              :loading="savingSettings"
            >
              {{ $t('common.save_changes') }}
            </v-btn>
          </div>
        </v-form>
      </v-tab-item>

      <v-tab-item>
        <v-sheet outlined class="pa-4">
          <h2 class="text-h6 mb-1">{{ $t('dedup_management.tasks.title') }}</h2>
          <p class="text-body-2 text--secondary mb-4">{{ $t('dedup_management.tasks.subtitle') }}</p>
          <div class="task-grid">
            <div v-for="item in workItems" :key="item.state" class="task-row">
              <v-icon :color="workColor(item.state)" class="mr-3">{{ workIcon(item.state) }}</v-icon>
              <div class="flex-grow-1">
                <div class="text-body-2 font-weight-medium">{{ workLabel(item.state) }}</div>
              </div>
              <span class="text-h6">{{ item.count }}</span>
            </div>
          </div>
          <v-alert v-if="(status.work.FAILED_REVIEW || 0) > 0" type="warning" text class="mt-4 mb-0">
            {{ $t('dedup_management.tasks.failed_help') }}
          </v-alert>
          <v-divider class="my-5"/>
          <h3 class="text-subtitle-1 font-weight-medium mb-3">{{ $t('dedup_management.tasks.audit_title') }}</h3>
          <div v-if="recentDecisions.length" class="decision-audit-list">
            <div v-for="decision in recentDecisions" :key="decision.id" class="decision-audit-row py-3">
              <div class="min-width-0">
                <div class="text-body-2 font-weight-medium text-truncate">{{ decision.id }}</div>
                <div class="text-caption text--secondary">
                  {{ decision.mode }} · {{ decision.items.length }} {{ $t('dedup_management.members') }} ·
                  {{ $t('dedup_management.tasks.gorse', {state: decision.gorseSyncState}) }}
                </div>
                <div v-if="decision.items.some(item => item.resultCode)" class="text-caption mt-1">
                  {{ decision.items.map(item => item.resultCode).filter(Boolean).join(' · ') }}
                </div>
              </div>
              <div class="d-flex align-center">
                <v-btn
                  v-if="decision.state === 'NEEDS_ATTENTION'"
                  x-small
                  text
                  color="primary"
                  :loading="caseActionId === `${decision.id}-retry`"
                  @click="retryDecision(decision.id)"
                >
                  {{ $t('dedup_management.actions.retry_decision') }}
                </v-btn>
                <v-chip small label :color="decisionStateColor(decision.state)">{{ decision.state }}</v-chip>
              </div>
            </div>
          </div>
          <p v-else class="text-body-2 text--secondary mb-0">{{ $t('dedup_management.tasks.no_decisions') }}</p>
        </v-sheet>
      </v-tab-item>
    </v-tabs-items>

    <v-dialog v-model="decisionDialog.show" max-width="720" persistent>
      <v-card>
        <v-card-title>{{ decisionDialog.mode === 'SUGGESTED'
          ? $t('dedup_management.decision.suggested_title')
          : $t('dedup_management.decision.custom_title') }}</v-card-title>
        <v-card-text v-if="decisionDialog.reviewCase">
          <v-alert type="error" outlined>
            <div class="font-weight-medium">{{ $t('dedup_management.decision.irreversible_title') }}</div>
            <div>{{ $t('dedup_management.decision.irreversible_body') }}</div>
          </v-alert>
          <v-select
            v-if="decisionDialog.mode === 'MANUAL'"
            v-model="decisionDialog.keeperBookId"
            :items="decisionMemberOptions"
            :label="$t('dedup_management.decision.keeper')"
            outlined
            @change="normalizeDecisionSelection"
          />
          <div class="text-subtitle-2 mb-2">{{ $t('dedup_management.decision.remove_members') }}</div>
          <v-checkbox
            v-for="member in removableDecisionMembers"
            :key="member.bookId"
            v-model="decisionDialog.removeBookIds"
            :value="member.bookId"
            :label="member.book ? `${member.book.seriesTitle} — ${member.book.name}` : member.bookId"
            :disabled="decisionDialog.mode === 'SUGGESTED'"
            hide-details
            class="mt-1"
          />
          <v-alert type="warning" text class="mt-4">
            {{ $t('dedup_management.decision.partial_warning') }}
          </v-alert>
          <div v-if="manualConfirmationReasons.length" class="mt-4">
            <div class="text-subtitle-2 mb-2">{{ $t('dedup_management.decision.acknowledge_title') }}</div>
            <v-checkbox
              v-for="reason in manualConfirmationReasons"
              :key="reason.code"
              v-model="decisionDialog.acknowledgedReasonCodes"
              :value="reason.code"
              :label="reasonLabel(reason.code)"
              hide-details
              class="mt-1"
            />
          </div>
          <v-checkbox
            v-model="decisionDialog.irreversibleAcknowledged"
            :label="$t('dedup_management.decision.irreversible_acknowledgement')"
            class="mt-5"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer/>
          <v-btn text :disabled="decisionSubmitting" @click="closeDecisionDialog">{{ $t('common.cancel') }}</v-btn>
          <v-btn color="error" :loading="decisionSubmitting" :disabled="!decisionCanSubmit" @click="submitDecision">
            {{ $t('dedup_management.decision.approve_and_execute') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="bulkDialog.show" max-width="640" persistent>
      <v-card>
        <v-card-title>{{ $t('dedup_management.bulk_verify.title') }}</v-card-title>
        <v-card-text>
          <v-alert type="info" text>
            {{ $t('dedup_management.bulk_verify.body', {count: bulkDialog.cases.length}) }}
          </v-alert>
          <div class="text-subtitle-2 mb-2">{{ $t('dedup_management.bulk_verify.snapshot') }}</div>
          <v-list dense outlined class="bulk-case-list">
            <v-list-item v-for="item in bulkDialog.cases" :key="item.caseId">
              <v-list-item-content>
                <v-list-item-title>{{ item.label }}</v-list-item-title>
                <v-list-item-subtitle>{{ item.caseId }} · revision {{ item.expectedRevision }}</v-list-item-subtitle>
              </v-list-item-content>
            </v-list-item>
          </v-list>
        </v-card-text>
        <v-card-actions>
          <v-spacer/>
          <v-btn text :disabled="bulkDialog.submitting" @click="bulkDialog.show = false">{{ $t('common.cancel') }}</v-btn>
          <v-btn color="primary" :loading="bulkDialog.submitting" @click="submitBulkVerification">
            {{ $t('dedup_management.bulk_verify.confirm', {count: bulkDialog.cases.length}) }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="pageDialog.show" max-width="1100">
      <v-card>
        <v-card-title>{{ $t('dedup_management.page_comparison.title') }}</v-card-title>
        <v-card-text>
          <v-skeleton-loader v-if="pageDialog.loading" type="image, image"/>
          <v-alert v-else-if="!pageDialog.comparison" type="info" text>
            {{ $t('dedup_management.page_comparison.no_evidence') }}
          </v-alert>
          <div v-else class="page-comparison-grid">
            <section v-for="(pages, bookId) in pageDialog.comparison.pages" :key="bookId">
              <h3 class="text-subtitle-2 mb-3">{{ pageBookTitle(bookId) }}</h3>
              <div class="page-evidence-list">
                <div v-for="page in pages" :key="`${bookId}-${page.pageNumber}`" class="page-evidence-item">
                  <v-img :src="pageThumbnailUrl(page)" width="96" height="136" contain/>
                  <div class="text-caption mt-1">
                    #{{ page.pageNumber }}
                    <span v-if="page.matchedPageNumber">→ #{{ page.matchedPageNumber }}</span>
                  </div>
                  <v-chip x-small :color="page.matchedPageNumber ? (page.exactMatch ? 'success' : 'warning') : 'error'">
                    {{ page.matchedPageNumber
                      ? (page.exactMatch ? $t('dedup_management.page_comparison.exact') : $t('dedup_management.page_comparison.perceptual'))
                      : $t('dedup_management.page_comparison.unmatched') }}
                  </v-chip>
                </div>
              </div>
            </section>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer/>
          <v-btn text @click="pageDialog.show = false">{{ $t('common.close') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar v-model="snackbar.show" :color="snackbar.color" :timeout="4000">
      {{ snackbar.text }}
      <template v-slot:action="{attrs}">
        <v-btn text v-bind="attrs" @click="snackbar.show = false">{{ $t('common.close') }}</v-btn>
      </template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import {formatDistanceToNow} from 'date-fns'
import PageSizeSelect from '@/components/PageSizeSelect.vue'
import {
  currentPageVerificationRequests,
  DedupEligibilityDisplayReason,
  DedupEligibilityEffect,
  hasPageEvidence,
  mergeEligibilityReasons,
  unmatchedPageCount,
} from '@/functions/dedup'
import {bookPageThumbnailUrl, bookThumbnailUrl} from '@/functions/urls'
import {
  DedupCaseOrigin,
  DedupCaseVerificationRequestDto,
  DedupEligibilityReasonDto,
  DedupDecisionDto,
  DedupPageEvidenceDto,
  DedupPageComparisonDto,
  DedupReviewCaseDto,
  DedupReviewCaseMemberDto,
  DedupSettingsDto,
  DedupStatusDto,
} from '@/types/komga-dedup'

interface DedupBulkCaseSnapshot extends DedupCaseVerificationRequestDto {
  label: string
}

const EMPTY_STATUS: DedupStatusDto = {
  work: {},
  decisions: {},
  decisionItems: {},
  gorseSync: {},
  enabledLibraries: 0,
  pausedLibraries: 0,
  reviewCases: 0,
  exactFileCases: 0,
}

export default Vue.extend({
  name: 'DedupManagement',
  components: {PageSizeSelect},
  data: () => ({
    tab: 0,
    status: {...EMPTY_STATUS} as DedupStatusDto,
    settings: {libraries: []} as DedupSettingsDto,
    cases: [] as DedupReviewCaseDto[],
    recentDecisions: [] as DedupDecisionDto[],
    totalCases: 0,
    casePage: 1,
    filters: {
      libraryId: undefined as string | undefined,
      origin: undefined as DedupCaseOrigin | undefined,
    },
    loadingStatus: true,
    loadingSettings: true,
    loadingCases: true,
    scanning: false,
    savingSettings: false,
    settingsDirty: false,
    settingsValid: true,
    caseActionId: '',
    decisionSubmitting: false,
    decisionDialog: {
      show: false,
      mode: 'SUGGESTED' as 'SUGGESTED' | 'MANUAL',
      reviewCase: undefined as DedupReviewCaseDto | undefined,
      keeperBookId: '',
      removeBookIds: [] as string[],
      acknowledgedReasonCodes: [] as string[],
      irreversibleAcknowledged: false,
    },
    pageDialog: {
      show: false,
      loading: false,
      reviewCase: undefined as DedupReviewCaseDto | undefined,
      comparison: undefined as DedupPageComparisonDto | undefined,
    },
    bulkDialog: {
      show: false,
      submitting: false,
      cases: [] as DedupBulkCaseSnapshot[],
    },
    snackbar: {show: false, text: '', color: 'success'},
  }),
  computed: {
    casePageSize: {
      get(): number {
        const value = this.$store.state.persistedState.dedupCasePageSize
        return [20, 50, 100].includes(value) ? value : 20
      },
      set(value: number) {
        this.$store.commit('setDedupCasePageSize', value)
        this.casePage = 1
        this.loadCases()
      },
    },
    loading(): boolean {
      return this.loadingStatus || this.loadingSettings || this.loadingCases
    },
    statusItems(): Array<{label: string; value: number}> {
      return [
        {label: this.$t('dedup_management.status.review_cases').toString(), value: this.status.reviewCases},
        {label: this.$t('dedup_management.status.pending').toString(), value: (this.status.work.WAITING || 0) + (this.status.work.PENDING || 0)},
        {label: this.$t('dedup_management.status.running').toString(), value: (this.status.work.RUNNING || 0) + (this.status.decisions.PURGING || 0)},
        {
          label: this.$t('dedup_management.status.failed').toString(),
          value: (this.status.work.FAILED_REVIEW || 0) + (this.status.decisions.NEEDS_ATTENTION || 0) +
            (this.status.decisions.PARTIALLY_COMPLETED || 0) + (this.status.decisions.REAPPROVAL_REQUIRED || 0) +
            (this.status.decisionItems.CONFLICT || 0) + (this.status.decisionItems.REAPPEARED || 0),
        },
      ]
    },
    workItems(): Array<{state: string; count: number}> {
      return ['WAITING', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED_REVIEW'].map(state => ({
        state,
        count: this.status.work[state] || 0,
      }))
    },
    libraryFilterItems(): Array<{text: string; value: string}> {
      return this.settings.libraries.map(item => ({text: this.libraryName(item.libraryId), value: item.libraryId}))
    },
    originItems(): Array<{text: string; value: DedupCaseOrigin}> {
      return [
        {text: this.$t('dedup_management.relations.EXACT_FILE').toString(), value: 'EXACT_FILE'},
        {text: this.$t('dedup_management.relations.VISUALLY_SIMILAR').toString(), value: 'COVER_SIMILARITY'},
      ]
    },
    intervalItems(): Array<{text: string; value: string}> {
      return ['HOURLY', 'EVERY_6H', 'EVERY_12H', 'DAILY', 'WEEKLY'].map(value => ({
        text: this.$t(`dedup_management.intervals.${value}`).toString(),
        value,
      }))
    },
    casePageCount(): number {
      return Math.ceil(this.totalCases / this.casePageSize)
    },
    paginationVisible(): number {
      switch (this.$vuetify.breakpoint.name) {
        case 'xs':
        case 'sm':
        case 'md':
          return 5
        case 'lg':
          return 10
        default:
          return 15
      }
    },
    bulkVerificationRequests(): DedupCaseVerificationRequestDto[] {
      return currentPageVerificationRequests(this.cases)
    },
    bulkVerifying(): boolean {
      return this.bulkDialog.submitting
    },
    decisionMemberOptions(): Array<{text: string; value: string}> {
      return (this.decisionDialog.reviewCase?.members || []).map(member => ({
        text: member.book ? `${member.book.seriesTitle} — ${member.book.name}` : member.bookId,
        value: member.bookId,
      }))
    },
    removableDecisionMembers(): DedupReviewCaseMemberDto[] {
      return (this.decisionDialog.reviewCase?.members || []).filter(member => member.bookId !== this.decisionDialog.keeperBookId)
    },
    manualConfirmationReasons(): DedupEligibilityReasonDto[] {
      if (this.decisionDialog.mode !== 'MANUAL') return []
      return (this.decisionDialog.reviewCase?.eligibility.warnings || [])
        .filter(reason => reason.confirmationRequired && reason.appliesTo.includes('MANUAL'))
        .filter((reason, index, all) => all.findIndex(item => item.code === reason.code) === index)
    },
    decisionCanSubmit(): boolean {
      const required = this.manualConfirmationReasons.map(reason => reason.code)
      return this.decisionDialog.irreversibleAcknowledged &&
        !!this.decisionDialog.keeperBookId &&
        this.decisionDialog.removeBookIds.length > 0 &&
        required.every(code => this.decisionDialog.acknowledgedReasonCodes.includes(code)) &&
        !this.decisionSubmitting
    },
  },
  mounted() {
    this.refreshAll()
  },
  methods: {
    async refreshAll() {
      await Promise.all([this.loadStatus(), this.loadSettings(), this.loadCases(), this.loadDecisions()])
    },
    async loadStatus() {
      this.loadingStatus = true
      try {
        this.status = await this.$komgaDedup.getStatus()
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.status').toString(), 'error')
      } finally {
        this.loadingStatus = false
      }
    },
    async loadSettings() {
      this.loadingSettings = true
      try {
        this.settings = await this.$komgaDedup.getSettings()
        this.settingsDirty = false
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.settings').toString(), 'error')
      } finally {
        this.loadingSettings = false
      }
    },
    async loadCases() {
      this.loadingCases = true
      try {
        const page = await this.$komgaDedup.getCases({
          page: this.casePage - 1,
          size: this.casePageSize,
          library_id: this.filters.libraryId,
          origin: this.filters.origin,
        })
        if (page.totalPages > 0 && this.casePage > page.totalPages) {
          this.casePage = page.totalPages
          await this.loadCases()
          return
        }
        if (page.totalPages === 0) this.casePage = 1
        this.cases = page.content
        this.totalCases = page.totalElements
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.cases').toString(), 'error')
      } finally {
        this.loadingCases = false
      }
    },
    onFiltersChanged() {
      this.casePage = 1
      this.loadCases()
    },
    async loadDecisions() {
      try {
        this.recentDecisions = (await this.$komgaDedup.getDecisions(0, 20)).content
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.decisions').toString(), 'error')
      }
    },
    async requestScan() {
      this.scanning = true
      try {
        const libraryIds = this.filters.libraryId ? [this.filters.libraryId] : []
        const result = await this.$komgaDedup.requestScan(libraryIds)
        this.notify(this.$t('dedup_management.scan_requested', {count: result.requestedLibraries}).toString(), 'success')
        await this.loadStatus()
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.scan').toString(), 'error')
      } finally {
        this.scanning = false
      }
    },
    async saveSettings() {
      this.savingSettings = true
      try {
        this.settings = await this.$komgaDedup.updateSettings(this.settings)
        this.settingsDirty = false
        this.notify(this.$t('dedup_management.settings.saved').toString(), 'success')
        await this.loadStatus()
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.save').toString(), 'error')
      } finally {
        this.savingSettings = false
      }
    },
    async setKeeper(reviewCase: DedupReviewCaseDto, bookId: string) {
      this.caseActionId = `${reviewCase.id}-keeper-${bookId}`
      try {
        const updated = await this.$komgaDedup.setKeeper(reviewCase.id, reviewCase.revision, bookId)
        this.replaceCase(updated)
        this.notify(this.$t('dedup_management.actions.keeper_saved').toString(), 'success')
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.case_changed').toString(), 'error')
        await this.loadCases()
      } finally {
        this.caseActionId = ''
      }
    },
    async applyOverride(reviewCase: DedupReviewCaseDto, type: string, bookId?: string) {
      this.caseActionId = bookId ? `${reviewCase.id}-protect-${bookId}` : `${reviewCase.id}-${type}`
      try {
        const updated = await this.$komgaDedup.addOverride(reviewCase.id, {
          type,
          expectedRevision: reviewCase.revision,
          bookId,
        })
        this.replaceCase(updated)
        this.notify(this.$t('dedup_management.actions.override_saved').toString(), 'success')
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.case_changed').toString(), 'error')
        await this.loadCases()
      } finally {
        this.caseActionId = ''
      }
    },
    async reanalyze(reviewCase: DedupReviewCaseDto) {
      this.caseActionId = `${reviewCase.id}-reanalyze`
      try {
        const result = await this.$komgaDedup.verifyCases([{caseId: reviewCase.id, expectedRevision: reviewCase.revision}])
        if (result.queued === 1) {
          this.notify(this.$t('dedup_management.actions.verification_requested').toString(), 'success')
        } else {
          this.notify(this.$t('dedup_management.errors.case_changed').toString(), 'warning')
          await this.loadCases()
        }
        await this.loadStatus()
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.verify').toString(), 'error')
      } finally {
        this.caseActionId = ''
      }
    },
    openBulkVerificationDialog() {
      const requests = this.bulkVerificationRequests
      this.bulkDialog.cases = requests.map(request => {
        const reviewCase = this.cases.find(item => item.id === request.caseId)!
        return {...request, label: this.memberTitles(reviewCase)}
      })
      this.bulkDialog.show = true
    },
    async submitBulkVerification() {
      const cases = this.bulkDialog.cases.map(({caseId, expectedRevision}) => ({caseId, expectedRevision}))
      if (!cases.length) return
      this.bulkDialog.submitting = true
      try {
        const result = await this.$komgaDedup.verifyCases(cases)
        this.bulkDialog.show = false
        this.notify(this.$t('dedup_management.bulk_verify.result', result).toString(), result.stale || result.failed ? 'warning' : 'success')
        await this.loadStatus()
        if (result.stale || result.failed) await this.loadCases()
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.verify').toString(), 'error')
      } finally {
        this.bulkDialog.submitting = false
      }
    },
    openDecisionDialog(reviewCase: DedupReviewCaseDto, mode: 'SUGGESTED' | 'MANUAL') {
      const keeper = reviewCase.suggestedKeeperBookId || ''
      this.decisionDialog = {
        show: true,
        mode,
        reviewCase,
        keeperBookId: keeper,
        removeBookIds: reviewCase.members.filter(member => member.bookId !== keeper).map(member => member.bookId),
        acknowledgedReasonCodes: [],
        irreversibleAcknowledged: false,
      }
    },
    closeDecisionDialog() {
      this.decisionDialog.show = false
    },
    normalizeDecisionSelection() {
      this.decisionDialog.removeBookIds = this.decisionDialog.removeBookIds.filter(id => id !== this.decisionDialog.keeperBookId)
      if (this.decisionDialog.removeBookIds.length === 0) {
        this.decisionDialog.removeBookIds = this.removableDecisionMembers.map(member => member.bookId)
      }
    },
    async submitDecision() {
      const reviewCase = this.decisionDialog.reviewCase
      if (!reviewCase || !this.decisionCanSubmit) return
      this.decisionSubmitting = true
      try {
        const decision = this.decisionDialog.mode === 'SUGGESTED'
          ? await this.$komgaDedup.createSuggestedDecision(
            reviewCase.id,
            reviewCase.revision,
            reviewCase.eligibility.stateRevision,
          )
          : await this.$komgaDedup.createCustomDecision(reviewCase.id, {
            expectedRevision: reviewCase.revision,
            keeperBookId: this.decisionDialog.keeperBookId,
            removeBookIds: this.decisionDialog.removeBookIds,
            stateRevision: reviewCase.eligibility.stateRevision,
            acknowledgedReasonCodes: this.decisionDialog.acknowledgedReasonCodes,
          })
        const executing = await this.$komgaDedup.executeDecision(decision.id)
        this.notify(this.$t('dedup_management.decision.queued', {id: executing.id}).toString(), 'success')
        this.closeDecisionDialog()
        await Promise.all([this.loadCases(), this.loadStatus(), this.loadDecisions()])
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.decision').toString(), 'error')
        await this.loadCases()
      } finally {
        this.decisionSubmitting = false
      }
    },
    async openPageComparison(reviewCase: DedupReviewCaseDto) {
      this.caseActionId = `${reviewCase.id}-pages`
      this.pageDialog = {show: true, loading: true, reviewCase, comparison: undefined}
      try {
        this.pageDialog.comparison = await this.$komgaDedup.getPageComparison(reviewCase.id)
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.page_comparison').toString(), 'error')
      } finally {
        this.pageDialog.loading = false
        this.caseActionId = ''
      }
    },
    async retryDecision(decisionId: string) {
      this.caseActionId = `${decisionId}-retry`
      try {
        await this.$komgaDedup.executeDecision(decisionId)
        this.notify(this.$t('dedup_management.decision.retry_queued').toString(), 'success')
        await Promise.all([this.loadDecisions(), this.loadStatus()])
      } catch (e) {
        this.notify(this.$t('dedup_management.errors.decision').toString(), 'error')
      } finally {
        this.caseActionId = ''
      }
    },
    replaceCase(updated: DedupReviewCaseDto) {
      const index = this.cases.findIndex(item => item.id === updated.id)
      if (index >= 0) this.$set(this.cases, index, updated)
    },
    thumbnailUrl(bookId: string): string {
      return bookThumbnailUrl(bookId)
    },
    libraryName(libraryId: string): string {
      const library = this.$store.state.komgaLibraries.libraries.find((item: any) => item.id === libraryId)
      return library?.name || libraryId
    },
    memberTitles(reviewCase: DedupReviewCaseDto): string {
      return reviewCase.members.map(member => member.book?.seriesTitle || member.book?.name || member.bookId).join(' ↔ ')
    },
    relationLabel(type: string): string {
      return this.$t(`dedup_management.relations.${type}`).toString()
    },
    relationColor(type: string): string {
      if (['EXACT_FILE', 'EXACT_PAGE_SEQUENCE', 'CONTAINED_IN'].includes(type)) return 'success'
      if (['NEAR_CONTAINED_IN', 'PARTIAL_OVERLAP', 'EDITION_UNCERTAIN', 'ALT_EDITION'].includes(type)) return 'warning'
      return 'info'
    },
    reasonLabel(code: string): string {
      return this.$t(`dedup_management.reasons.${code}`).toString()
    },
    groupedReasons(reviewCase: DedupReviewCaseDto): DedupEligibilityDisplayReason[] {
      return mergeEligibilityReasons(reviewCase)
    },
    reasonEffectLabel(effect: DedupEligibilityEffect): string {
      return this.$t(`dedup_management.eligibility.effects.${effect}`).toString()
    },
    reasonDetails(reason: DedupEligibilityDisplayReason): string {
      const details: string[] = []
      if (reason.actual != null) details.push(`${this.$t('dedup_management.eligibility.actual')}: ${this.reasonValue(reason.actual)}`)
      if (reason.threshold != null) details.push(`${this.$t('dedup_management.eligibility.threshold')}: ${this.reasonValue(reason.threshold)}`)
      return details.join(' · ')
    },
    reasonValue(value: unknown): string {
      return typeof value === 'string' ? value : JSON.stringify(value)
    },
    reasonActionLabel(action: string): string {
      return this.$t(`dedup_management.eligibility.actions.${action}`).toString()
    },
    reasonActionClickable(action: string, reason: DedupEligibilityDisplayReason, reviewCase: DedupReviewCaseDto): boolean {
      if (['RUN_DEEP_VERIFICATION', 'REANALYZE_CASE'].includes(action)) return reviewCase.origin !== 'EXACT_FILE'
      if (action === 'OPEN_PAGE_COMPARISON') return hasPageEvidence(reviewCase)
      if (action === 'ANALYZE_BOOK') return reason.memberIds.some(id => reviewCase.members.some(member => member.bookId === id && member.book))
      return action === 'VIEW_TASK'
    },
    reasonActionLoading(action: string, reviewCase: DedupReviewCaseDto): boolean {
      if (['RUN_DEEP_VERIFICATION', 'REANALYZE_CASE'].includes(action)) return this.caseActionId === `${reviewCase.id}-reanalyze`
      if (action === 'OPEN_PAGE_COMPARISON') return this.caseActionId === `${reviewCase.id}-pages`
      if (action === 'ANALYZE_BOOK') return this.caseActionId === `${reviewCase.id}-analyze-books`
      return false
    },
    async runReasonAction(action: string, reviewCase: DedupReviewCaseDto, reason: DedupEligibilityDisplayReason) {
      if (['RUN_DEEP_VERIFICATION', 'REANALYZE_CASE'].includes(action)) return this.reanalyze(reviewCase)
      if (action === 'OPEN_PAGE_COMPARISON') return this.openPageComparison(reviewCase)
      if (action === 'VIEW_TASK') {
        this.tab = 2
        return
      }
      if (action === 'ANALYZE_BOOK') {
        const books = reviewCase.members.filter(member => reason.memberIds.includes(member.bookId)).map(member => member.book).filter(Boolean)
        this.caseActionId = `${reviewCase.id}-analyze-books`
        try {
          await Promise.all(books.map(book => this.$komgaBooks.analyzeBook(book!)))
          this.notify(this.$t('dedup_management.actions.book_analysis_requested', {count: books.length}).toString(), 'success')
        } catch (e) {
          this.notify(this.$t('dedup_management.errors.analyze_book').toString(), 'error')
        } finally {
          this.caseActionId = ''
        }
      }
    },
    blockerCount(reviewCase: DedupReviewCaseDto, action: 'SUGGESTED' | 'MANUAL'): number {
      return reviewCase.eligibility.blockers.filter(reason => reason.appliesTo.includes(action)).length
    },
    pageThumbnailUrl(page: DedupPageEvidenceDto): string {
      return bookPageThumbnailUrl(page.bookId, page.pageNumber)
    },
    pageBookTitle(bookId: string): string {
      const member = this.pageDialog.reviewCase?.members.find(item => item.bookId === bookId)
      return member?.book ? `${member.book.seriesTitle} — ${member.book.name}` : bookId
    },
    formatDate(value: string): string {
      return formatDistanceToNow(new Date(value), {addSuffix: true})
    },
    percent(value?: number | null): string {
      return value == null ? '—' : `${Math.round(value * 1000) / 10}%`
    },
    optionalNumber(value?: number | null): number | string {
      return value == null ? '—' : value
    },
    hasPageEvidence,
    unmatchedPageCount,
    bookRoute(member: DedupReviewCaseMemberDto): object {
      return member.book?.oneshot
        ? {name: 'browse-oneshot', params: {seriesId: member.book.seriesId}}
        : {name: 'browse-book', params: {bookId: member.bookId}}
    },
    workLabel(state: string): string {
      return this.$t(`dedup_management.work_states.${state}`).toString()
    },
    workColor(state: string): string {
      if (state === 'FAILED_REVIEW') return 'warning'
      if (state === 'RUNNING') return 'primary'
      if (state === 'SUCCEEDED') return 'success'
      return 'grey'
    },
    workIcon(state: string): string {
      if (state === 'FAILED_REVIEW') return 'mdi-alert-circle-outline'
      if (state === 'RUNNING') return 'mdi-progress-clock'
      if (state === 'SUCCEEDED') return 'mdi-check-circle-outline'
      return 'mdi-clock-outline'
    },
    decisionStateColor(state: string): string {
      if (state === 'COMPLETED') return 'success'
      if (['PARTIALLY_COMPLETED', 'REAPPROVAL_REQUIRED', 'NEEDS_ATTENTION', 'FAILED'].includes(state)) return 'warning'
      if (state === 'ABORTED') return 'error'
      return 'primary'
    },
    positiveRule(value: number): boolean | string {
      return value > 0 || this.$t('dedup_management.validation.positive').toString()
    },
    nonNegativeRule(value: number): boolean | string {
      return value >= 0 || this.$t('dedup_management.validation.non_negative').toString()
    },
    hashDistanceRule(value: number): boolean | string {
      return (value >= 0 && value <= 256) || this.$t('dedup_management.validation.hash_distance').toString()
    },
    notify(text: string, color: string) {
      this.snackbar = {show: true, text, color}
    },
  },
})
</script>

<style scoped>
.dedup-page {
  max-width: 1440px;
}

.dedup-heading {
  max-width: 72ch;
}

.status-strip,
.filter-bar,
.settings-section,
.case-panel,
.empty-state {
  border-radius: 12px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.status-item {
  padding: 16px 20px;
  border-right: 1px solid rgba(127, 127, 127, 0.24);
}

.status-item:last-child {
  border-right: 0;
}

.filter-control {
  flex: 0 1 280px;
}

.page-comparison-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}

.page-evidence-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  gap: 12px;
  max-height: 60vh;
  overflow: auto;
}

.page-evidence-item {
  min-width: 0;
}

.decision-audit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid rgba(127, 127, 127, 0.2);
}

.decision-audit-row:last-child {
  border-bottom: 0;
}

@media (max-width: 700px) {
  .page-comparison-grid {
    grid-template-columns: 1fr;
  }
}

.case-panel {
  border: 1px solid rgba(127, 127, 127, 0.3);
  overflow: hidden;
}

.case-summary,
.min-width-0 {
  min-width: 0;
}

.cover-stack {
  display: flex;
  min-width: 78px;
}

.cover-thumb {
  border-radius: 4px;
  background: var(--v-contrast-1-base);
}

.cover-thumb + .cover-thumb {
  margin-left: -16px;
}

.member-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}

.member-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 14px;
  border-radius: 10px;
}

.member-cover {
  border-radius: 4px;
  background: var(--v-contrast-1-base);
}

.reason-alert {
  max-width: 900px;
}

.bulk-case-list {
  max-height: 260px;
  overflow-y: auto;
}

.evidence-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 16px;
  border-radius: 10px;
}

.task-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1px;
  background: rgba(127, 127, 127, 0.24);
}

.task-row {
  display: flex;
  align-items: center;
  padding: 14px 16px;
  background: var(--v-base-base);
}

.empty-state p {
  max-width: 60ch;
}

@media (max-width: 599px) {
  .status-item {
    border-right: 0;
    border-bottom: 1px solid rgba(127, 127, 127, 0.24);
  }

  .status-item:last-child {
    border-bottom: 0;
  }

  .filter-control {
    flex-basis: auto;
  }
}
</style>
