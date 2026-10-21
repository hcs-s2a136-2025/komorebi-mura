-- v0.9.6：建てた家の中身（家具の並び・壁紙・床・収納箱）をみんなで同じにする
CREATE TABLE house_room (
  house_key   VARCHAR(16)     PRIMARY KEY,
  doc         VARCHAR(200000) NOT NULL,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
