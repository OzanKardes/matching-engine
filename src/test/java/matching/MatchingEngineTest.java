package matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    private List<Trade> buy(long id, long price, long qty) {
        return engine.submit(Order.limit(id, Side.BUY, price, qty));
    }

    private List<Trade> sell(long id, long price, long qty) {
        return engine.submit(Order.limit(id, Side.SELL, price, qty));
    }

    @Test
    void noMatchWhenPricesDoNotCross() {
        assertEquals(List.of(), buy(1, 9_900, 10));
        assertEquals(List.of(), sell(2, 10_000, 10));
        assertEquals(OptionalLong.of(9_900), engine.book().bestBid());
        assertEquals(OptionalLong.of(10_000), engine.book().bestAsk());
    }

    @Test
    void fullFillAtEqualPrice() {
        sell(1, 10_000, 10);
        assertEquals(List.of(new Trade(1, 2, 10_000, 10)), buy(2, 10_000, 10));
        assertTrue(engine.book().isEmpty());
    }

    @Test
    void tradeHappensAtRestingPriceNotAggressorPrice() {
        sell(1, 10_000, 10);
        // Buyer was willing to pay 10_500 but gets the resting seller's 10_000.
        assertEquals(List.of(new Trade(1, 2, 10_000, 10)), buy(2, 10_500, 10));
    }

    @Test
    void partialFillOfRestingOrderLeavesRemainderOnBook() {
        sell(1, 10_000, 10);
        assertEquals(List.of(new Trade(1, 2, 10_000, 4)), buy(2, 10_000, 4));
        assertEquals(List.of(new BookEntry(Side.SELL, 10_000, 1, 6)), engine.book().snapshot());
    }

    @Test
    void partialFillOfIncomingLimitRestsTheRemainder() {
        sell(1, 10_000, 4);
        assertEquals(List.of(new Trade(1, 2, 10_000, 4)), buy(2, 10_000, 10));
        assertEquals(List.of(new BookEntry(Side.BUY, 10_000, 2, 6)), engine.book().snapshot());
    }

    @Test
    void incomingOrderConsumesMultipleLevelsBestFirst() {
        sell(1, 10_200, 5);
        sell(2, 10_000, 5);
        sell(3, 10_100, 5);
        List<Trade> trades = buy(4, 10_150, 12);
        assertEquals(List.of(
                new Trade(2, 4, 10_000, 5),
                new Trade(3, 4, 10_100, 5)), trades);
        // 10_200 is above the limit so the remaining 2 rest as a bid.
        assertEquals(List.of(
                new BookEntry(Side.BUY, 10_150, 4, 2),
                new BookEntry(Side.SELL, 10_200, 1, 5)), engine.book().snapshot());
    }

    @Test
    void fifoWithinAPriceLevel() {
        buy(1, 9_900, 5);
        buy(2, 9_900, 5);
        buy(3, 9_900, 5);
        List<Trade> trades = sell(4, 9_900, 7);
        assertEquals(List.of(
                new Trade(1, 4, 9_900, 5),
                new Trade(2, 4, 9_900, 2)), trades);
        assertEquals(List.of(
                new BookEntry(Side.BUY, 9_900, 2, 3),
                new BookEntry(Side.BUY, 9_900, 3, 5)), engine.book().snapshot());
    }

    @Test
    void partiallyFilledOrderKeepsItsTimePriority() {
        sell(1, 10_000, 10);
        sell(2, 10_000, 10);
        buy(3, 10_000, 4);   // order 1 now has 6 left, still at the front
        assertEquals(List.of(new Trade(1, 4, 10_000, 6), new Trade(2, 4, 10_000, 1)), buy(4, 10_000, 7));
    }

    @Test
    void betterPriceBeatsEarlierTime() {
        buy(1, 9_900, 5);
        buy(2, 9_950, 5);    // arrived later but pays more
        assertEquals(List.of(new Trade(2, 3, 9_950, 5)), sell(3, 9_900, 5));
    }

    @Test
    void marketOrderOnEmptyBookIsDiscarded() {
        assertEquals(List.of(), engine.submit(Order.market(1, Side.BUY, 10)));
        assertTrue(engine.book().isEmpty());
    }

    @Test
    void marketOrderSweepsLevelsIgnoringPrice() {
        sell(1, 10_000, 5);
        sell(2, 50_000, 5);
        List<Trade> trades = engine.submit(Order.market(3, Side.BUY, 8));
        assertEquals(List.of(new Trade(1, 3, 10_000, 5), new Trade(2, 3, 50_000, 3)), trades);
        assertEquals(List.of(new BookEntry(Side.SELL, 50_000, 2, 2)), engine.book().snapshot());
    }

    @Test
    void unfilledMarketRemainderIsDiscardedNotRested() {
        buy(1, 9_900, 3);
        List<Trade> trades = engine.submit(Order.market(2, Side.SELL, 10));
        assertEquals(List.of(new Trade(1, 2, 9_900, 3)), trades);
        assertTrue(engine.book().isEmpty());
    }

    @Test
    void sellAggressorMatchesHighestBidFirst() {
        buy(1, 9_800, 5);
        buy(2, 9_900, 5);
        List<Trade> trades = sell(3, 9_800, 10);
        assertEquals(List.of(new Trade(2, 3, 9_900, 5), new Trade(1, 3, 9_800, 5)), trades);
        assertTrue(engine.book().isEmpty());
    }

    @Test
    void bookIsNeverLeftCrossed() {
        sell(1, 10_000, 5);
        buy(2, 10_100, 10);  // takes the 5, rests 5 at 10_100
        assertEquals(OptionalLong.of(10_100), engine.book().bestBid());
        assertEquals(OptionalLong.empty(), engine.book().bestAsk());
    }
}
