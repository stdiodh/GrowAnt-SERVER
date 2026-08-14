CREATE TABLE minute_candles (
    ticker             VARCHAR(10)  NOT NULL,
    bucket_start       TIMESTAMPTZ  NOT NULL,
    open               INTEGER      NOT NULL,
    high               INTEGER      NOT NULL,
    low                INTEGER      NOT NULL,
    close              INTEGER      NOT NULL,
    volume             BIGINT       NOT NULL,
    trade_count        BIGINT       NOT NULL,
    revision           INTEGER      NOT NULL DEFAULT 0,
    is_final           BOOLEAN      NOT NULL DEFAULT FALSE,
    source             VARCHAR(40)  NOT NULL,
    source_updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_minute_candles PRIMARY KEY (ticker, bucket_start),
    CONSTRAINT ck_minute_candles_prices_positive
        CHECK (open > 0 AND high > 0 AND low > 0 AND close > 0),
    CONSTRAINT ck_minute_candles_high
        CHECK (high >= open AND high >= low AND high >= close),
    CONSTRAINT ck_minute_candles_low
        CHECK (low <= open AND low <= high AND low <= close),
    CONSTRAINT ck_minute_candles_volume_non_negative CHECK (volume >= 0),
    CONSTRAINT ck_minute_candles_trade_count_non_negative CHECK (trade_count >= 0),
    CONSTRAINT ck_minute_candles_revision_non_negative CHECK (revision >= 0)
);
