package me.neveux.ccx.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Quips")
class QuipsTest {

    @Nested
    @DisplayName("pick")
    class Pick {

        @Test
        @DisplayName("returns an in-bounds element for the full 0..1000 seed range")
        void pick_when_nonNegativeSeeds_then_alwaysValidElement() {
            for (int seed = 0; seed <= 1000; seed++) {
                assertThat(Quips.pick(Quips.ALLGOOD, seed))
                    .as("seed %d", seed)
                    .isNotNull()
                    .isIn((Object[]) Quips.ALLGOOD);
            }
        }

        @Test
        @DisplayName("returns an in-bounds element for negative seeds")
        void pick_when_negativeSeeds_then_alwaysValidElement() {
            for (int seed = -1000; seed < 0; seed++) {
                assertThat(Quips.pick(Quips.ENABLE, seed))
                    .as("seed %d", seed)
                    .isNotNull()
                    .isIn((Object[]) Quips.ENABLE);
            }
        }

        // LATENT BUG: Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (still negative),
        // so pick(cat, Integer.MIN_VALUE) yields a negative index -> AIOOBE.
        // This test asserts the CORRECT behavior and is expected to FAIL against the
        // current implementation. Do not weaken it — it is the red signal for the fix.
        @Test
        @DisplayName("returns a valid element for Integer.MIN_VALUE (does not throw)")
        void pick_doesNotThrow_forIntegerMinValue() {
            assertThatCode(() -> Quips.pick(Quips.ALLGOOD, Integer.MIN_VALUE))
                .doesNotThrowAnyException();
            assertThat(Quips.pick(Quips.ALLGOOD, Integer.MIN_VALUE))
                .isNotNull()
                .isIn((Object[]) Quips.ALLGOOD);
        }

        @Test
        @DisplayName("is deterministic — same seed yields the same element")
        void pick_when_sameSeed_then_sameResult() {
            assertThat(Quips.pick(Quips.ALLGOOD, 42)).isEqualTo(Quips.pick(Quips.ALLGOOD, 42));
        }
    }

    @Nested
    @DisplayName("IMPOSSIBLE bank (plan 038 global-plugin toggle)")
    class Impossible {

        @Test
        @DisplayName("exists and is non-empty")
        void impossible_isNonEmpty() {
            assertThat(Quips.IMPOSSIBLE)
                .as("IMPOSSIBLE quip bank must be declared and populated")
                .isNotNull()
                .isNotEmpty();
        }

        @Test
        @DisplayName("pick returns an in-bounds element for varied seeds incl. negative and Integer.MIN_VALUE")
        void impossible_pick_alwaysInBounds() {
            for (int seed : new int[] {0, 1, 7, 42, -1, -1000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                assertThat(Quips.pick(Quips.IMPOSSIBLE, seed))
                    .as("seed %d", seed)
                    .isNotNull()
                    .isIn((Object[]) Quips.IMPOSSIBLE);
            }
        }

        @Test
        @DisplayName("pick is deterministic — same seed yields the same element")
        void impossible_pick_isDeterministic() {
            assertThat(Quips.pick(Quips.IMPOSSIBLE, 42))
                .isEqualTo(Quips.pick(Quips.IMPOSSIBLE, 42));
        }
    }

}
