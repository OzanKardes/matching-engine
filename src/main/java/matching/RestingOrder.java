package matching;

/**
 * Book-keeping for an order sitting on the book.
 *
 * Order is an immutable record, so its quantity can never shrink. The quantity still
 * waiting to be filled therefore lives here, in a small mutable object owned by the book.
 */
final class RestingOrder {
    final Order order;
    final PriceLevel level;   // back-pointer so cancel can reach the level without a TreeMap lookup
    long remaining;
    boolean cancelled;        // lazy deletion: set on cancel, entry is skipped when it reaches the front

    RestingOrder(Order order, PriceLevel level, long remaining) {
        this.order = order;
        this.level = level;
        this.remaining = remaining;
    }

    long id() {
        return order.id();
    }
}
