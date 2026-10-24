-- v0.30.4：みんなの麻雀の卓のようすを保存しておく（サーバーを再起動・更新しても、対局を続けられるように）
-- 行は id=1 の1つだけ
CREATE TABLE mj_state (
  id          INT           PRIMARY KEY,
  doc         VARCHAR(1000000) NOT NULL,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
