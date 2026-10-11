package matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CancelTest {

    private final MatchingEngine engine = new MatchingEngine();

    private List<Trade> buy(long id, long price, long qty) {
        return engine.submit(Order.limit(id, Side.BUY, price, qty));
    }

    private List<Trade> sell(long id, long price, long qty) {
        return engine.submit(Order.limit(id, Side.SELL, price, qty));
    }

    @Test
    void cancelRestingOrderRemovesIt() {
        buy(1, 9_900, 10);
        assertEquals(10, engine.cancel(1));
        assertTrue(engine.book().isEmpty());
        assertEquals(OptionalLong.empty(), engine.book().bestBid());
    }

    @Test
    void cancelReturnsOnlyTheUnfilledRemainder() {
        buy(1, 9_900, 10);
        sell(2, 9_900, 4);
        assertEquals(6, engine.cancel(1));
    }

    @Test
    void cancelFullyFilledOrderDoesNothing() {
        buy(1, 9_900, 10);
        sell(2, 9_900, 10);
        assertEquals(0, engine.cancel(1));
    }

    /** Regression: found by DifferentialFuzzTest when filled orders were left in the id index. */
    @Test
    void cancelOfFilledOrderDoesNotDisturbItsOldLevel() {
        buy(1, 9_900, 5);
        buy(2, 9_900, 5);
        sell(3, 9_900, 5);                       // fills order 1; order 2 still rests at 9_900
        assertEquals(0, engine.cancel(1));       // cancel arrives just after the fill
        assertEquals(List.of(new Trade(2, 4, 9_900, 5)), sell(4, 9_900, 5));
    }

    @Test
    void cancelUnknownIdDoesNothing() {
        buy(1, 9_900, 10);
        assertEquals(0, engine.cancel(999));
        assertEquals(List.of(new BookEntry(Side.BUY, 9_900, 1, 10)), engine.book().snapshot());
    }

    @Test
    void cancelTwiceOnlyCancelsOnce() {
        buy(1, 9_900, 10);
        assertEquals(10, engine.cancel(1));
        assertEquals(0, engine.cancel(1));
    }

    @Test
    void cancelledMidQueueOrderIsSkippedByMatching() {
        buy(1, 9_900, 5);
        buy(2, 9_900, 5);
        buy(3, 9_900, 5);
        engine.cancel(2);
        List<Trade> trades = sell(4, 9_900, 10);
        assertEquals(List.of(new Trade(1, 4, 9_900, 5), new Trade(3, 4, 9_900, 5)), trades);
        assertTrue(engine.book().isEmpty());
    }

    @Test
    void cancellingLastLiveOrderRemovesLevelEvenWithDeadEntriesQueued() {
        buy(1, 9_900, 5);
        buy(2, 9_900, 5);
        buy(3, 9_800, 5);
        engine.cancel(2);    // dead entry sits behind order 1
        engine.cancel(1);
        assertEquals(OptionalLong.of(9_800), engine.book().bestBid());
        assertEquals(1, engine.book().levelCount(Side.BUY));
    }

    @Test
    void deadEntriesLingerUntilTheyReachTheFront() {
        buy(1, 9_900, 5);
        buy(2, 9_900, 5);
        buy(3, 9_900, 5);
        engine.cancel(2);
        PriceLevel level = engine.book().bestLevel(Side.BUY);
        assertEquals(3, level.queueSize());       // 2 is still physically in the deque
        sell(4, 9_900, 5);                        // fills 1, so 2 reaches the front and is dropped
        assertEquals(1, level.queueSize());
    }

    @Test
    void cancelledIdCanBeReused() {
        buy(1, 9_900, 5);
        engine.cancel(1);
        buy(1, 9_800, 5);
        assertEquals(List.of(new BookEntry(Side.BUY, 9_800, 1, 5)), engine.book().snapshot());
    }

    @Test
    void duplicateRestingIdIsRejected() {
        buy(1, 9_900, 5);
        assertThrows(IllegalArgumentException.class, () -> buy(1, 9_800, 5));
    }
}
