package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class GorseItem(
  val ItemId: String,
  val IsHidden: Boolean = false,
  @param:JsonSetter(nulls = Nulls.AS_EMPTY)
  val Labels: Map<String, Any> = emptyMap(),
  @param:JsonSetter(nulls = Nulls.AS_EMPTY)
  val Categories: List<String> = emptyList(),
  val Timestamp: String,
  val Comment: String = "",
)

data class GorseFeedback(
  val FeedbackType: String,
  val UserId: String,
  val ItemId: String,
  val Timestamp: String,
)

data class GorseUser(
  val UserId: String,
  val Labels: Map<String, Any> = emptyMap(),
  val Comment: String = "",
)

data class GorseRecommendation(
  val Id: String,
  val Score: Double,
)
