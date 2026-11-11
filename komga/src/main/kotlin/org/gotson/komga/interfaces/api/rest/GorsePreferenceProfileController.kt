package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.infrastructure.gorse.GorsePreferenceProfile
import org.gotson.komga.infrastructure.gorse.GorsePreferenceProfileService
import org.gotson.komga.infrastructure.gorse.PreferencePreview
import org.gotson.komga.infrastructure.gorse.PreferenceProfileStatus
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/gorse/preference-profile")
class GorsePreferenceProfileController(
  private val service: GorsePreferenceProfileService,
) {
  @GetMapping
  fun get(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): PreferenceProfileStatus = service.get(principal.user.id)

  @GetMapping("status")
  fun status(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): PreferenceProfileStatus = service.get(principal.user.id)

  @PutMapping
  fun save(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestBody profile: GorsePreferenceProfile,
  ): PreferenceProfileStatus = validate { service.save(principal.user.id, profile) }

  @PostMapping("preview")
  fun preview(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestBody profile: GorsePreferenceProfile,
  ): PreferencePreview = validate { service.preview(profile, principal.user) }

  @PostMapping("apply")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun apply(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): PreferenceProfileStatus = validate { service.apply(principal.user.id) }

  @DeleteMapping("samples")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun withdraw(
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): PreferenceProfileStatus = validate { service.apply(principal.user.id, true) }

  private fun <Result> validate(action: () -> Result): Result =
    try {
      action()
    } catch (exception: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
    }
}
