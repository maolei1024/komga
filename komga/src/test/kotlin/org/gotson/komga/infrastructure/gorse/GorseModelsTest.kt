package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GorseModelsTest {
  @Test
  fun `Gorse Item readback accepts null labels and categories`() {
    val item =
      jacksonObjectMapper().readValue<GorseItem>(
        """
        {
          "ItemId": "series",
          "IsHidden": true,
          "Categories": null,
          "Timestamp": "0001-01-01T00:00:00Z",
          "Labels": null,
          "Comment": ""
        }
        """.trimIndent(),
      )

    assertThat(item.IsHidden).isTrue()
    assertThat(item.Labels).isEmpty()
    assertThat(item.Categories).isEmpty()
  }
}
