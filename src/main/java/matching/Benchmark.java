package matching;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Throughput and latency benchmark.
 *
 * Run: mvn -q package -DskipTests && java -jar target/matching-engine-0.1.0.jar
 *
 * Operations are generated up front so that random-number generation and object
 * allocation for the inputs are not measured. Each measured run uses a fresh engine.
 */
public final class Benchmark {

    private static final int OPERATIONS = 1_000_000;
    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 5;
    private static final long SEED = 42L;

    private static final long MID_PRICE = 10_000;
    private static final double PRICE_STDDEV_TICKS = 15.0;
    private static final int CANCEL_PCT = 20;       // halved below TARGET_DEPTH, doubled above it
    private static final int MARKET_PCT = 5;
    private static final int TARGET_DEPTH = 5_000;  // resting orders in steady state

    /** One pre-generated operation: either a submit (order != null) or a cancel. */
    private record Op(Order order, long cancelId) {
    }

    public static void main(String[] args) {
        int operations = args.length > 0 ? Integer.parseInt(args[0]) : OPERATIONS;
        Op[] ops = generate(operations, new Random(SEED));

        int cancels = 0;
        for (Op op : ops) {
            if (op.order() == null) {
                cancels++;
            }
        }
        System.out.printf("Generated %,d operations: %,d cancels, rest are submits (%d%% of them market orders)%n",
                operations, cancels, MARKET_PCT);
        System.out.printf("JVM: %s %s, %d cores%n", System.getProperty("java.vm.name"),
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());

        // Warm-up: let the JIT compiler see the hot paths and compile them to optimised
        // machine code before we start the clock. Results are discarded.
        for (int i = 0; i < WARMUP_RUNS; i++) {
            runThroughput(ops);
            runLatency(ops);
        }

        double[] throughputs = new double[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            throughputs[i] = runThroughput(ops);
        }
        long[] latencies = runLatency(ops);
        Arrays.sort(latencies);
        Arrays.sort(throughputs);

        System.out.println();
        System.out.println("| Metric                   | Value                |");
        System.out.println("|--------------------------|----------------------|");
        System.out.printf("| Throughput (median of %d) | %,14.0f ops/s |%n", MEASURED_RUNS, throughputs[MEASURED_RUNS / 2]);
        System.out.printf("| Throughput (best of %d)   | %,14.0f ops/s |%n", MEASURED_RUNS, throughputs[MEASURED_RUNS - 1]);
        System.out.printf("| Latency p50              | %,14d ns    |%n", percentile(latencies, 50.0));
        System.out.printf("| Latency p99              | %,14d ns    |%n", percentile(latencies, 99.0));
        System.out.printf("| Latency p99.9            | %,14d ns    |%n", percentile(latencies, 99.9));
        System.out.printf("| Latency max              | %,14d ns    |%n", latencies[latencies.length - 1]);

        MatchingEngine engine = new MatchingEngine();
        long trades = 0;
        for (Op op : ops) {
            if (op.order() != null) {
                trades += engine.submit(op.order()).size();
            } else {
                engine.cancel(op.cancelId());
            }
        }
        System.out.printf("%nEnd of run: %,d resting orders across %d bid / %d ask levels; %,d trades%n",
                engine.book().snapshot().size(), engine.book().levelCount(Side.BUY),
                engine.book().levelCount(Side.SELL), trades);
    }

    /** Time the whole run with two clock reads; no per-operation timing overhead. */
    private static double runThroughput(Op[] ops) {
        MatchingEngine engine = new MatchingEngine();
        long sink = 0;
        long start = System.nanoTime();
        for (Op op : ops) {
            sink += apply(engine, op);
        }
        long elapsed = System.nanoTime() - start;
        consume(sink);
        return ops.length / (elapsed / 1e9);
    }

    /** Time each operation individually. Includes ~20ns of nanoTime() overhead per op. */
    private static long[] runLatency(Op[] ops) {
        MatchingEngine engine = new MatchingEngine();
        long[] latencies = new long[ops.length];
        long sink = 0;
        for (int i = 0; i < ops.length; i++) {
            long start = System.nanoTime();
            sink += apply(engine, ops[i]);
            latencies[i] = System.nanoTime() - start;
        }
        consume(sink);
        return latencies;
    }

    /**
     * Returns something derived from the result so the JIT cannot decide the work is
     * unused and delete it (dead-code elimination).
     */
    private static long apply(MatchingEngine engine, Op op) {
        if (op.order() != null) {
            List<Trade> trades = engine.submit(op.order());
            return trades.size();
        }
        return engine.cancel(op.cancelId());
    }

    private static volatile long blackhole;

    private static void consume(long value) {
        blackhole = value;
    }

    private static long percentile(long[] sorted, double pct) {
        int index = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /**
     * Prices are normally distributed around MID_PRICE, so most activity is near the
     * touch, as in a real market. Buys skew slightly below mid and sells slightly above, so
     * the book builds depth instead of every order crossing immediately.
     *
     * A simulation engine runs alongside the generator so cancels can target orders that
     * are genuinely resting, and so the cancel rate can rise when the book is deep. That
     * keeps the book in a steady state around TARGET_DEPTH orders instead of growing for
     * the whole run.
     */
    private static Op[] generate(int count, Random random) {
        Op[] ops = new Op[count];
        MatchingEngine sim = new MatchingEngine();
        long[] candidates = new long[1024];   // ids that were resting when last seen
        int candidateCount = 0;
        long nextId = 1;

        for (int i = 0; i < count; i++) {
            int cancelPct = sim.book().size() < TARGET_DEPTH ? CANCEL_PCT / 2 : CANCEL_PCT * 2;
            if (random.nextInt(100) < cancelPct) {
                // Pick a random candidate; drop any that have since been filled.
                long cancelId = 0;
                while (candidateCount > 0 && cancelId == 0) {
                    int slot = random.nextInt(candidateCount);
                    long id = candidates[slot];
                    candidates[slot] = candidates[--candidateCount];
                    if (sim.book().contains(id)) {
                        cancelId = id;
                    }
                }
                if (cancelId != 0) {
                    ops[i] = new Op(null, cancelId);
                    sim.cancel(cancelId);
                    continue;
                }
            }

            long id = nextId++;
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + random.nextInt(100);
            Order order;
            if (random.nextInt(100) < MARKET_PCT) {
                order = Order.market(id, side, qty);
            } else {
                long skew = side == Side.BUY ? -3 : 3;
                long price = MID_PRICE + skew + Math.round(random.nextGaussian() * PRICE_STDDEV_TICKS);
                order = Order.limit(id, side, Math.max(1, price), qty);
            }
            ops[i] = new Op(order, 0);
            sim.submit(order);
            if (sim.book().contains(id)) {
                if (candidateCount == candidates.length) {
                    candidates = Arrays.copyOf(candidates, candidates.length * 2);
                }
                candidates[candidateCount++] = id;
            }
        }
        return ops;
    }
}
