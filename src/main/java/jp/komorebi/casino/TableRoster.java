package jp.komorebi.casino;

import java.util.List;

/** みんなで遊ぶ卓（麻雀・BJ）の「同じ卓の人」を教える係。卓の中だけのチャット（tchat）で使う */
public interface TableRoster {

    /** 卓の種類（"mj" / "bj"） */
    String kind();

    /** その人と同じ卓についている人（本人もふくむ）。卓にいなければ空 */
    List<Long> mates(long playerId);
}
