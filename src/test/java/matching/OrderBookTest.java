package matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    @Test
    void emptyBookHasNoBestPrices() {
        OrderBook book = new OrderBook();
        assertTrue(book.isEmpty());
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    void bestBidIsHighestPrice() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.BUY, 9_900, 10));
        book.addResting(Order.limit(2, Side.BUY, 9_950, 10));
        book.addResting(Order.limit(3, Side.BUY, 9_800, 10));
        assertEquals(OptionalLong.of(9_950), book.bestBid());
    }

    @Test
    void bestAskIsLowestPrice() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.SELL, 10_100, 10));
        book.addResting(Order.limit(2, Side.SELL, 10_050, 10));
        book.addResting(Order.limit(3, Side.SELL, 10_200, 10));
        assertEquals(OptionalLong.of(10_050), book.bestAsk());
    }

    @Test
    void oneSidedBookHasNoPriceOnOtherSide() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.BUY, 9_900, 10));
        assertEquals(OptionalLong.of(9_900), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    void ordersAtSamePriceShareOneLevelInArrivalOrder() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.BUY, 9_900, 10));
        book.addResting(Order.limit(2, Side.BUY, 9_900, 20));
        book.addResting(Order.limit(3, Side.BUY, 9_900, 30));
        assertEquals(1, book.levelCount(Side.BUY));
        assertEquals(List.of(
                new BookEntry(Side.BUY, 9_900, 1, 10),
                new BookEntry(Side.BUY, 9_900, 2, 20),
                new BookEntry(Side.BUY, 9_900, 3, 30)), book.snapshot());
    }

    @Test
    void snapshotListsEachSideBestPriceFirst() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.BUY, 9_800, 1));
        book.addResting(Order.limit(2, Side.BUY, 9_900, 1));
        book.addResting(Order.limit(3, Side.SELL, 10_200, 1));
        book.addResting(Order.limit(4, Side.SELL, 10_100, 1));
        assertEquals(List.of(
                new BookEntry(Side.BUY, 9_900, 2, 1),
                new BookEntry(Side.BUY, 9_800, 1, 1),
                new BookEntry(Side.SELL, 10_100, 4, 1),
                new BookEntry(Side.SELL, 10_200, 3, 1)), book.snapshot());
    }

    @Test
    void emptiedLevelIsRemovedAndNextLevelBecomesBest() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.SELL, 10_000, 5));
        book.addResting(Order.limit(2, Side.SELL, 10_100, 5));

        book.fillFront(Side.SELL, book.bestLevel(Side.SELL), 5);

        assertEquals(1, book.levelCount(Side.SELL));
        assertEquals(OptionalLong.of(10_100), book.bestAsk());
    }

    @Test
    void nonEmptyLevelIsKept() {
        OrderBook book = new OrderBook();
        book.addResting(Order.limit(1, Side.SELL, 10_000, 5));
        book.fillFront(Side.SELL, book.bestLevel(Side.SELL), 2);
        assertEquals(OptionalLong.of(10_000), book.bestAsk());
        assertEquals(List.of(new BookEntry(Side.SELL, 10_000, 1, 3)), book.snapshot());
    }

    @Test
    void marketOrdersCannotRest() {
        OrderBook book = new OrderBook();
        assertThrows(IllegalArgumentException.class, () -> book.addResting(Order.market(1, Side.BUY, 10)));
    }
}
