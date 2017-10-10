package orestes.bloomfilter.redis;

import orestes.bloomfilter.BloomFilter;
import orestes.bloomfilter.CountingBloomFilter;
import orestes.bloomfilter.FilterBuilder;
import orestes.bloomfilter.MigratableBloomFilter;
import orestes.bloomfilter.memory.CountingBloomFilterMemory;
import orestes.bloomfilter.redis.helper.RedisKeys;
import orestes.bloomfilter.redis.helper.RedisPool;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.util.SafeEncoder;

import java.awt.print.PrinterIOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Uses regular key-value pairs for counting instead of a bitarray. This introduces a space overhead but allows
 * distribution of keys, thus increasing throughput. Pipelining can also be leveraged in this approach to minimize
 * network latency.
 *
 * @param <T> The type of the containing elements
 */
public class CountingBloomFilterRedis<T> implements CountingBloomFilter<T>, MigratableBloomFilter<T, CountingBloomFilterRedis<T>> {
    protected final RedisKeys keys;
    protected final RedisPool pool;
    protected final RedisBitSet bloom;
    protected final FilterBuilder config;


    public CountingBloomFilterRedis(FilterBuilder builder) {
        builder.complete();
        this.keys = new RedisKeys(builder.name());
        this.pool = builder.pool();
        this.bloom = new RedisBitSet(pool, keys.BITS_KEY, builder.size());
        this.config = keys.persistConfig(pool, builder);
        if (builder.overwriteIfExists()) {
            this.clear();
        }
    }

    @Override
    public Map<Integer, Long> getCountMap() {
        return pool.allowingSlaves().safelyReturn(r -> r.hgetAll(keys.COUNTS_KEY)
            .entrySet()
            .stream()
            .collect(
                Collectors.toMap(
                    e -> Integer.valueOf(e.getKey()),
                    e -> Long.valueOf(e.getValue())
                )
            ));
    }

    @Override
    public long addAndEstimateCountRaw(byte[] element) {
        List<Object> results = pool.transactionallyRetry(p -> {
            int[] hashes = hash(element);
            for (int position : hashes) {
                bloom.set(p, position, true);
            }
            for (int position : hashes) {
                p.hincrBy(keys.COUNTS_KEY, encode(position), 1);
            }
        }, keys.BITS_KEY, keys.COUNTS_KEY);
        return results.stream().skip(config().hashes()).map(i -> (Long) i).min(Comparator.<Long>naturalOrder()).get();
    }

    @Override
    public List<Boolean> addAll(Collection<T> elements) {
        return addAndEstimateCountRaw(elements).stream().map(el -> el == 1).collect(Collectors.toList());
    }

    /**
     * Adds all elements to the Bloom filter and returns the estimated count of the inserted elements.
     * @param elements The elements to add.
     * @return An estimation of how often the respective elements are in the Bloom filter.
     */
    private List<Long> addAndEstimateCountRaw(Collection<T> elements) {
        List<int[]> allHashes = elements.stream().map(el -> hash(toBytes(el))).collect(Collectors.toList());
        List<Object> results = pool.transactionallyRetry(p -> {
            // Add to flattened Bloom filter
            for (int[] hashes : allHashes) {
                for (int position : hashes) {
                    bloom.set(p, position, true);
                }
            }
            // Add to counting Bloom filter
            for (int[] hashes : allHashes) {
                for (int position : hashes) {
                    p.hincrBy(keys.COUNTS_KEY, encode(position), 1);
                }
            }
        }, keys.BITS_KEY, keys.COUNTS_KEY);

        // Walk through result in blocks of #hashes and skip the return values from set-bit calls.
        // Get the minimum count for each added element to estimate how often the element is in the Bloom filter.
        List<Long> mins = new LinkedList<>();
        for (int i = results.size() / 2; i < results.size(); i += config().hashes()) {
            long min = results.subList(i, i + config().hashes())
                .stream()
                .map(val -> (Long) val)
                .min(Comparator.<Long>naturalOrder())
                .get();
            mins.add(min);
        }
        return mins;
    }

    //TODO removeALL


    @Override
    public boolean removeRaw(byte[] value) {
        return removeAndEstimateCountRaw(value) <= 0;
    }

    @Override
    public long removeAndEstimateCountRaw(byte[] value) {
        return pool.safelyReturn(jedis -> {
            int[] hashes = hash(value);
            String[] hashesString = encode(hashes);

            // Decrement counters in CBF and return their new value
            final Pipeline p1 = jedis.pipelined();
            p1.watch(keys.COUNTS_KEY, keys.BITS_KEY); // Forbid writing to CBF and FBF in the meantime
            List<Response<Long>> responses = new ArrayList<>(config().hashes());
            for (String position : hashesString) {
                responses.add(p1.hincrBy(keys.COUNTS_KEY, position, -1));
            }
            p1.sync();
            List<Long> counts = responses.stream().map(Response::get).collect(Collectors.toList());

            // Fast lane: don't set BF bits
            long min = Collections.min(counts);
            if (min > 0) {
                return min;
            }

            while (true) {
                // Transactionally set FBF bits to 0
                final Pipeline p2 = jedis.pipelined();
                p2.multi();
                for (int i = 0; i < config().hashes(); i++) {
                    if (counts.get(i) <= 0) {
                        bloom.set(p2, hashes[i], false);
                    }
                }
                Response<List<Object>> exec = p2.exec();
                p2.sync();

                // Did the transaction fail?
                if (exec.get() == null) {
                    // Update counts from Redis
                    final Pipeline p3 = jedis.pipelined();
                    p3.watch(keys.COUNTS_KEY, keys.BITS_KEY); // Forbid writing to CBF and FBF in the meantime
                    Response<List<String>> hmget = p3.hmget(keys.COUNTS_KEY, hashesString);
                    p3.sync();
                    counts = hmget.get()
                        .stream()
                        .map(o -> o != null ? Long.parseLong(o) : 0L)
                        .collect(Collectors.toList());
                } else {
                    return Collections.min(counts);
                }
            }
        });
    }

    @Override
    public long getEstimatedCount(T element) {
        return pool.allowingSlaves().safelyReturn(jedis -> {
            String[] hashesString = encode(hash(toBytes(element)));
            List<String> hmget = jedis.hmget(keys.COUNTS_KEY, hashesString);
            return hmget.stream().mapToLong(i -> i == null ? 0L : Long.valueOf(i)).min().orElse(0L);
        });
    }


    @Override
    public void clear() {
        pool.safelyDo(jedis -> {
            jedis.del(keys.COUNTS_KEY, keys.BITS_KEY);
        });
    }

    @Override
    public void remove() {
        clear();
        pool.safelyDo(jedis -> jedis.del(config().name()));
        pool.destroy();
    }

    @Override
    public boolean contains(byte[] element) {
        return bloom.isAllSet(hash(element));
    }


    protected RedisBitSet getRedisBitSet() {
        return bloom;
    }

    @Override
    public BitSet getBitSet() {
        return bloom.asBitSet();
    }

    public byte[] getBytes() { return bloom.toByteArray(); }

    @Override
    public FilterBuilder config() {
        return config;
    }

    public CountingBloomFilterMemory<T> toMemoryFilter() {
        CountingBloomFilterMemory<T> filter = new CountingBloomFilterMemory<>(config().clone());
        filter.getBloomFilter().setBitSet(getBitSet());
        return filter;
    }

    @Override
    public CountingBloomFilter<T> clone() {
        return new CountingBloomFilterRedis<>(config().clone());
    }

    @Override
    public boolean union(BloomFilter<T> other) {
        //TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean intersect(BloomFilter<T> other) {
        //TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        return bloom.isEmpty();
    }

    @Override
    public Double getEstimatedPopulation() {
        return BloomFilter.population(bloom, config());
    }

    public RedisPool getRedisPool() {
        return pool;
    }

    private static String encode(int value) {
        return SafeEncoder.encode(
            new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }


    private static String[] encode(int[] hashes) {
        return IntStream.of(hashes).mapToObj(CountingBloomFilterRedis::encode).toArray(String[]::new);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CountingBloomFilterRedis)) {
            return false;
        }

        CountingBloomFilterRedis that = (CountingBloomFilterRedis) o;

        if (bloom != null ? !bloom.equals(that.bloom) : that.bloom != null) {
            return false;
        }
        if (config != null ? !config.isCompatibleTo(that.config) : that.config != null) {
            return false;
        }
        //TODO also checks counters

        return true;
    }

    @Override
    public CountingBloomFilterRedis<T> migrateFrom(BloomFilter<T> source) {
        if (!(source instanceof CountingBloomFilter) || !compatible(source)) {
            throw new IncompatibleMigrationSourceException("Source is not compatible with the targeted Bloom filter");
        }

        final CountingBloomFilter<T> cbf = (CountingBloomFilter<T>) source;

        Map<Integer, Long> countSetToMigrate = cbf.getCountMap();

        pool.transactionallyRetry(p -> {
            countSetToMigrate.forEach((position, value) -> set(position, value, p));
        }, keys.BITS_KEY, keys.COUNTS_KEY);

        return this;
    }

    /**
     * Sets the value at the given position.
     *
     * @param position The position in the Bloom filter
     * @param value The value to set
     * @param p The jedis pipeline to use
     */
    private void set(int position, long value, Pipeline p) {
        bloom.set(p, position, value > 0);
        p.hset(keys.COUNTS_KEY, encode(position), String.valueOf(value));
    }
}
