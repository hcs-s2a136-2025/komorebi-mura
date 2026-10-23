-- v0.27.1：管理人室からプレイヤーを削除できるように（記録は残して、削除した印だけつける）
-- 削除した人のトークンが来たら「削除された」と伝えて、その端末のデータも消してもらう
ALTER TABLE player ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
