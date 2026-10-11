package matching;

/**
 * One resting order as seen from outside the book. A list of these, in priority order,
 * is a complete description of the book's state.
 */
public record BookEntry(Side side, long price, long orderId, long remaining) {
}
