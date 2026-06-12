-- GrowAnt 초기 스키마 — users·positions·trades (스펙 §4)
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    provider    VARCHAR(20)  NOT NULL,
    nickname    VARCHAR(20)  NOT NULL,
    cash        BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_provider_nickname UNIQUE (provider, nickname)
);

CREATE TABLE positions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    ticker     VARCHAR(10) NOT NULL,
    qty        INT         NOT NULL,
    avg_price  INT         NOT NULL,
    CONSTRAINT uq_positions_user_ticker UNIQUE (user_id, ticker)
);

CREATE TABLE trades (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    ticker       VARCHAR(10) NOT NULL,
    name         VARCHAR(40) NOT NULL,
    is_buy       BOOLEAN     NOT NULL,
    price        INT         NOT NULL,
    qty          INT         NOT NULL,
    amount       BIGINT      NOT NULL,
    executed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trades_user_executed ON trades (user_id, executed_at DESC);
