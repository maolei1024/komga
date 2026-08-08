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

  @Test
  fun `V2 migration preserves processed survivor intent and relaxes DELETE audit constraints`() {
    val database = directory.resolve("dedup-v2.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE SERIES (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE BOOK (ID varchar PRIMARY KEY, FILE_SIZE int8 NOT NULL, DELETED_DATE datetime NULL)")
      }
      executeMigration(connection, "V20260804120000__dedup_cluster_reset.sql")
      executeMigration(connection, "V20260805120000__dedup_rclone_identity_and_summary.sql")
      connection.createStatement().use { statement ->
        statement.execute("INSERT INTO LIBRARY VALUES ('library')")
        statement.execute("INSERT INTO SERIES VALUES ('series')")
        listOf("A", "B", "C").forEach { statement.execute("INSERT INTO BOOK VALUES ('$it', 100, NULL)") }
        statement.execute(
          """
          INSERT INTO DEDUP_LIBRARY_SETTINGS (LIBRARY_ID, ENABLED) VALUES ('library', true)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_CLUSTER
            (ID, LIBRARY_ID, REVISION, STATUS, REVIEWABLE, ANCHOR_BOOK_ID, TOPOLOGY_FINGERPRINT,
             EVIDENCE_FINGERPRINT, STATE_FINGERPRINT, PROCESSED_REVISION, LAST_RESOLUTION_ID,
             CREATED_DATE, LAST_MODIFIED_DATE, PROCESSED_DATE, MEMBER_COUNT, VERIFIED_PAIR_COUNT,
             TOTAL_PAIR_COUNT, EVIDENCE_MATURITY)
          VALUES
            ('cluster-ab', 'library', 1, 'PROCESSED', false, 'A', 't', 'e', 's', 1, 'resolution',
             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2, 1, 1, 'COMPLETE'),
            ('cluster-c', 'library', 1, 'PROCESSED', false, 'C', 't2', 'e2', 's2', 1, NULL,
             CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0, 0, 'COVER_ONLY')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION
            (ID, CLUSTER_ID, CLUSTER_REVISION, MODE, PLAN_REVISION, PLAN_JSON, EVIDENCE_JSON,
             ELIGIBILITY_JSON, RULE_VERSION, STATE, ACTOR_ID, RESULT_JSON, LEASE_TOKEN, LEASE_UNTIL,
             CREATED_DATE, LAST_MODIFIED_DATE, COMPLETED_DATE)
          VALUES ('resolution', 'cluster-ab', 1, 'CUSTOM', 'plan', '{}', '{}', '{}', 2, 'PROCESSED',
                  'admin', '{}', 'lease', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_CLUSTER_MEMBER
            (CLUSTER_ID, BOOK_ID, PRESENT, SOURCE_CONTENT_GENERATION, SOURCE_COVER_GENERATION,
             SOURCE_METADATA_GENERATION, SERIES_SCOPE_REVISION)
          VALUES
            ('cluster-ab', 'A', true, 'a', 'a', 'a', 'a'),
            ('cluster-ab', 'B', true, 'b', 'b', 'b', 'b'),
            ('cluster-c', 'C', true, 'c', 'c', 'c', 'c')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RELATION
            (ID, LIBRARY_ID, BOOK_LOW_ID, BOOK_HIGH_ID, LOW_CONTENT_GENERATION, HIGH_CONTENT_GENERATION,
             RELATION_TYPE, FEATURE_SCHEMA_VERSION, CLASSIFIER_RULE_VERSION, STATUS)
          VALUES ('relation', 'library', 'A', 'B', 'a', 'b', 'EXACT_FILE', 1, 1, 'VERIFIED')
          """.trimIndent(),
        )
      }

      executeMigration(connection, "V20260805180000__dedup_v2.sql")

      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT BOOK_LOW_ID, BOOK_HIGH_ID, DECISION, RESOLUTION_ID, ACTOR_ID FROM DEDUP_PAIR_DECISION").use { result ->
          assertThat(result.next()).isTrue()
          assertThat(listOf(result.getString(1), result.getString(2), result.getString(3), result.getString(4), result.getString(5)))
            .containsExactly("A", "B", "KEEP_BOTH", "resolution", "admin")
          assertThat(result.next()).isFalse()
        }
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION_MEMBER
            (RESOLUTION_ID, BOOK_ID, SERIES_ID, LIBRARY_ID, ACTION, TITLE_SNAPSHOT, PATH_SNAPSHOT,
             SOURCE_GENERATIONS_JSON, LOCAL_STATE_SNAPSHOT_JSON, STATE)
          VALUES ('resolution', 'C', 'series', 'library', 'DELETE', 'C', '/C.cbz', '{}', '{}', 'PLANNED')
          """.trimIndent(),
        )
        org.assertj.core.api.Assertions
          .assertThatThrownBy {
            statement.execute("INSERT INTO DEDUP_PAIR_DECISION VALUES ('B', 'A', 'KEEP_BOTH', NULL, 'admin', CURRENT_TIMESTAMP)")
          }.isInstanceOf(java.sql.SQLException::class.java)
        statement.execute("DELETE FROM BOOK WHERE ID = 'A'")
        assertThat(
          statement.executeQuery("SELECT COUNT(*) FROM DEDUP_PAIR_DECISION").use {
            it.next()
            it.getInt(1)
          },
        ).isZero()
        assertThat(statement.executeQuery("PRAGMA foreign_key_check").use { it.next() }).isFalse()
        assertThat(
          statement.executeQuery("PRAGMA integrity_check").use {
            it.next()
            it.getString(1)
          },
        ).isEqualTo("ok")
      }
    }
  }

  @Test
  fun `automatic suggestion migration preserves settings and defaults to disabled`() {
    val database = directory.resolve("dedup-auto-suggestions.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE SERIES (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE BOOK (ID varchar PRIMARY KEY)")
        statement.execute("INSERT INTO LIBRARY VALUES ('library')")
      }
      executeMigration(connection, "V20260804120000__dedup_cluster_reset.sql")
      connection.createStatement().use { statement ->
        statement.execute("INSERT INTO DEDUP_LIBRARY_SETTINGS (LIBRARY_ID, ENABLED) VALUES ('library', true)")
      }

      executeMigration(connection, "V20260808120000__dedup_auto_resolve_suggestions.sql")

      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT ENABLED, AUTO_RESOLVE_SUGGESTIONS FROM DEDUP_LIBRARY_SETTINGS WHERE LIBRARY_ID = 'library'").use { result ->
          assertThat(result.next()).isTrue()
          assertThat(result.getBoolean(1)).isTrue()
          assertThat(result.getBoolean(2)).isFalse()
        }
        statement.execute("UPDATE DEDUP_LIBRARY_SETTINGS SET AUTO_RESOLVE_SUGGESTIONS = true WHERE LIBRARY_ID = 'library'")
        assertThat(
          statement.executeQuery("SELECT AUTO_RESOLVE_SUGGESTIONS FROM DEDUP_LIBRARY_SETTINGS WHERE LIBRARY_ID = 'library'").use {
            it.next()
            it.getBoolean(1)
          },
        ).isTrue()
        assertThat(
          statement.executeQuery("PRAGMA integrity_check").use {
            it.next()
            it.getString(1)
          },
        ).isEqualTo("ok")
      }
    }
  }

  private fun executeMigration(
    connection: java.sql.Connection,
    fileName: String,
  ) {
    val migration = Files.readString(Path.of("src/flyway/resources/db/migration/sqlite/$fileName"))
    migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql ->
      try {
        connection.createStatement().use { statement -> statement.execute(sql) }
      } catch (exception: java.sql.SQLException) {
        throw java.sql.SQLException("$fileName failed at: ${sql.take(240)}", exception)
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
