CREATE TABLE market_observation_runs (
    run_id                       UUID         NOT NULL,
    provider                     VARCHAR(40)  NOT NULL,
    role                         VARCHAR(24)  NOT NULL,
    origin                       VARCHAR(16)  NOT NULL,
    state                        VARCHAR(16)  NOT NULL,
    storage_right                VARCHAR(16)  NOT NULL,
    benchmark_right              VARCHAR(16)  NOT NULL,
    replay_right                 VARCHAR(16)  NOT NULL,
    ci_right                     VARCHAR(16)  NOT NULL,
    internal_display_right       VARCHAR(16)  NOT NULL,
    external_distribution_right VARCHAR(16)  NOT NULL,
    rights_evidence_id           VARCHAR(120) NOT NULL,
    rights_evidence_sha256       CHAR(64)     NOT NULL,
    benchmark_spec_id            VARCHAR(120) NOT NULL,
    benchmark_spec_sha256        CHAR(64)     NOT NULL,
    source_commit_sha            VARCHAR(64)  NOT NULL,
    source_tree_dirty            BOOLEAN      NOT NULL,
    window_start                 TIMESTAMPTZ  NOT NULL,
    window_end                   TIMESTAMPTZ  NOT NULL,
    expected_ticker_count        INTEGER      NOT NULL,
    ticker_set_sha256            CHAR(64)     NOT NULL,
    latest_clock_sample_sequence BIGINT,
    activation_clock_sample_sequence BIGINT,
    retention_until              TIMESTAMPTZ  NOT NULL,
    created_at                   TIMESTAMPTZ  NOT NULL,
    started_at                   TIMESTAMPTZ,
    completed_at                 TIMESTAMPTZ,
    CONSTRAINT pk_market_observation_runs PRIMARY KEY (run_id, provider),
    CONSTRAINT ck_market_observation_runs_provider
        CHECK (provider ~ '^[a-z0-9][a-z0-9._-]*$'),
    CONSTRAINT ck_market_observation_runs_role
        CHECK (role IN ('REALTIME_POC', 'CANDLE_REFERENCE', 'KOSPI_FEED')),
    CONSTRAINT ck_market_observation_runs_origin
        CHECK (origin IN ('PROVIDER', 'SYNTHETIC')),
    CONSTRAINT ck_market_observation_runs_state
        CHECK (state IN ('PLANNED', 'RUNNING', 'COMPLETED', 'INVALID')),
    CONSTRAINT ck_market_observation_runs_rights
        CHECK (
            storage_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
            AND benchmark_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
            AND replay_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
            AND ci_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
            AND internal_display_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
            AND external_distribution_right IN ('UNKNOWN', 'ALLOWED', 'DENIED')
        ),
    CONSTRAINT ck_market_observation_runs_evidence_id
        CHECK (rights_evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    CONSTRAINT ck_market_observation_runs_evidence_sha256
        CHECK (rights_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_observation_runs_benchmark_spec_id
        CHECK (benchmark_spec_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    CONSTRAINT ck_market_observation_runs_benchmark_spec_sha256
        CHECK (benchmark_spec_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_observation_runs_source_commit
        CHECK (source_commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    CONSTRAINT ck_market_observation_runs_window
        CHECK (window_start < window_end AND retention_until > window_end),
    CONSTRAINT ck_market_observation_runs_expected_ticker_count
        CHECK (expected_ticker_count BETWEEN 1 AND 10000),
    CONSTRAINT ck_market_observation_runs_ticker_set_sha256
        CHECK (ticker_set_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_observation_runs_clock_sequences
        CHECK (
            (latest_clock_sample_sequence IS NULL OR latest_clock_sample_sequence >= 0)
            AND (
                activation_clock_sample_sequence IS NULL
                OR (
                    activation_clock_sample_sequence >= 0
                    AND latest_clock_sample_sequence IS NOT NULL
                    AND activation_clock_sample_sequence <= latest_clock_sample_sequence
                )
            )
        ),
    CONSTRAINT ck_market_observation_runs_retention
        CHECK (retention_until > created_at),
    CONSTRAINT ck_market_observation_runs_state_times
        CHECK (
            (state = 'PLANNED' AND started_at IS NULL AND completed_at IS NULL)
            OR (state = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
            OR (state = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL)
            OR (state = 'INVALID' AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_market_observation_runs_lifecycle_order
        CHECK (
            (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= COALESCE(started_at, created_at))
            AND (state <> 'COMPLETED' OR retention_until > completed_at)
            AND (state <> 'COMPLETED' OR completed_at >= window_end)
        ),
    CONSTRAINT ck_market_observation_runs_activation_clock
        CHECK (
            (state = 'PLANNED' AND activation_clock_sample_sequence IS NULL)
            OR (state IN ('RUNNING', 'COMPLETED') AND activation_clock_sample_sequence IS NOT NULL)
            OR state = 'INVALID'
        )
);

CREATE TABLE market_observation_expected_tickers (
    run_id    UUID        NOT NULL,
    provider  VARCHAR(40) NOT NULL,
    ticker    VARCHAR(10) NOT NULL,
    ordinal   INTEGER     NOT NULL,
    CONSTRAINT pk_market_observation_expected_tickers
        PRIMARY KEY (run_id, provider, ticker),
    CONSTRAINT uq_market_observation_expected_tickers_ordinal
        UNIQUE (run_id, provider, ordinal),
    CONSTRAINT fk_market_observation_expected_tickers_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT ck_market_observation_expected_tickers_ticker
        CHECK (ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_expected_tickers_ordinal
        CHECK (ordinal >= 0)
);

CREATE TABLE market_observation_semantics (
    run_id                           UUID         NOT NULL,
    provider                         VARCHAR(40)  NOT NULL,
    venue                            VARCHAR(16)  NOT NULL,
    session                          VARCHAR(24)  NOT NULL,
    candle_interval                  VARCHAR(16)  NOT NULL,
    timestamp_origin                 VARCHAR(24)  NOT NULL,
    timestamp_precision_micros       BIGINT,
    provider_zone_id                 VARCHAR(64),
    candle_time_convention           VARCHAR(24)  NOT NULL,
    adjustment_mode                  VARCHAR(24)  NOT NULL,
    correction_policy                VARCHAR(24)  NOT NULL,
    empty_minute_policy              VARCHAR(32)  NOT NULL,
    volume_unit                      VARCHAR(24)  NOT NULL,
    provider_event_id_scope          VARCHAR(24)  NOT NULL,
    document_evidence_id             VARCHAR(120) NOT NULL,
    document_evidence_sha256         CHAR(64)     NOT NULL,
    confirmed_at                     TIMESTAMPTZ,
    CONSTRAINT pk_market_observation_semantics PRIMARY KEY (run_id, provider),
    CONSTRAINT fk_market_observation_semantics_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT ck_market_observation_semantics_venue
        CHECK (venue IN ('UNKNOWN', 'KRX', 'NXT', 'INTEGRATED')),
    CONSTRAINT ck_market_observation_semantics_session
        CHECK (session IN ('UNKNOWN', 'REGULAR', 'PRE_MARKET', 'AFTER_HOURS')),
    CONSTRAINT ck_market_observation_semantics_interval
        CHECK (candle_interval IN ('UNKNOWN', 'ONE_MINUTE')),
    CONSTRAINT ck_market_observation_semantics_timestamp_origin
        CHECK (timestamp_origin IN (
            'UNKNOWN', 'PROVIDER_EVENT', 'PROVIDER_CANDLE', 'SERVER_RECEIVE', 'NOT_APPLICABLE'
        )),
    CONSTRAINT ck_market_observation_semantics_precision
        CHECK (timestamp_precision_micros IS NULL OR timestamp_precision_micros > 0),
    CONSTRAINT ck_market_observation_semantics_candle_time
        CHECK (candle_time_convention IN ('UNKNOWN', 'START', 'END', 'NOT_APPLICABLE')),
    CONSTRAINT ck_market_observation_semantics_adjustment
        CHECK (adjustment_mode IN ('UNKNOWN', 'UNADJUSTED', 'ADJUSTED', 'NOT_APPLICABLE')),
    CONSTRAINT ck_market_observation_semantics_correction
        CHECK (correction_policy IN (
            'UNKNOWN', 'EVENT_REVISION', 'CANDLE_REVISION', 'NOT_SUPPORTED', 'NOT_APPLICABLE'
        )),
    CONSTRAINT ck_market_observation_semantics_empty_minute
        CHECK (empty_minute_policy IN (
            'UNKNOWN', 'OMIT', 'CARRY_FORWARD_ZERO_VOLUME', 'EXPLICIT_ZERO_VOLUME', 'NOT_APPLICABLE'
        )),
    CONSTRAINT ck_market_observation_semantics_volume_unit
        CHECK (volume_unit IN ('UNKNOWN', 'SHARES', 'LOTS', 'CONTRACTS', 'NOT_APPLICABLE')),
    CONSTRAINT ck_market_observation_semantics_event_id_scope
        CHECK (provider_event_id_scope IN (
            'UNKNOWN', 'NONE', 'CONNECTION', 'SYMBOL', 'GLOBAL', 'NOT_APPLICABLE'
        )),
    CONSTRAINT ck_market_observation_semantics_evidence_id
        CHECK (document_evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'),
    CONSTRAINT ck_market_observation_semantics_evidence_sha256
        CHECK (document_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_market_observation_semantics_confirmation
        CHECK (
            confirmed_at IS NULL
            OR (
                venue <> 'UNKNOWN'
                AND session <> 'UNKNOWN'
                AND candle_interval <> 'UNKNOWN'
                AND timestamp_origin <> 'UNKNOWN'
                AND candle_time_convention <> 'UNKNOWN'
                AND adjustment_mode <> 'UNKNOWN'
                AND correction_policy <> 'UNKNOWN'
                AND empty_minute_policy <> 'UNKNOWN'
                AND volume_unit <> 'UNKNOWN'
                AND provider_event_id_scope <> 'UNKNOWN'
            )
        )
);

CREATE TABLE market_observation_clock_samples (
    run_id                     UUID        NOT NULL,
    provider                   VARCHAR(40) NOT NULL,
    sample_sequence            BIGINT      NOT NULL,
    sampled_at                 TIMESTAMPTZ NOT NULL,
    local_clock_offset_micros  BIGINT      NOT NULL,
    uncertainty_micros         BIGINT      NOT NULL,
    is_synchronized            BOOLEAN     NOT NULL,
    clock_source               VARCHAR(16) NOT NULL,
    inserted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_observation_clock_samples
        PRIMARY KEY (run_id, provider, sample_sequence),
    CONSTRAINT fk_market_observation_clock_samples_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT ck_market_observation_clock_samples_sequence
        CHECK (sample_sequence >= 0),
    CONSTRAINT ck_market_observation_clock_samples_uncertainty
        CHECK (uncertainty_micros >= 0),
    CONSTRAINT ck_market_observation_clock_samples_source
        CHECK (clock_source IN ('CHRONY', 'NTP', 'SYSTEM', 'SYNTHETIC'))
);

COMMENT ON COLUMN market_observation_clock_samples.local_clock_offset_micros IS
    'Signed local clock minus trusted reference clock, in microseconds';

CREATE INDEX idx_market_observation_clock_samples_latest
    ON market_observation_clock_samples (run_id, provider, sampled_at DESC, sample_sequence DESC);

ALTER TABLE market_observation_runs
    ADD CONSTRAINT fk_market_observation_runs_latest_clock_sample
        FOREIGN KEY (run_id, provider, latest_clock_sample_sequence)
        REFERENCES market_observation_clock_samples (run_id, provider, sample_sequence)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_market_observation_runs_activation_clock_sample
        FOREIGN KEY (run_id, provider, activation_clock_sample_sequence)
        REFERENCES market_observation_clock_samples (run_id, provider, sample_sequence)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE market_observation_rest_polls (
    run_id                   UUID        NOT NULL,
    provider                 VARCHAR(40) NOT NULL,
    ticker                   VARCHAR(10) NOT NULL,
    request_id               UUID        NOT NULL,
    observed_at              TIMESTAMPTZ NOT NULL,
    request_started_at       TIMESTAMPTZ NOT NULL,
    normalized_at            TIMESTAMPTZ,
    requested_from           TIMESTAMPTZ NOT NULL,
    requested_to             TIMESTAMPTZ NOT NULL,
    outcome                  VARCHAR(24) NOT NULL,
    http_status              INTEGER,
    retry_after_millis       BIGINT,
    rate_limit_remaining     BIGINT,
    rate_limit_reset_at      TIMESTAMPTZ,
    round_robin_position     INTEGER,
    returned_candle_count    INTEGER     NOT NULL,
    eligible_candle_count    INTEGER     NOT NULL,
    inserted_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_observation_rest_polls
        PRIMARY KEY (run_id, provider, request_id),
    CONSTRAINT uq_market_observation_rest_polls_request_ticker
        UNIQUE (run_id, provider, request_id, ticker),
    CONSTRAINT fk_market_observation_rest_polls_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT fk_market_observation_rest_polls_expected_ticker
        FOREIGN KEY (run_id, provider, ticker)
        REFERENCES market_observation_expected_tickers (run_id, provider, ticker),
    CONSTRAINT ck_market_observation_rest_polls_ticker
        CHECK (ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_rest_polls_range
        CHECK (requested_from < requested_to),
    CONSTRAINT ck_market_observation_rest_polls_timestamps
        CHECK (
            observed_at >= request_started_at
            AND (normalized_at IS NULL OR normalized_at >= observed_at)
        ),
    CONSTRAINT ck_market_observation_rest_polls_outcome
        CHECK (outcome IN ('SUCCESS', 'HTTP_ERROR', 'NETWORK_ERROR', 'PARSE_ERROR')),
    CONSTRAINT ck_market_observation_rest_polls_http_status
        CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_market_observation_rest_polls_retry_after
        CHECK (retry_after_millis IS NULL OR retry_after_millis >= 0),
    CONSTRAINT ck_market_observation_rest_polls_rate_limit
        CHECK (rate_limit_remaining IS NULL OR rate_limit_remaining >= 0),
    CONSTRAINT ck_market_observation_rest_polls_round_robin
        CHECK (round_robin_position IS NULL OR round_robin_position >= 0),
    CONSTRAINT ck_market_observation_rest_polls_candle_count
        CHECK (
            returned_candle_count >= 0
            AND eligible_candle_count BETWEEN 0 AND returned_candle_count
        ),
    CONSTRAINT ck_market_observation_rest_polls_success
        CHECK (
            outcome <> 'SUCCESS'
            OR (http_status BETWEEN 200 AND 299 AND normalized_at IS NOT NULL)
        )
);

CREATE INDEX idx_market_observation_rest_polls_observed
    ON market_observation_rest_polls (run_id, provider, ticker, observed_at);

CREATE TABLE market_observation_ticks (
    run_id                  UUID         NOT NULL,
    provider                VARCHAR(40)  NOT NULL,
    expected_ticker         VARCHAR(10)  NOT NULL,
    reported_ticker         VARCHAR(10),
    connection_epoch        UUID         NOT NULL,
    local_receive_sequence  BIGINT       NOT NULL,
    clock_sample_sequence   BIGINT,
    provider_event_id       VARCHAR(120),
    provider_occurred_at    TIMESTAMPTZ,
    socket_received_at      TIMESTAMPTZ  NOT NULL,
    normalized_at           TIMESTAMPTZ,
    price                   INTEGER,
    quantity                BIGINT,
    outcome                 VARCHAR(24)  NOT NULL,
    inserted_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_observation_ticks
        PRIMARY KEY (run_id, provider, connection_epoch, local_receive_sequence),
    CONSTRAINT fk_market_observation_ticks_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT fk_market_observation_ticks_expected_ticker
        FOREIGN KEY (run_id, provider, expected_ticker)
        REFERENCES market_observation_expected_tickers (run_id, provider, ticker),
    CONSTRAINT fk_market_observation_ticks_clock_sample
        FOREIGN KEY (run_id, provider, clock_sample_sequence)
        REFERENCES market_observation_clock_samples (run_id, provider, sample_sequence),
    CONSTRAINT ck_market_observation_ticks_expected_ticker
        CHECK (expected_ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_ticks_reported_ticker
        CHECK (reported_ticker IS NULL OR reported_ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_ticks_sequence
        CHECK (local_receive_sequence >= 0),
    CONSTRAINT ck_market_observation_ticks_price
        CHECK (price IS NULL OR price > 0),
    CONSTRAINT ck_market_observation_ticks_quantity
        CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT ck_market_observation_ticks_normalized_at
        CHECK (normalized_at IS NULL OR normalized_at >= socket_received_at),
    CONSTRAINT ck_market_observation_ticks_outcome
        CHECK (outcome IN ('ACCEPTED', 'DUPLICATE', 'TOO_LATE', 'INVALID', 'MISROUTED')),
    CONSTRAINT ck_market_observation_ticks_normalized_fields
        CHECK (
            outcome IN ('INVALID', 'MISROUTED')
            OR (
                provider_occurred_at IS NOT NULL
                AND clock_sample_sequence IS NOT NULL
                AND normalized_at IS NOT NULL
                AND price IS NOT NULL
                AND quantity IS NOT NULL
            )
        ),
    CONSTRAINT ck_market_observation_ticks_ticker_outcome
        CHECK (
            (outcome IN ('ACCEPTED', 'DUPLICATE', 'TOO_LATE') AND reported_ticker = expected_ticker)
            OR (outcome = 'MISROUTED' AND reported_ticker IS NOT NULL AND reported_ticker <> expected_ticker)
            OR outcome = 'INVALID'
        )
);

CREATE INDEX idx_market_observation_ticks_occurred
    ON market_observation_ticks (run_id, provider, expected_ticker, provider_occurred_at);

CREATE INDEX idx_market_observation_ticks_provider_event
    ON market_observation_ticks (run_id, provider, connection_epoch, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

CREATE INDEX idx_market_observation_ticks_clock_sample
    ON market_observation_ticks (run_id, provider, clock_sample_sequence)
    WHERE clock_sample_sequence IS NOT NULL;

CREATE TABLE market_observation_candles (
    run_id                UUID         NOT NULL,
    provider              VARCHAR(40)  NOT NULL,
    ticker                VARCHAR(10)  NOT NULL,
    bucket_start          TIMESTAMPTZ  NOT NULL,
    observation_sequence  BIGINT       NOT NULL,
    provider_revision     VARCHAR(120),
    observed_at           TIMESTAMPTZ  NOT NULL,
    source                VARCHAR(32)  NOT NULL,
    open                  INTEGER      NOT NULL,
    high                  INTEGER      NOT NULL,
    low                   INTEGER      NOT NULL,
    close                 INTEGER      NOT NULL,
    volume                BIGINT       NOT NULL,
    trade_count           BIGINT,
    is_final              BOOLEAN,
    adjusted              BOOLEAN,
    rest_request_id       UUID,
    inserted_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_observation_candles
        PRIMARY KEY (run_id, provider, ticker, bucket_start, observation_sequence, source),
    CONSTRAINT fk_market_observation_candles_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT fk_market_observation_candles_expected_ticker
        FOREIGN KEY (run_id, provider, ticker)
        REFERENCES market_observation_expected_tickers (run_id, provider, ticker),
    CONSTRAINT fk_market_observation_candles_rest_poll
        FOREIGN KEY (run_id, provider, rest_request_id, ticker)
        REFERENCES market_observation_rest_polls (run_id, provider, request_id, ticker),
    CONSTRAINT ck_market_observation_candles_ticker
        CHECK (ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_candles_sequence
        CHECK (observation_sequence >= 0),
    CONSTRAINT ck_market_observation_candles_observed_at
        CHECK (observed_at >= bucket_start),
    CONSTRAINT ck_market_observation_candles_source
        CHECK (source IN ('PROVIDER_REST', 'LOCAL_AGGREGATE')),
    CONSTRAINT ck_market_observation_candles_prices_positive
        CHECK (open > 0 AND high > 0 AND low > 0 AND close > 0),
    CONSTRAINT ck_market_observation_candles_high
        CHECK (high >= open AND high >= low AND high >= close),
    CONSTRAINT ck_market_observation_candles_low
        CHECK (low <= open AND low <= high AND low <= close),
    CONSTRAINT ck_market_observation_candles_volume
        CHECK (volume >= 0),
    CONSTRAINT ck_market_observation_candles_trade_count
        CHECK (trade_count IS NULL OR trade_count >= 0),
    CONSTRAINT ck_market_observation_candles_rest_reference
        CHECK (
            (source = 'PROVIDER_REST' AND rest_request_id IS NOT NULL)
            OR (source = 'LOCAL_AGGREGATE' AND rest_request_id IS NULL)
        )
);

CREATE INDEX idx_market_observation_candles_observed
    ON market_observation_candles (run_id, provider, ticker, bucket_start, observed_at);

CREATE INDEX idx_market_observation_candles_revision
    ON market_observation_candles (run_id, provider, ticker, bucket_start, provider_revision)
    WHERE provider_revision IS NOT NULL;

CREATE INDEX idx_market_observation_candles_rest_request
    ON market_observation_candles (run_id, provider, rest_request_id, ticker)
    WHERE rest_request_id IS NOT NULL;

CREATE TABLE market_observation_fault_events (
    run_id            UUID        NOT NULL,
    provider          VARCHAR(40) NOT NULL,
    fault_id          UUID        NOT NULL,
    event_sequence    BIGINT      NOT NULL,
    event_type        VARCHAR(32) NOT NULL,
    observed_at       TIMESTAMPTZ NOT NULL,
    ticker            VARCHAR(10),
    connection_epoch  UUID,
    gap_from          TIMESTAMPTZ,
    gap_to            TIMESTAMPTZ,
    http_status       INTEGER,
    inserted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_market_observation_fault_events
        PRIMARY KEY (run_id, provider, fault_id, event_sequence),
    CONSTRAINT fk_market_observation_fault_events_run
        FOREIGN KEY (run_id, provider)
        REFERENCES market_observation_runs (run_id, provider),
    CONSTRAINT fk_market_observation_fault_events_expected_ticker
        FOREIGN KEY (run_id, provider, ticker)
        REFERENCES market_observation_expected_tickers (run_id, provider, ticker),
    CONSTRAINT ck_market_observation_fault_events_sequence
        CHECK (event_sequence >= 0),
    CONSTRAINT ck_market_observation_fault_events_type
        CHECK (event_type IN (
            'FAULT_INJECTED', 'DISCONNECTED', 'RECONNECT_STARTED', 'RECONNECTED',
            'RESUBSCRIBE_STARTED', 'RESUBSCRIBED', 'BACKFILL_STARTED', 'BACKFILL_COMPLETED',
            'RECOVERY_VERIFIED', 'RECOVERY_FAILED'
        )),
    CONSTRAINT ck_market_observation_fault_events_ticker
        CHECK (ticker IS NULL OR ticker ~ '^[0-9]{6}$'),
    CONSTRAINT ck_market_observation_fault_events_gap_pair
        CHECK ((gap_from IS NULL) = (gap_to IS NULL)),
    CONSTRAINT ck_market_observation_fault_events_gap_range
        CHECK (gap_from IS NULL OR gap_from < gap_to),
    CONSTRAINT ck_market_observation_fault_events_http_status
        CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);

CREATE INDEX idx_market_observation_fault_events_observed
    ON market_observation_fault_events (run_id, provider, observed_at);

CREATE TABLE market_observation_cleanup_audits (
    cleanup_id             UUID        NOT NULL,
    run_id                 UUID        NOT NULL,
    provider               VARCHAR(40) NOT NULL,
    result                 VARCHAR(32) NOT NULL,
    terminal_state         VARCHAR(16),
    retention_until        TIMESTAMPTZ,
    requested_at           TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ NOT NULL,
    semantics_deleted      BIGINT      NOT NULL,
    expected_tickers_deleted BIGINT    NOT NULL,
    clock_samples_deleted  BIGINT      NOT NULL,
    rest_polls_deleted     BIGINT      NOT NULL,
    ticks_deleted          BIGINT      NOT NULL,
    candles_deleted        BIGINT      NOT NULL,
    fault_events_deleted   BIGINT      NOT NULL,
    CONSTRAINT pk_market_observation_cleanup_audits PRIMARY KEY (cleanup_id),
    CONSTRAINT ck_market_observation_cleanup_audits_provider
        CHECK (provider ~ '^[a-z0-9][a-z0-9._-]*$'),
    CONSTRAINT ck_market_observation_cleanup_audits_result
        CHECK (result IN (
            'DELETED', 'SKIPPED_NOT_FOUND', 'SKIPPED_NOT_TERMINAL', 'SKIPPED_NOT_EXPIRED'
        )),
    CONSTRAINT ck_market_observation_cleanup_audits_state
        CHECK (terminal_state IS NULL OR terminal_state IN ('COMPLETED', 'INVALID')),
    CONSTRAINT ck_market_observation_cleanup_audits_counts
        CHECK (
            semantics_deleted >= 0
            AND expected_tickers_deleted >= 0
            AND clock_samples_deleted >= 0
            AND rest_polls_deleted >= 0
            AND ticks_deleted >= 0
            AND candles_deleted >= 0
            AND fault_events_deleted >= 0
        )
);

CREATE INDEX idx_market_observation_cleanup_audits_scope
    ON market_observation_cleanup_audits (run_id, provider, requested_at);

CREATE INDEX idx_market_observation_runs_expired
    ON market_observation_runs (retention_until, run_id, provider);
