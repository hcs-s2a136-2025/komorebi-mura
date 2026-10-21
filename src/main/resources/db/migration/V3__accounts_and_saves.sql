-- v0.9.3：なまえ＋合言葉でログインして、持ち物やお金をサーバーに保存する

-- 合言葉（BCryptのハッシュだけ保存。合言葉そのものは保存しない）
ALTER TABLE player ADD COLUMN pass_hash VARCHAR(100);
CREATE INDEX idx_player_name ON player (name);

-- プレイヤーごとのセーブデータ（ゲームの saveText() の中身そのまま）
CREATE TABLE player_save (
  player_id  BIGINT           PRIMARY KEY REFERENCES player (id),
  doc        VARCHAR(1000000) NOT NULL,
  rev        BIGINT           NOT NULL,
  saved_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
