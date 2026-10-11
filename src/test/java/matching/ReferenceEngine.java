package matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A deliberately naive matching engine used as a test oracle.
 *
 * One flat list of resting orders, O(n) scans for everything, no indexes, no lazy
 * deletion, no clever invariants. It is slow, but each rule of price-time priority is
 * written out directly, so it is easy to convince yourself that it is correct.
 * MatchingEngine must produce exactly the same trades and book as this class.
 */
final class ReferenceEngine {

    private static final class Entry {
        final long id;
        final Side side;
        final long price;
        final long arrival;   // time priority: lower = arrived earlier
        long remaining;

        Entry(long id, Side side, long price, long arrival, long remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.remaining = remaining;
        }
    }

    private final List<Entry> resting = new ArrayList<>();
    private long nextArrival = 0;

    List<Trade> submit(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        long remaining = incoming.quantity();

        while (remaining > 0) {
            Entry best = findBestMatch(incoming);
            if (best == null) {
                break;
            }
            long qty = Math.min(remaining, best.remaining);
            trades.add(new Trade(best.id, incoming.id(), best.price, qty));
            best.remaining -= qty;
            remaining -= qty;
            if (best.remaining == 0) {
                resting.remove(best);
            }
        }

        if (remaining > 0 && incoming.type() == OrderType.LIMIT) {
            resting.add(new Entry(incoming.id(), incoming.side(), incoming.price(), nextArrival++, remaining));
        }
        return trades;
    }

    long cancel(long orderId) {
        for (int i = 0; i < resting.size(); i++) {
            Entry e = resting.get(i);
            if (e.id == orderId) {
                resting.remove(i);
                return e.remaining;
            }
        }
        return 0;
    }

    /** Same shape and order as OrderBook.snapshot(): bids best-first, asks best-first, FIFO in a price. */
    List<BookEntry> snapshot() {
        List<Entry> sorted = new ArrayList<>(resting);
        sorted.sort(Comparator.comparingInt((Entry e) -> e.side == Side.BUY ? 0 : 1)
                .thenComparingLong(e -> e.side == Side.BUY ? -e.price : e.price)
                .thenComparingLong(e -> e.arrival));
        List<BookEntry> out = new ArrayList<>();
        for (Entry e : sorted) {
            out.add(new BookEntry(e.side, e.price, e.id, e.remaining));
        }
        return out;
    }

    /** Scan every resting order; pick the best acceptable price, earliest arrival on ties. */
    private Entry findBestMatch(Order incoming) {
        Entry best = null;
        for (Entry e : resting) {
            if (e.side == incoming.side() || !acceptable(incoming, e.price)) {
                continue;
            }
            if (best == null || isHigherPriority(e, best)) {
                best = e;
            }
        }
        return best;
    }

    private static boolean acceptable(Order incoming, long restingPrice) {
        if (incoming.type() == OrderType.MARKET) {
            return true;
        }
        if (incoming.side() == Side.BUY) {
            return restingPrice <= incoming.price();
        }
        return restingPrice >= incoming.price();
    }

    /** Price priority first (low ask / high bid), then time priority. */
    private static boolean isHigherPriority(Entry a, Entry b) {
        if (a.price != b.price) {
            return a.side == Side.SELL ? a.price < b.price : a.price > b.price;
        }
        return a.arrival < b.arrival;
    }
}
