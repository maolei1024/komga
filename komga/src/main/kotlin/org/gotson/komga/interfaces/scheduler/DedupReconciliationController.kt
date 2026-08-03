package org.gotson.komga.interfaces.scheduler

import org.gotson.komga.domain.service.DedupWorkLifecycle
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Profile("!test")
@Component
class DedupReconciliationController(
  private val dedupWorkLifecycle: DedupWorkLifecycle,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun reconcileOnStartup() {
    dedupWorkLifecycle.reconcileAtStartup()
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  fun reconcilePeriodically() {
    dedupWorkLifecycle.reconcileScheduled()
  }
}
