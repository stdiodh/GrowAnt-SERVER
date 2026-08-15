ALTER TABLE market_observation_rest_polls
    ADD COLUMN poll_run_id UUID,
    ADD COLUMN page_ordinal INTEGER,
    ADD COLUMN request_cursor VARCHAR(120),
    ADD COLUMN next_cursor VARCHAR(120),
    ADD COLUMN poll_terminal BOOLEAN;

-- V3 requests had no pagination identity, so each legacy request remains one
-- independently complete logical poll after the forward-only migration.
UPDATE market_observation_rest_polls
SET poll_run_id = request_id,
    page_ordinal = 0,
    poll_terminal = TRUE;

ALTER TABLE market_observation_rest_polls
    ALTER COLUMN poll_run_id SET NOT NULL,
    ALTER COLUMN page_ordinal SET NOT NULL,
    ALTER COLUMN poll_terminal SET NOT NULL,
    ADD CONSTRAINT uq_market_observation_rest_polls_poll_page
        UNIQUE (run_id, provider, ticker, poll_run_id, page_ordinal),
    ADD CONSTRAINT fk_market_observation_rest_polls_poll_root
        FOREIGN KEY (run_id, provider, poll_run_id, ticker)
        REFERENCES market_observation_rest_polls (run_id, provider, request_id, ticker),
    ADD CONSTRAINT ck_market_observation_rest_polls_page_identity
        CHECK (
            (page_ordinal = 0 AND poll_run_id = request_id)
            OR (
                page_ordinal > 0
                AND poll_run_id <> request_id
                AND request_cursor IS NOT NULL
            )
        ),
    ADD CONSTRAINT ck_market_observation_rest_polls_request_cursor
        CHECK (
            request_cursor IS NULL
            OR (
                octet_length(request_cursor) BETWEEN 1 AND 120
                AND request_cursor !~ '[^!-~]'
            )
        ),
    ADD CONSTRAINT ck_market_observation_rest_polls_next_cursor
        CHECK (
            next_cursor IS NULL
            OR (
                octet_length(next_cursor) BETWEEN 1 AND 120
                AND next_cursor !~ '[^!-~]'
            )
        ),
    ADD CONSTRAINT ck_market_observation_rest_polls_cursor_progress
        CHECK (
            request_cursor IS NULL
            OR next_cursor IS NULL
            OR request_cursor <> next_cursor
        ),
    ADD CONSTRAINT ck_market_observation_rest_polls_terminal
        CHECK (
            (
                outcome = 'SUCCESS'
                AND (poll_terminal OR next_cursor IS NOT NULL)
            )
            OR (
                outcome <> 'SUCCESS'
                AND poll_terminal
                AND next_cursor IS NULL
            )
        );
