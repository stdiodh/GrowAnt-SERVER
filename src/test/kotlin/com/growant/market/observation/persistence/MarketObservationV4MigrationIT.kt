package com.growant.market.observation.persistence

import com.growant.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class MarketObservationV4MigrationIT {
    @Test
    fun `V4 backfills populated V3 polls and enforces pagination identity`() {
        val schema = "observation_v4_${UUID.randomUUID().toString().replace("-", "")}"
        require(SCHEMA_NAME.matches(schema))
        createSchema(schema)

        try {
            val versionThree = flyway(schema, MigrationVersion.fromVersion("3"))
            versionThree.migrate()
            assertThat(versionThree.info().current()?.version?.version).isEqualTo("3")

            connection(schema).use(::insertV3Evidence)

            val latest = flyway(schema)
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(latest.info().current()?.version?.version).isEqualTo("4")

            connection(schema).use { connection ->
                assertBackfilledPage(connection)
                assertV4ConstraintsInstalled(connection, schema)

                val invalidPage = assertThrows<SQLException> {
                    insertV4Page(
                        connection = connection,
                        requestId = INVALID_PAGE_REQUEST_ID,
                        pollRunId = ROOT_REQUEST_ID,
                        pageOrdinal = 1,
                        requestCursor = null,
                    )
                }
                assertThat(invalidPage.sqlState).isEqualTo(CHECK_VIOLATION_SQL_STATE)

                val orphanPage = assertThrows<SQLException> {
                    insertV4Page(
                        connection = connection,
                        requestId = ORPHAN_PAGE_REQUEST_ID,
                        pollRunId = ORPHAN_POLL_RUN_ID,
                        pageOrdinal = 1,
                        requestCursor = "2026-08-17T09:00:00+09:00",
                    )
                }
                assertThat(orphanPage.sqlState).isEqualTo(FOREIGN_KEY_VIOLATION_SQL_STATE)
            }
        } finally {
            dropSchema(schema)
        }
    }

    private fun flyway(
        schema: String,
        target: MigrationVersion? = null,
    ): Flyway {
        val configuration = Flyway.configure()
            .dataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(false)
        target?.let(configuration::target)
        return configuration.load()
    }

    private fun createSchema(schema: String) {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
    }

    private fun dropSchema(schema: String) {
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }

    private fun connection(schema: String): Connection =
        DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).also { connection ->
            connection.createStatement().use { statement -> statement.execute("SET search_path TO $schema") }
        }

    private fun insertV3Evidence(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO market_observation_runs (
                run_id, provider, role, origin, state,
                storage_right, benchmark_right, replay_right, ci_right,
                internal_display_right, external_distribution_right,
                rights_evidence_id, rights_evidence_sha256,
                benchmark_spec_id, benchmark_spec_sha256,
                source_commit_sha, source_tree_dirty,
                window_start, window_end, expected_ticker_count, ticker_set_sha256,
                retention_until, created_at
            ) VALUES (
                ?, 'toss', 'CANDLE_REFERENCE', 'SYNTHETIC', 'PLANNED',
                'ALLOWED', 'ALLOWED', 'DENIED', 'DENIED', 'DENIED', 'DENIED',
                'v4-migration-rights', ?, 'v4-migration-spec', ?, ?, FALSE,
                TIMESTAMPTZ '2026-08-17T00:00:00Z', TIMESTAMPTZ '2026-08-17T01:00:00Z', 1, ?,
                TIMESTAMPTZ '2026-08-18T00:00:00Z', TIMESTAMPTZ '2026-08-16T00:00:00Z'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setString(2, "a".repeat(64))
            statement.setString(3, "b".repeat(64))
            statement.setString(4, "c".repeat(40))
            statement.setString(5, "d".repeat(64))
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }

        connection.prepareStatement(
            """
            INSERT INTO market_observation_expected_tickers (run_id, provider, ticker, ordinal)
            VALUES (?, 'toss', '005930', 0)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }

        connection.prepareStatement(
            """
            INSERT INTO market_observation_rest_polls (
                run_id, provider, ticker, request_id,
                observed_at, request_started_at, normalized_at,
                requested_from, requested_to, outcome, http_status,
                returned_candle_count, eligible_candle_count
            ) VALUES (
                ?, 'toss', '005930', ?,
                TIMESTAMPTZ '2026-08-17T00:00:04Z', TIMESTAMPTZ '2026-08-17T00:00:03Z', NULL,
                TIMESTAMPTZ '2026-08-17T00:00:00Z', TIMESTAMPTZ '2026-08-17T01:00:00Z',
                'HTTP_ERROR', 500, 1, 1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setObject(2, LEGACY_ERROR_REQUEST_ID)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }

        connection.prepareStatement(
            """
            INSERT INTO market_observation_rest_polls (
                run_id, provider, ticker, request_id,
                observed_at, request_started_at, normalized_at,
                requested_from, requested_to, outcome, http_status,
                returned_candle_count, eligible_candle_count
            ) VALUES (
                ?, 'toss', '005930', ?,
                TIMESTAMPTZ '2026-08-17T00:00:02Z', TIMESTAMPTZ '2026-08-17T00:00:01Z',
                TIMESTAMPTZ '2026-08-17T00:00:02Z',
                TIMESTAMPTZ '2026-08-17T00:00:00Z', TIMESTAMPTZ '2026-08-17T01:00:00Z',
                'SUCCESS', 200, 0, 0
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setObject(2, ROOT_REQUEST_ID)
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }

    private fun assertBackfilledPage(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT request_id, poll_run_id, page_ordinal, request_cursor, next_cursor, poll_terminal
            FROM market_observation_rest_polls
            WHERE run_id = ? AND provider = 'toss' AND request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setObject(2, ROOT_REQUEST_ID)
            statement.executeQuery().use { resultSet ->
                assertThat(resultSet.next()).isTrue()
                assertThat(resultSet.getObject("request_id", UUID::class.java)).isEqualTo(ROOT_REQUEST_ID)
                assertThat(resultSet.getObject("poll_run_id", UUID::class.java)).isEqualTo(ROOT_REQUEST_ID)
                assertThat(resultSet.getInt("page_ordinal")).isZero()
                assertThat(resultSet.getString("request_cursor")).isNull()
                assertThat(resultSet.getString("next_cursor")).isNull()
                assertThat(resultSet.getBoolean("poll_terminal")).isTrue()
                assertThat(resultSet.next()).isFalse()
            }
        }

        connection.prepareStatement(
            """
            SELECT request_id, poll_run_id, page_ordinal, request_cursor, next_cursor,
                poll_terminal, returned_candle_count, eligible_candle_count
            FROM market_observation_rest_polls
            WHERE run_id = ? AND provider = 'toss' AND request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setObject(2, LEGACY_ERROR_REQUEST_ID)
            statement.executeQuery().use { resultSet ->
                assertThat(resultSet.next()).isTrue()
                assertThat(resultSet.getObject("poll_run_id", UUID::class.java))
                    .isEqualTo(LEGACY_ERROR_REQUEST_ID)
                assertThat(resultSet.getInt("page_ordinal")).isZero()
                assertThat(resultSet.getString("request_cursor")).isNull()
                assertThat(resultSet.getString("next_cursor")).isNull()
                assertThat(resultSet.getBoolean("poll_terminal")).isTrue()
                assertThat(resultSet.getInt("returned_candle_count")).isEqualTo(1)
                assertThat(resultSet.getInt("eligible_candle_count")).isEqualTo(1)
                assertThat(resultSet.next()).isFalse()
            }
        }
    }

    private fun assertV4ConstraintsInstalled(
        connection: Connection,
        schema: String,
    ) {
        connection.prepareStatement(
            """
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_class r ON r.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = r.relnamespace
            WHERE n.nspname = ?
              AND r.relname = 'market_observation_rest_polls'
              AND c.convalidated
              AND c.conname = ANY (?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, schema)
            statement.setArray(2, connection.createArrayOf("text", V4_CONSTRAINTS.toTypedArray()))
            statement.executeQuery().use { resultSet ->
                val installed = buildSet {
                    while (resultSet.next()) add(resultSet.getString("conname"))
                }
                assertThat(installed).containsExactlyInAnyOrderElementsOf(V4_CONSTRAINTS)
            }
        }
    }

    private fun insertV4Page(
        connection: Connection,
        requestId: UUID,
        pollRunId: UUID,
        pageOrdinal: Int,
        requestCursor: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO market_observation_rest_polls (
                run_id, provider, ticker, request_id,
                poll_run_id, page_ordinal, request_cursor, next_cursor, poll_terminal,
                observed_at, request_started_at, normalized_at,
                requested_from, requested_to, outcome, http_status,
                returned_candle_count, eligible_candle_count
            ) VALUES (
                ?, 'toss', '005930', ?, ?, ?, ?, NULL, TRUE,
                TIMESTAMPTZ '2026-08-17T00:00:04Z', TIMESTAMPTZ '2026-08-17T00:00:03Z',
                TIMESTAMPTZ '2026-08-17T00:00:04Z',
                TIMESTAMPTZ '2026-08-17T00:00:00Z', TIMESTAMPTZ '2026-08-17T01:00:00Z',
                'SUCCESS', 200, 0, 0
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, RUN_ID)
            statement.setObject(2, requestId)
            statement.setObject(3, pollRunId)
            statement.setInt(4, pageOrdinal)
            statement.setString(5, requestCursor)
            statement.executeUpdate()
        }
    }

    private companion object {
        val POSTGRES = PostgresIntegrationTest.postgres
        val SCHEMA_NAME = Regex("[a-z][a-z0-9_]{0,62}")
        val RUN_ID: UUID = UUID.fromString("71000000-0000-0000-0000-000000000001")
        val ROOT_REQUEST_ID: UUID = UUID.fromString("72000000-0000-0000-0000-000000000001")
        val LEGACY_ERROR_REQUEST_ID: UUID = UUID.fromString("72000000-0000-0000-0000-000000000004")
        val INVALID_PAGE_REQUEST_ID: UUID = UUID.fromString("72000000-0000-0000-0000-000000000002")
        val ORPHAN_PAGE_REQUEST_ID: UUID = UUID.fromString("72000000-0000-0000-0000-000000000003")
        val ORPHAN_POLL_RUN_ID: UUID = UUID.fromString("72000000-0000-0000-0000-000000000099")
        const val CHECK_VIOLATION_SQL_STATE = "23514"
        const val FOREIGN_KEY_VIOLATION_SQL_STATE = "23503"
        val V4_CONSTRAINTS = setOf(
            "uq_market_observation_rest_polls_poll_page",
            "fk_market_observation_rest_polls_poll_root",
            "ck_market_observation_rest_polls_page_identity",
            "ck_market_observation_rest_polls_request_cursor",
            "ck_market_observation_rest_polls_next_cursor",
            "ck_market_observation_rest_polls_cursor_progress",
            "ck_market_observation_rest_polls_terminal",
        )
    }
}
