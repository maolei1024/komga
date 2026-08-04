package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DedupMigrationTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `reset migration destroys every old Dedup row while preserving native data and Flyway history`() {
    val database = directory.resolve("dedup-reset.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE SERIES (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE BOOK (ID varchar PRIMARY KEY)")
        statement.execute("INSERT INTO LIBRARY VALUES ('library')")
        statement.execute("INSERT INTO SERIES VALUES ('series')")
        statement.execute("INSERT INTO BOOK VALUES ('native-book')")
        statement.execute("CREATE TABLE flyway_schema_history (version varchar)")
        statement.execute("INSERT INTO flyway_schema_history VALUES ('20260803121000')")
        OLD_TABLES.forEach { table ->
          statement.execute("CREATE TABLE $table (VALUE varchar)")
          statement.execute("INSERT INTO $table VALUES ('old-dedup-data')")
        }
      }
      val migration = Files.readString(Path.of("src/flyway/resources/db/migration/sqlite/V20260804120000__dedup_cluster_reset.sql"))
      connection.createStatement().use { statement ->
        migration
          .split(';')
          .map(String::trim)
          .filter(String::isNotEmpty)
          .forEach(statement::execute)
      }

      connection.createStatement().use { statement ->
        val tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { result -> buildSet { while (result.next()) add(result.getString(1)) } }
        assertThat(tables).contains(
          "DEDUP_CLUSTER",
          "DEDUP_CLUSTER_MEMBER",
          "DEDUP_RESOLUTION",
          "DEDUP_RESOLUTION_MEMBER",
          "DEDUP_LIBRARY_SETTINGS",
          "DEDUP_WORK",
          "DEDUP_FEATURE",
          "DEDUP_PAGE_FEATURE",
          "DEDUP_RELATION",
          "DEDUP_GORSE_SYNC",
        )
        assertThat(tables).doesNotContain("DEDUP_REVIEW_CASE", "DEDUP_REVIEW_CASE_MEMBER", "DEDUP_OVERRIDE", "DEDUP_DECISION", "DEDUP_DECISION_ITEM")
        assertThat(
          statement.executeQuery("SELECT COUNT(*) FROM DEDUP_LIBRARY_SETTINGS").use {
            it.next()
            it.getInt(1)
          },
        ).isZero()
        assertThat(
          statement.executeQuery("SELECT ID FROM BOOK").use {
            it.next()
            it.getString(1)
          },
        ).isEqualTo("native-book")
        assertThat(
          statement.executeQuery("SELECT version FROM flyway_schema_history").use {
            it.next()
            it.getString(1)
          },
        ).isEqualTo("20260803121000")
      }
    }
  }

  companion object {
    private val OLD_TABLES =
      listOf(
        "DEDUP_GORSE_SYNC",
        "DEDUP_DECISION_ITEM",
        "DEDUP_DECISION",
        "DEDUP_OVERRIDE",
        "DEDUP_REVIEW_CASE_MEMBER",
        "DEDUP_REVIEW_CASE",
        "DEDUP_RELATION",
        "DEDUP_PAGE_FEATURE",
        "DEDUP_FEATURE",
        "DEDUP_WORK",
        "DEDUP_LIBRARY_SETTINGS",
      )
  }
}
