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

  @Test
  fun `relation v3 migration preserves audit and feature data while reseeding configured Libraries`() {
    val database = directory.resolve("dedup-relation-v3.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute("CREATE TABLE LIBRARY (ID varchar PRIMARY KEY)")
        statement.execute("CREATE TABLE SERIES (ID varchar PRIMARY KEY)")
        statement.execute(
          """
          CREATE TABLE BOOK (
            ID varchar PRIMARY KEY,
            SERIES_ID varchar NOT NULL,
            LIBRARY_ID varchar NOT NULL,
            URL varchar NOT NULL,
            FILE_SIZE int8 NOT NULL,
            DELETED_DATE datetime NULL
          )
          """.trimIndent(),
        )
      }
      executeMigration(connection, "V20260804120000__dedup_cluster_reset.sql")
      executeMigration(connection, "V20260805120000__dedup_rclone_identity_and_summary.sql")
      executeMigration(connection, "V20260805180000__dedup_v2.sql")
      executeMigration(connection, "V20260808120000__dedup_auto_resolve_suggestions.sql")

      connection.createStatement().use { statement ->
        statement.execute("INSERT INTO LIBRARY VALUES ('library'), ('disabled-library'), ('paused-library')")
        statement.execute("INSERT INTO SERIES VALUES ('series')")
        statement.execute(
          """
          INSERT INTO BOOK VALUES
            ('A', 'series', 'library', 'file:/library/A.cbz', 100, NULL),
            ('B', 'series', 'library', 'file:/library/B.cbz', 100, CURRENT_TIMESTAMP),
            ('C', 'series', 'library', 'file:/library/C.pdf', 100, NULL),
            ('D', 'series', 'disabled-library', 'file:/library/D.CBZ', 100, NULL),
            ('E', 'series', 'paused-library', 'file:/library/E.cbz?cache=1', 100, NULL)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_LIBRARY_SETTINGS (LIBRARY_ID, ENABLED, PAUSED, AUTO_RESOLVE_SUGGESTIONS)
          VALUES
            ('library', true, false, true),
            ('disabled-library', false, false, true),
            ('paused-library', true, true, true)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_FEATURE
            (BOOK_ID, SERIES_ID, LIBRARY_ID, SOURCE_CONTENT_GENERATION, SOURCE_COVER_GENERATION,
             SOURCE_METADATA_GENERATION, SERIES_SCOPE_REVISION, FEATURE_SCHEMA_VERSION, COVER_STATE,
             PAGE_STATE, COVER_HASH)
          VALUES
            ('A', 'series', 'library', 'content-A', 'cover-A', 'metadata-A', 'scope-A', 2, 'READY', 'READY', X'00'),
            ('D', 'series', 'disabled-library', 'content-D', 'cover-D', 'metadata-D', 'scope-D', 2, 'READY', 'WAITING', X'00')
          """.trimIndent(),
        )
        statement.execute("INSERT INTO DEDUP_PAGE_FEATURE VALUES ('A', 'content-A', 1, 1, 'exact', X'00', 100)")
        statement.execute(
          """
          INSERT INTO DEDUP_CLUSTER
            (ID, LIBRARY_ID, REVISION, STATUS, REVIEWABLE, ANCHOR_BOOK_ID, TOPOLOGY_FINGERPRINT,
             EVIDENCE_FINGERPRINT, STATE_FINGERPRINT, MEMBER_COUNT, VERIFIED_PAIR_COUNT,
             TOTAL_PAIR_COUNT, EVIDENCE_MATURITY)
          VALUES ('cluster', 'library', 1, 'PROCESSED', false, 'A', 't', 'e', 's', 1, 0, 0, 'COVER_ONLY')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_CLUSTER
            (ID, LIBRARY_ID, REVISION, STATUS, REVIEWABLE, ANCHOR_BOOK_ID, TOPOLOGY_FINGERPRINT,
             EVIDENCE_FINGERPRINT, STATE_FINGERPRINT, LAST_RESOLUTION_ID, MEMBER_COUNT,
             VERIFIED_PAIR_COUNT, TOTAL_PAIR_COUNT, EVIDENCE_MATURITY)
          VALUES
            ('auto-cluster', 'library', 1, 'PROCESSING', true, 'A', 'ta', 'ea', 'sa',
             'auto-resolution', 1, 0, 0, 'COVER_ONLY'),
            ('partial-cluster', 'library', 1, 'PROCESSING', true, 'A', 'tp', 'ep', 'sp',
             'partial-resolution', 1, 0, 0, 'COVER_ONLY')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_CLUSTER_MEMBER
            (CLUSTER_ID, BOOK_ID, PRESENT, SOURCE_CONTENT_GENERATION, SOURCE_COVER_GENERATION,
             SOURCE_METADATA_GENERATION, SERIES_SCOPE_REVISION)
          VALUES
            ('cluster', 'A', true, 'content-A', 'cover-A', 'metadata-A', 'scope-A'),
            ('auto-cluster', 'A', true, 'content-A', 'cover-A', 'metadata-A', 'scope-A'),
            ('partial-cluster', 'A', true, 'content-A', 'cover-A', 'metadata-A', 'scope-A')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION
            (ID, CLUSTER_ID, CLUSTER_REVISION, MODE, PLAN_REVISION, PLAN_JSON, EVIDENCE_JSON,
             ELIGIBILITY_JSON, RULE_VERSION, STATE, ACTOR_ID, LEASE_TOKEN, LEASE_UNTIL)
          VALUES ('resolution', 'cluster', 1, 'CUSTOM', 'plan', '{}', '{}', '{}', 3,
                  'PROCESSED', 'admin', 'lease', CURRENT_TIMESTAMP)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION
            (ID, CLUSTER_ID, CLUSTER_REVISION, MODE, PLAN_REVISION, PLAN_JSON, EVIDENCE_JSON,
             ELIGIBILITY_JSON, RULE_VERSION, STATE, ACTOR_ID, LEASE_TOKEN, LEASE_UNTIL)
          VALUES
            ('auto-resolution', 'auto-cluster', 1, 'SUGGESTED', 'auto-plan', '{}', '{}', '{}', 3,
             'PROCESSING', 'system:dedup-auto', 'auto-lease', CURRENT_TIMESTAMP),
            ('partial-resolution', 'partial-cluster', 1, 'SUGGESTED', 'partial-plan', '{}', '{}', '{}', 3,
             'PROCESSING', 'system:dedup-auto', 'partial-lease', CURRENT_TIMESTAMP)
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION_MEMBER
            (RESOLUTION_ID, BOOK_ID, SERIES_ID, LIBRARY_ID, ACTION, TITLE_SNAPSHOT, PATH_SNAPSHOT,
             SOURCE_GENERATIONS_JSON, LOCAL_STATE_SNAPSHOT_JSON, STATE)
          VALUES ('resolution', 'A', 'series', 'library', 'KEEP', 'A', '/A.cbz', '{}', '{}', 'COMPLETED')
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_RESOLUTION_MEMBER
            (RESOLUTION_ID, BOOK_ID, SERIES_ID, LIBRARY_ID, ACTION, KEEPER_BOOK_ID,
             TITLE_SNAPSHOT, PATH_SNAPSHOT, SOURCE_GENERATIONS_JSON, LOCAL_STATE_SNAPSHOT_JSON,
             DIRECT_RELATION_ID, STATE)
          VALUES
            ('auto-resolution', 'A', 'series', 'library', 'KEEP', NULL,
             'A', '/A.cbz', '{}', '{}', NULL, 'PLANNED'),
            ('partial-resolution', 'A', 'series', 'library', 'DELETE', 'D',
             'A', '/A.cbz', '{}', '{}', 'old-relation', 'DELETED')
          """.trimIndent(),
        )
        statement.execute("INSERT INTO DEDUP_PAIR_DECISION VALUES ('A', 'C', 'KEEP_BOTH', 'resolution', 'admin', CURRENT_TIMESTAMP)")
        statement.execute("INSERT INTO DEDUP_GORSE_SYNC (SERIES_ID, LIBRARY_ID, DESIRED_HIDDEN) VALUES ('series', 'library', false)")
        statement.execute(
          """
          INSERT INTO DEDUP_RELATION
            (ID, LIBRARY_ID, BOOK_LOW_ID, BOOK_HIGH_ID, LOW_CONTENT_GENERATION,
             HIGH_CONTENT_GENERATION, RELATION_TYPE, FEATURE_SCHEMA_VERSION,
             CLASSIFIER_RULE_VERSION, STATUS)
          VALUES ('old-relation', 'library', 'A', 'C', 'content-A', 'content-C',
                  'EDITION_UNCERTAIN', 1, 2, 'VERIFIED')
          """.trimIndent(),
        )
        statement.execute("INSERT INTO DEDUP_WORK (ID, LIBRARY_ID, TYPE, TARGET_KEY) VALUES ('old-work', 'library', 'VERIFY_RELATION', 'A|C')")
      }

      executeMigration(connection, "V20260809120000__dedup_relation_v3.sql")

      connection.createStatement().use { statement ->
        val relationColumns =
          statement.executeQuery("PRAGMA table_info(DEDUP_RELATION)").use { result ->
            buildSet { while (result.next()) add(result.getString("name")) }
          }
        assertThat(relationColumns).contains("RELATION_TYPE", "EVIDENCE_JSON", "COVER_DISTANCE")
        assertThat(relationColumns).doesNotContain("STATUS", "COVERAGE_LEFT", "CONFIDENCE", "LOW_COVER_GENERATION")
        assertThat(statement.count("DEDUP_RELATION")).isZero()
        assertThat(statement.count("DEDUP_FEATURE")).isEqualTo(2)
        assertThat(statement.count("DEDUP_PAGE_FEATURE")).isEqualTo(1)
        assertThat(statement.count("DEDUP_CLUSTER")).isEqualTo(3)
        assertThat(statement.count("DEDUP_RESOLUTION")).isEqualTo(3)
        assertThat(statement.count("DEDUP_RESOLUTION_MEMBER")).isEqualTo(3)
        assertThat(statement.count("DEDUP_PAIR_DECISION")).isEqualTo(1)
        assertThat(statement.count("DEDUP_GORSE_SYNC")).isEqualTo(1)
        assertThat(
          statement.executeQuery("SELECT LIBRARY_ID, TYPE, TARGET_KEY, PRIORITY FROM DEDUP_WORK ORDER BY ID").use { result ->
            buildList {
              while (result.next()) add(listOf(result.getString(1), result.getString(2), result.getString(3), result.getInt(4)))
            }
          },
        ).containsExactlyInAnyOrder(
          listOf("library", "SCAN_BOOK", "A", 0),
          listOf("disabled-library", "SCAN_BOOK", "D", 0),
          listOf("paused-library", "SCAN_BOOK", "E", 0),
          listOf("library", "REBUILD_CLUSTERS", "", -1),
          listOf("disabled-library", "REBUILD_CLUSTERS", "", -1),
          listOf("paused-library", "REBUILD_CLUSTERS", "", -1),
        )
        assertThat(
          statement.executeQuery("SELECT COUNT(*) FROM DEDUP_LIBRARY_SETTINGS WHERE AUTO_RESOLVE_SUGGESTIONS = true").use {
            it.next()
            it.getInt(1)
          },
        ).isZero()
        assertThat(
          statement.executeQuery("SELECT ID, STATE FROM DEDUP_RESOLUTION ORDER BY ID").use { result ->
            buildList {
              while (result.next()) add(result.getString(1) to result.getString(2))
            }
          },
        ).containsExactly(
          "auto-resolution" to "NEEDS_ATTENTION",
          "partial-resolution" to "PARTIALLY_COMPLETED",
          "resolution" to "PROCESSED",
        )
        assertThat(
          statement.executeQuery("SELECT ID, STATUS, REOPEN_REASON FROM DEDUP_CLUSTER ORDER BY ID").use { result ->
            buildList {
              while (result.next()) add(Triple(result.getString(1), result.getString(2), result.getString(3)))
            }
          },
        ).containsExactly(
          Triple("auto-cluster", "UNPROCESSED", "AUTO_RESOLUTION_PAUSED_FOR_RELATION_V3"),
          Triple("cluster", "PROCESSED", null),
          Triple("partial-cluster", "NEEDS_ATTENTION", "AUTO_RESOLUTION_PAUSED_FOR_RELATION_V3"),
        )
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
  fun `Gorse revision migration preserves queued desired state`() {
    val database = directory.resolve("dedup-gorse-revision.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE DEDUP_GORSE_SYNC
          (
              SERIES_ID           varchar  NOT NULL PRIMARY KEY,
              LIBRARY_ID          varchar  NOT NULL,
              DESIRED_HIDDEN      boolean  NOT NULL,
              STATE               varchar  NOT NULL DEFAULT 'PENDING',
              ATTEMPT_COUNT       integer  NOT NULL DEFAULT 0,
              NEXT_RETRY_AT       datetime NULL,
              LAST_ERROR          varchar  NULL,
              CREATED_DATE        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
              LAST_MODIFIED_DATE  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
              COMPLETED_DATE      datetime NULL,
              CHECK (STATE IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED_REVIEW'))
          )
          """.trimIndent(),
        )
        statement.execute(
          """
          INSERT INTO DEDUP_GORSE_SYNC
            (SERIES_ID, LIBRARY_ID, DESIRED_HIDDEN, STATE, ATTEMPT_COUNT, CREATED_DATE, LAST_MODIFIED_DATE)
          VALUES
            ('visible', 'library', false, 'PENDING', 0,
             '2026-08-04 18:32:54.541937599', '2026-08-04 18:32:54.541937599'),
            ('hidden', 'library', true, 'FAILED_REVIEW', 3,
             '2026-08-05 03:14:54.581755441', '2026-08-05 03:14:54.581755441')
          """.trimIndent(),
        )
      }

      executeMigration(connection, "V20260811120000__dedup_gorse_sync_revision.sql")

      connection.createStatement().use { statement ->
        assertThat(
          statement
            .executeQuery(
              "SELECT SERIES_ID, DESIRED_HIDDEN, STATE, ATTEMPT_COUNT, LAST_MODIFIED_DATE, REVISION FROM DEDUP_GORSE_SYNC ORDER BY SERIES_ID",
            ).use { result ->
              buildList {
                while (result.next()) {
                  add(
                    listOf(
                      result.getString(1),
                      result.getBoolean(2),
                      result.getString(3),
                      result.getInt(4),
                      result.getString(5),
                      result.getLong(6),
                    ),
                  )
                }
              }
            },
        ).containsExactly(
          listOf("hidden", true, "FAILED_REVIEW", 3, "2026-08-05 03:14:54.581755441", 1L),
          listOf("visible", false, "PENDING", 0, "2026-08-04 18:32:54.541937599", 1L),
        )
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

  private fun java.sql.Statement.count(table: String): Int =
    executeQuery("SELECT COUNT(*) FROM $table").use {
      it.next()
      it.getInt(1)
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
