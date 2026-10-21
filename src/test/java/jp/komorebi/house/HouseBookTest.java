package jp.komorebi.house;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 家の中身と収納箱のルールだけを確かめる（Springなしで動く） */
class HouseBookTest {

    @Test
    void layoutAndSeedsOnlyOnFirstPut() {
        HouseBook b = new HouseBook();
        assertThat(b.putLayout("48,21", "{\"furn\":[]}", Map.of("tomato", 3L), Map.of("gem", 1L))).isTrue();
        assertThat(b.putLayout("48,21", "{\"furn\":[1]}", Map.of("tomato", 99L), null)).isTrue();
        Map<String, Object> v = b.view("48,21", true);
        assertThat(v.get("layout")).isEqualTo("{\"furn\":[1]}");
        assertThat((List<?>) v.get("storage")).hasSize(1);
        assertThat(b.view("48,21", true).get("storage").toString()).contains("n=3");
        assertThat(b.view("48,21", false)).doesNotContainKey("priv");
        assertThat(b.putLayout("bad key", "{}", null, null)).isFalse();
    }

    @Test
    void putAndTake() {
        HouseBook b = new HouseBook();
        assertThat(b.put("1,2", false, "fish_アジ", 5)).isEqualTo(5);
        assertThat(b.take("1,2", false, "fish_アジ", 6)).isEqualTo(-1);
        assertThat(b.take("1,2", false, "fish_アジ", 5)).isZero();
        assertThat(b.take("1,2", false, "fish_アジ", 1)).isEqualTo(-1);
        assertThat(b.put("1,2", true, "<script>", 1)).isEqualTo(-1);
        assertThat(b.put("1,2", true, "gem", 0)).isEqualTo(-1);
    }
}
