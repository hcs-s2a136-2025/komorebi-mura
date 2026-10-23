-- v0.28.4：ほかの人の畑・家畜・ペットのようす（見るだけ）を、サーバーを再起動しても覚えておく
-- 1人1行。中身は最後に送られてきたようす（JSON）
CREATE TABLE farm_snapshot (
  player_id   BIGINT        PRIMARY KEY REFERENCES player (id),
  doc         VARCHAR(60000) NOT NULL,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
