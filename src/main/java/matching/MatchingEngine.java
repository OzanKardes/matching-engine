package matching;

import java.util.ArrayList;
import java.util.List;

/**
 * Price-time priority matching.
 *
 * An incoming order trades against the best opposite price first (price priority) and,
 * within that price, against the order that arrived first (time priority). Trades
 * happen at the resting order's price.
 *
 * Single-threaded by design: one thread owns the book, so there are no locks and the
 * sequence of events is fully deterministic.
 */
public final class MatchingEngine {
    private final OrderBook book = new OrderBook();

    public OrderBook book() {
        return book;
    }

    /** Match an incoming order; returns the trades it produced, in execution order. */
    public List<Trade> submit(Order incoming) {
        if (book.contains(incoming.id())) {
            throw new IllegalArgumentException("order id already resting: " + incoming.id());
        }
        List<Trade> trades = new ArrayList<>();
        Side contraSide = incoming.side().opposite();
        long remaining = incoming.quantity();

        while (remaining > 0) {
            PriceLevel level = book.bestLevel(contraSide);
            if (level == null || !crosses(incoming, level.price())) {
                break;
            }
            RestingOrder resting = level.front();
            long fillQty = Math.min(remaining, resting.remaining);
            trades.add(new Trade(resting.id(), incoming.id(), level.price(), fillQty));

            book.fillFront(contraSide, level, fillQty);
            remaining -= fillQty;
        }

        // A limit order's unfilled remainder rests; a market order's is discarded.
        if (remaining > 0 && incoming.type() == OrderType.LIMIT) {
            book.addResting(incoming, remaining);
        }
        return trades;
    }

    /** Cancel a resting order; returns the quantity cancelled, or 0 if it was not resting. */
    public long cancel(long orderId) {
        return book.cancel(orderId);
    }

    /** Can the incoming order trade at this resting price? */
    private static boolean crosses(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        if (incoming.side() == Side.BUY) {
            return restingPrice <= incoming.price();   // buyer accepts any ask at or below its limit
        }
        return restingPrice >= incoming.price();       // seller accepts any bid at or above its limit
    }
}
