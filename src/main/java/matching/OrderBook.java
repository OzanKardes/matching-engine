package matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Resting limit orders, grouped by price.
 *
 * Both sides are sorted so that the BEST price is always the FIRST key:
 *   bids: highest price first (reverse order) - the most a buyer will pay
 *   asks: lowest price first (natural order)  - the least a seller will accept
 * So firstEntry() is the best price on either side, and the matching code never has
 * to care which side it is looking at.
 *
 * ordersById indexes every live resting order so cancel can find it in O(1).
 */
public final class OrderBook {
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    private final HashMap<Long, RestingOrder> ordersById = new HashMap<>();

    // Levels near the touch empty and refill constantly. Recycling emptied levels avoids
    // allocating a new PriceLevel + ArrayDeque each time (found by profiling, see README).
    private static final int MAX_SPARE_LEVELS = 256;
    private final ArrayDeque<PriceLevel> spareLevels = new ArrayDeque<>();

    /** Rest a limit order on the book with its full quantity. */
    public void addResting(Order order) {
        addResting(order, order.quantity());
    }

    /** Rest a limit order with the given remaining quantity (after any partial fill). */
    void addResting(Order order, long remaining) {
        if (order.type() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only limit orders can rest, got " + order);
        }
        if (ordersById.containsKey(order.id())) {
            throw new IllegalArgumentException("order id already resting: " + order.id());
        }
        TreeMap<Long, PriceLevel> levels = levelsFor(order.side());
        PriceLevel level = levels.get(order.price());
        if (level == null) {
            level = newLevel(order.price());
            levels.put(order.price(), level);
        }
        RestingOrder resting = new RestingOrder(order, level, remaining);
        level.add(resting);
        ordersById.put(order.id(), resting);
    }

    /**
     * Cancel a resting order.
     *
     * @return the quantity that was cancelled, or 0 if no live order has this id
     *         (never existed, already fully filled, or already cancelled)
     */
    public long cancel(long orderId) {
        RestingOrder resting = ordersById.remove(orderId);
        if (resting == null) {
            return 0;
        }
        long cancelledQty = resting.remaining;
        resting.level.cancel(resting);
        removeLevelIfEmpty(resting.order.side(), resting.level);
        return cancelledQty;
    }

    public boolean contains(long orderId) {
        return ordersById.containsKey(orderId);
    }

    /** Number of live resting orders. */
    public int size() {
        return ordersById.size();
    }

    public OptionalLong bestBid() {
        return bestPrice(bids);
    }

    public OptionalLong bestAsk() {
        return bestPrice(asks);
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    public int levelCount(Side side) {
        return levelsFor(side).size();
    }

    /** Every live resting order: bids best-first, then asks best-first, FIFO within each price. */
    public List<BookEntry> snapshot() {
        List<BookEntry> entries = new ArrayList<>();
        addEntries(entries, Side.BUY, bids);
        addEntries(entries, Side.SELL, asks);
        return entries;
    }

    /** The best price level on a side, or null if that side is empty. */
    PriceLevel bestLevel(Side side) {
        Map.Entry<Long, PriceLevel> best = levelsFor(side).firstEntry();
        return best == null ? null : best.getValue();
    }

    /** Fill the front order of a level, keeping the id index and the level map in sync. */
    void fillFront(Side side, PriceLevel level, long qty) {
        RestingOrder front = level.front();
        level.fillFront(qty);
        if (front.remaining == 0) {
            ordersById.remove(front.id());
        }
        removeLevelIfEmpty(side, level);
    }

    /** Drop a level once its last live order has gone, so firstEntry() never returns an empty level. */
    void removeLevelIfEmpty(Side side, PriceLevel level) {
        if (level.isEmpty()) {
            PriceLevel removed = levelsFor(side).remove(level.price());
            if (removed == level && spareLevels.size() < MAX_SPARE_LEVELS) {
                spareLevels.push(level);
            }
        }
    }

    private PriceLevel newLevel(long price) {
        PriceLevel level = spareLevels.poll();
        if (level == null) {
            return new PriceLevel(price);
        }
        level.reuseAt(price);
        return level;
    }

    private TreeMap<Long, PriceLevel> levelsFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static OptionalLong bestPrice(TreeMap<Long, PriceLevel> levels) {
        Map.Entry<Long, PriceLevel> best = levels.firstEntry();
        if (best == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(best.getKey());
    }

    private static void addEntries(List<BookEntry> out, Side side, TreeMap<Long, PriceLevel> levels) {
        for (PriceLevel level : levels.values()) {
            for (RestingOrder o : level.orders()) {
                out.add(new BookEntry(side, level.price(), o.id(), o.remaining));
            }
        }
    }
}
