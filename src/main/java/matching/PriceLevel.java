package matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * All resting orders at one price, in arrival order (FIFO = time priority).
 *
 * Cancelled orders are not removed from the queue straight away (that would be an O(n)
 * search through the deque). They are flagged and dropped once they reach the front.
 * Every method that changes the head re-establishes the invariant "the head is live",
 * so front() is a plain peek. liveCount tracks un-cancelled orders, so isEmpty() is O(1).
 */
final class PriceLevel {
    private long price;   // not final: a spare level is reused at a new price (see OrderBook)
    private final ArrayDeque<RestingOrder> queue = new ArrayDeque<>();
    private int liveCount;

    PriceLevel(long price) {
        this.price = price;
    }

    /** Recycle an empty level at a new price. Its deque keeps its already-grown backing array. */
    void reuseAt(long newPrice) {
        if (!queue.isEmpty() || liveCount != 0) {
            throw new IllegalStateException("cannot reuse a non-empty level at " + price);
        }
        this.price = newPrice;
    }

    long price() {
        return price;
    }

    void add(RestingOrder order) {
        queue.addLast(order);
        liveCount++;
    }

    /** The live order with time priority at this price, or null if the level is empty. */
    RestingOrder front() {
        return queue.peekFirst();   // invariant: the head of the queue is never a cancelled entry
    }

    /** Fill the front order by qty; remove it from the queue once it is fully filled. */
    void fillFront(long qty) {
        RestingOrder front = queue.peekFirst();
        front.remaining -= qty;
        if (front.remaining == 0) {
            queue.pollFirst();
            liveCount--;
            discardCancelledAtFront();
        }
    }

    /** O(1): flag the order as dead and leave it in the queue until it reaches the front. */
    void cancel(RestingOrder order) {
        order.cancelled = true;
        liveCount--;
        discardCancelledAtFront();
    }

    boolean isEmpty() {
        return liveCount == 0;
    }

    /** A copy of the live orders in priority order, for snapshots and tests. */
    List<RestingOrder> orders() {
        List<RestingOrder> live = new ArrayList<>();
        for (RestingOrder o : queue) {
            if (!o.cancelled) {
                live.add(o);
            }
        }
        return live;
    }

    /** Total entries in the deque, including cancelled ones not yet discarded. */
    int queueSize() {
        return queue.size();
    }

    private void discardCancelledAtFront() {
        while (!queue.isEmpty() && queue.peekFirst().cancelled) {
            queue.pollFirst();
        }
    }
}
