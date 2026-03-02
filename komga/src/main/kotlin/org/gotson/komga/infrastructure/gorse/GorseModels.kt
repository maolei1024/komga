package org.gotson.komga.infrastructure.gorse

data class GorseItem(
  val ItemId: String,
  val IsHidden: Boolean = false,
  val Labels: List<String> = emptyList(),
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
  val Labels: List<String> = emptyList(),
  val Comment: String = "",
)
