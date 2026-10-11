package matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Differential fuzz test: drive MatchingEngine and the naive ReferenceEngine with the
 * same random stream of orders and cancels, and require identical behaviour.
 *
 * Reproduce a failure:   mvn test -Dtest=DifferentialFuzzTest -Dfuzz.seed=<seed>
 * Soak with a new seed:  mvn test -Dtest=DifferentialFuzzTest -Dfuzz.seed=random -Dfuzz.ops=1000000
 */
class DifferentialFuzzTest {

    private static final long DEFAULT_SEED = 20261011L;
    private static final int DEFAULT_OPS = 100_000;

    private static final long MID_PRICE = 10_000;
    private static final int PRICE_BAND = 50;          // limit prices fall in MID +/- 50 ticks
    private static final int MAX_QTY = 100;
    private static final int TARGET_DEPTH = 500;       // resting orders the book hovers around
    private static final int ANY_ID_CANCEL_PCT = 3;    // cancels of random ids: filled, cancelled or unknown
    private static final int RECENT_ID_WINDOW = 50;    // "recent" for the cancel/fill race
    private static final int MARKET_ORDER_PCT = 8;     // share of new orders that are market orders

    @Test
    void engineMatchesReferenceOnRandomOrderFlow() {
        long seed = readSeed();
        int ops = Integer.getInteger("fuzz.ops", DEFAULT_OPS);
        try {
            run(seed, ops);
        } catch (Throwable t) {
            throw new AssertionError("Fuzz failure with seed=" + seed + ". Reproduce with: "
                    + "mvn test -Dtest=DifferentialFuzzTest -Dfuzz.seed=" + seed, t);
        }
    }

    private static long readSeed() {
        String property = System.getProperty("fuzz.seed");
        if (property == null) {
            return DEFAULT_SEED;
        }
        if (property.equals("random")) {
            long seed = System.nanoTime();
            System.out.println("DifferentialFuzzTest seed=" + seed);
            return seed;
        }
        return Long.parseLong(property);
    }

    private static void run(long seed, int ops) {
        Random random = new Random(seed);
        MatchingEngine engine = new MatchingEngine();
        ReferenceEngine reference = new ReferenceEngine();

        long nextId = 1;
        // Running totals for the conservation check.
        long submittedQty = 0;
        long tradedQty = 0;      // each traded unit fills one resting AND one aggressing unit
        long cancelledQty = 0;
        long discardedQty = 0;   // unfilled remainder of market orders
        int maxBookSize = 0;
        List<BookEntry> book = List.of();

        for (int op = 0; op < ops; op++) {
            String where = "op " + op;
            int roll = random.nextInt(100);
            // Cancel live orders more aggressively when the book is deep, so its size hovers
            // around TARGET_DEPTH instead of drifting to empty or growing without bound.
            int liveCancelPct = book.size() < TARGET_DEPTH ? 5 : 30;

            if (roll < ANY_ID_CANCEL_PCT + liveCancelPct) {
                long id = roll < ANY_ID_CANCEL_PCT || book.isEmpty()
                        ? randomAnyId(random, nextId)
                        : book.get(random.nextInt(book.size())).orderId();
                long actual = engine.cancel(id);
                long expected = reference.cancel(id);
                assertEquals(expected, actual, where + ": cancel(" + id + ") quantity");
                cancelledQty += actual;
            } else {
                boolean market = random.nextInt(100) < MARKET_ORDER_PCT;
                Order order = randomOrder(random, nextId++, market);
                List<Trade> actual = engine.submit(order);
                List<Trade> expected = reference.submit(order);
                assertEquals(expected, actual, where + ": trades for " + order);

                long filled = 0;
                for (Trade t : actual) {
                    filled += t.quantity();
                    assertTradeRespectsLimit(order, t, where);
                }
                submittedQty += order.quantity();
                tradedQty += filled;
                if (order.type() == OrderType.MARKET) {
                    discardedQty += order.quantity() - filled;
                }
            }

            book = engine.book().snapshot();
            assertEquals(reference.snapshot(), book, where + ": book state");
            assertNotCrossed(engine.book(), where);
            assertFifoWithinLevels(book, where);

            long restingQty = 0;
            for (BookEntry e : book) {
                restingQty += e.remaining();
            }
            assertEquals(submittedQty, 2 * tradedQty + restingQty + cancelledQty + discardedQty,
                    where + ": quantity not conserved");
            maxBookSize = Math.max(maxBookSize, book.size());
        }

        // Guard against a fuzz test that silently stops exercising anything interesting.
        assertTrue(tradedQty > 0, "fuzz run produced no trades");
        assertTrue(cancelledQty > 0, "fuzz run cancelled nothing");
        assertTrue(maxBookSize > TARGET_DEPTH / 2, "book never got deep enough, maxBookSize=" + maxBookSize);
    }

    private static Order randomOrder(Random random, long id, boolean market) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long qty = 1 + random.nextInt(MAX_QTY);
        if (market) {
            return Order.market(id, side, qty);
        }
        long price = MID_PRICE + random.nextInt(2 * PRICE_BAND + 1) - PRICE_BAND;
        return Order.limit(id, side, price, qty);
    }

    /**
     * An id that may or may not be resting, to exercise the "nothing to cancel" path.
     * Half the time a recent id: models the cancel/fill race, where a cancel arrives just after
     * the order filled, while its price level is still on the book. Otherwise any id, including
     * a few never issued.
     */
    private static long randomAnyId(Random random, long nextId) {
        if (random.nextBoolean()) {
            long oldest = Math.max(1, nextId - RECENT_ID_WINDOW);
            return oldest + random.nextInt((int) (nextId - oldest + 1));
        }
        return 1 + random.nextInt((int) Math.min(nextId + 10, Integer.MAX_VALUE));
    }

    private static void assertNotCrossed(OrderBook book, String where) {
        OptionalLong bid = book.bestBid();
        OptionalLong ask = book.bestAsk();
        if (bid.isPresent() && ask.isPresent() && bid.getAsLong() >= ask.getAsLong()) {
            fail(where + ": book crossed, bid=" + bid.getAsLong() + " ask=" + ask.getAsLong());
        }
    }

    /** Ids are issued in arrival order, so FIFO means ids strictly increase within each level. */
    private static void assertFifoWithinLevels(List<BookEntry> book, String where) {
        BookEntry previous = null;
        for (BookEntry e : book) {
            if (previous != null && previous.side() == e.side() && previous.price() == e.price()
                    && previous.orderId() >= e.orderId()) {
                fail(where + ": FIFO violated at " + e.side() + " " + e.price()
                        + ", order " + previous.orderId() + " ahead of " + e.orderId());
            }
            previous = e;
        }
    }

    private static void assertTradeRespectsLimit(Order order, Trade t, String where) {
        if (order.type() != OrderType.LIMIT) {
            return;
        }
        boolean ok = order.side() == Side.BUY ? t.price() <= order.price() : t.price() >= order.price();
        assertTrue(ok, where + ": " + t + " is worse than limit of " + order);
    }
}
