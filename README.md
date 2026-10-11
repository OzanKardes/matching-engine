# Limit Order Book Matching Engine

A single-threaded limit order book and matching engine in plain Java 21 (no frameworks), with
price-time priority, partial fills, market orders, O(1) cancellation, and a differential fuzz
test against a deliberately naive reference implementation.

```
mvn -B verify                                         # 43 tests incl. 100k-op differential fuzz
mvn -q package -DskipTests && java -jar target/matching-engine-0.1.0.jar   # benchmark
```

## What it does

- **Limit orders** match against the opposite side while prices cross, then any remainder rests.
- **Market orders** take liquidity at any price; any unfilled remainder is discarded.
- **Price-time priority**: best price first; at the same price, earliest arrival first. A partially
  filled order keeps its place in the queue.
- **Trades execute at the resting order's price.**
- **Cancel** by order id in O(1).
- Prices are `long` pence and quantities are `long` units. `double` is never used: binary floating
  point cannot represent most decimal prices exactly, so equality and crossing checks would be unreliable.

## Architecture

```
                    submit(Order) / cancel(id)
                              |
                              v
                     +-----------------+
                     | MatchingEngine  |   matching loop: while (remaining > 0 && best contra level crosses)
                     +-----------------+              trade min(remaining, front.remaining) at level price
                              |
                              v
  +-------------------------------------------------------------------+
  | OrderBook                                                         |
  |                                                                   |
  |  bids: TreeMap<Long, PriceLevel>  (reverse order: highest first)  |
  |  asks: TreeMap<Long, PriceLevel>  (natural order: lowest first)   |
  |        -> firstEntry() is the best price on BOTH sides            |
  |                                                                   |
  |  ordersById: HashMap<Long, RestingOrder>  (live orders only)      |
  |  spareLevels: ArrayDeque<PriceLevel>      (recycled empty levels) |
  +-------------------------------------------------------------------+
            |                                      |
            v                                      v
  +---------------------------+         +----------------------------+
  | PriceLevel (one price)    |  <----  | RestingOrder               |
  |  ArrayDeque<RestingOrder> |  level  |  final Order order         |
  |  FIFO = time priority     |         |  long remaining  (mutable) |
  |  liveCount                |         |  boolean cancelled         |
  +---------------------------+         +----------------------------+

  Order, Trade, BookEntry: immutable records.     Side, OrderType: enums.
```

`Order` is an immutable record, so the shrinking quantity of a resting order lives in the mutable
`RestingOrder` wrapper that the book owns. Callers never see mutable state.

## Data structures and complexity

L = number of price levels on a side, N = resting orders, k = resting orders filled by one submit.

| Operation | Cost | How |
|---|---|---|
| Submit, no match (rests) | O(log L) | TreeMap get/put + deque append + hash put |
| Submit, fills k orders | O(k + log L) per level touched | front of best level, repeatedly |
| Cancel | O(1) expected, plus O(log L) only if the level empties | hash lookup, flag `cancelled`, `liveCount--` |
| Best bid / ask | O(log L) | `TreeMap.firstEntry()` (red-black tree, walks leftmost path) |
| Snapshot | O(N + dead entries) | used by tests only |

**Lazy deletion.** A cancelled order is flagged and left in its deque, because unlinking it from the
middle of an `ArrayDeque` is O(n). Every operation that changes the head of a deque then drops
cancelled entries, so the head is always live and `front()` is a plain peek. Each entry is popped
exactly once, so the clean-up is O(1) amortised. **Cost:** dead entries use memory until they
reach the front. In the worst case, one old order that never trades sits at the head with
thousands of cancelled entries piled up behind it.

## Design trade-offs considered and rejected

| Alternative | Why rejected (for now) |
|---|---|
| **Array indexed by price tick** instead of TreeMap | O(1) level access, cache-friendly, no boxing. But it needs a fixed tick size and a bounded price range, and finding the next best price after a level empties means scanning. It is the right choice for a production engine on a known instrument (a window centred on the mid price), and the next optimisation here. |
| `double` prices | Inexact decimal representation breaks equality and crossing checks. |
| Eager removal on cancel | O(n) scan of the deque. |
| Intrusive doubly linked list per level | True O(1) unlink with no dead entries, but `java.util.LinkedList` does not expose its nodes, so this would need a hand-written list. More code for a small gain at this scale. |
| Leave empty levels in the tree | Avoids TreeMap rebalancing on churn, but `bestLevel` would have to skip empty levels and they would need sweeping. Rejected for simplicity; level *objects* are recycled instead (see Profiling). |
| Mutable `quantity` on `Order` | Shared, mutable domain objects leak state to callers. Immutable record + book-owned wrapper instead. |
| Locks / concurrent matching | See below. |

### Why single-threaded

Matching on one book is inherently sequential: the outcome of every order depends on the exact
state left by the previous one. A single writer thread needs no locks, has no contention, and is
**deterministic**: the same input sequence always produces the same trades. That determinism is
what makes the fuzz test reproducible, and it lets a real system rebuild state by replaying a
journal. To scale: **shard by instrument** (books are independent, so one thread per book or group
of books), and move parsing, risk checks, journaling and market-data publication to other threads,
connected by lock-free queues (the LMAX Disruptor architecture). Replicate for failover by feeding
a standby engine the same sequenced input.

## Testing: how do we know it's correct?

1. **Unit tests** (42): full and partial fills, no match, market orders on an empty book, multiple
   levels consumed, FIFO within a level, a partially filled order keeping its priority, a better price beating
   an earlier arrival, cancel of resting / filled / unknown / already-cancelled orders, and dead entries.

2. **Differential fuzz test** (`DifferentialFuzzTest`): 100,000 random submits and cancels (fixed
   seed), run through both `MatchingEngine` and `ReferenceEngine`. The reference is one flat list
   with O(n) scans, slow but simple enough to check by reading. After **every** operation the test asserts:
   - identical trades, identical cancel results, and identical full book state (every order, in priority order)
   - the book is never crossed (best bid < best ask)
   - quantity is conserved: `submitted = 2 x traded + resting + cancelled + discarded`
   - FIFO: within each level, order ids (issued in arrival order) strictly increase
   - no trade executes at a worse price than the aggressor's limit

   The generator holds the book at about 500 resting orders and includes the **cancel/fill race**
   (a cancel arriving just after its order filled).

**Proof that the fuzz test earns its place:** I planted a bug where a filled order was not removed
from `ordersById`. All 41 unit tests at the time still passed. The first version of the fuzz test
*also* passed, because its "cancel a random id" ops almost always hit long-dead orders. After
adding the cancel/fill race to the generator, it failed at op 1,082 with a precise diff, and that
scenario is now a regression unit test (`cancelOfFilledOrderDoesNotDisturbItsOldLevel`). Lesson:
fuzzing coverage comes from the input distribution, not the operation count.

```
mvn test -Dtest=DifferentialFuzzTest -Dfuzz.seed=12345              # reproduce a reported seed
mvn test -Dtest=DifferentialFuzzTest -Dfuzz.seed=random -Dfuzz.ops=1000000   # soak test
```
On failure the message includes the seed and the exact reproduce command.
(PowerShell: quote the properties, e.g. `'-Dfuzz.seed=12345'`.)

## Benchmark

1,000,000 pre-generated operations (21% cancels; submits are 5% market orders; limit prices
normally distributed around a mid with sigma = 15 ticks). The book stays at a steady state of about 5,000
resting orders across about 100 price levels. 5 warm-up passes, then 5 throughput runs (timed as a whole) and
1 latency run (each op timed with `System.nanoTime()`).

**Machine:** Intel Core Ultra 7 155H (22 logical cores, hybrid P/E cores), 32 GB RAM, Windows 11
Pro, Temurin OpenJDK 21.0.12, default JVM flags (G1 GC).

| Metric | Typical run | Range over 6 runs |
|---|---|---|
| Throughput (median of 5 in-process runs) | 10.2M ops/s | 8.5M to 10.4M ops/s |
| Latency p50 | 100 ns | 100 ns |
| Latency p99 | 400 ns | 400 to 800 ns |
| Latency p99.9 | 1.7 µs | 1.4 to 3.8 µs |
| Latency max | ~0.2 to 2 ms | GC pauses / OS scheduling |

Caveats: on Windows `System.nanoTime()` has 100 ns resolution, so latencies are quantised to
100 ns, and p50 really means "below roughly 100 ns". The variance comes from a laptop CPU with turbo boost and
hybrid cores. A serious measurement would use JMH, a pinned core, and a Linux host.

### Profiling (Java Flight Recorder)

```
java -XX:StartFlightRecording=settings=profile,filename=bench.jfr -jar target/matching-engine-0.1.0.jar
jfr view hot-methods bench.jfr
jfr view allocation-by-site bench.jfr
```

**Finding: level churn.** Levels near the touch constantly empty and refill. About 40% of CPU samples
were inside `TreeMap` (insert/rebalance/lookup), and allocating a fresh `PriceLevel` plus its
`ArrayDeque` (16-slot backing array) was about 29% of allocated bytes, which fed garbage-collection pauses.

**Fix:** keep up to 256 emptied `PriceLevel`s in a spare pool and reuse them. Interleaved A/B runs, 5 each:

| | Median throughput | p99 | p99.9 | GC pauses (one run) |
|---|---|---|---|---|
| Before | 9.18M ops/s | 500 to 600 ns | 1.5 to 2.7 µs | 11, max 12.2 ms |
| After | 10.18M ops/s | 400 ns in all 5 | 1.4 to 2.0 µs | 10, max 7.7 ms |

The throughput gain is about the same size as the run-to-run noise. The tail-latency improvement held in every run.
`ArrayDeque`/`PriceLevel` allocation disappeared from the profile.

**Future work (next bottleneck):** `Long` boxing is now 56% of allocation. Every `TreeMap`/`HashMap`
call with a `long` key allocates a `Long` outside the -128..127 cache. Next steps are a primitive
`long -> object` hash map for `ordersById` (open addressing over `long[]`) and a tick-indexed array
window for price levels, which also removes the TreeMap rebalancing cost. `TreeMap.firstEntry()`
also allocates an immutable entry per call (about 5%), and caching the best level per side would remove that.

## How to run

Requires JDK 21 and Maven.

```bash
mvn -B verify                                        # all tests
mvn test -Dtest=MatchingEngineTest                   # one test class
mvn -q package -DskipTests
java -jar target/matching-engine-0.1.0.jar           # benchmark, 1M ops
java -jar target/matching-engine-0.1.0.jar 200000    # benchmark, custom op count
```

Docker (runs the benchmark):

```bash
docker build -t matching-engine .
docker run --rm matching-engine
```

CI: GitHub Actions runs `mvn -B verify` and a 100k-op benchmark smoke test on every push.

## Project layout

```
src/main/java/matching/
  Side, OrderType           enums
  Order, Trade, BookEntry   immutable records
  RestingOrder              mutable per-order book state (package-private)
  PriceLevel                FIFO queue at one price, lazy deletion (package-private)
  OrderBook                 bid/ask TreeMaps, id index, level pool
  MatchingEngine            price-time priority matching, cancel
  Benchmark                 throughput/latency benchmark (jar main class)
src/test/java/matching/
  DomainTypesTest, OrderBookTest, MatchingEngineTest, CancelTest
  ReferenceEngine           naive O(n) oracle
  DifferentialFuzzTest      100k-op differential fuzz + invariants
```

## Known limitations

- No order amend, time-in-force (IOC/FOK), or self-trade prevention.
- Order ids must be unique per caller. Only a duplicate of a currently resting id is rejected.
- No overflow checks on quantity arithmetic (fine for realistic sizes in a `long`).
- In-memory only: no journaling or recovery.
