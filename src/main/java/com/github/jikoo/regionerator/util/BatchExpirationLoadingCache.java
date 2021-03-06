package com.github.jikoo.regionerator.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A cache system designed to load values automatically and minimize write operations by expiring values in batches.
 * @param <K> the key type
 * @param <V> the value stored
 */
public class BatchExpirationLoadingCache<K, V> {

	private final Map<K, V> internal = new ConcurrentHashMap<>();
	private final Collection<K> expired = Collections.synchronizedSet(new LinkedHashSet<>());
	private final AtomicBoolean expirationQueued = new AtomicBoolean();
	private final ExpirationMap<K> expirationMap;
	private final Function<K, V> load;
	private final Consumer<Collection<V>> expirationConsumer;
	private final int maxBatchSize;
	private final long batchDelay;

	/**
	 * Constructs a new BatchExpirationLoadingCache using the default max batch size and delay.
	 *
	 * @param retention the cache retention duration
	 * @param load the {@link Function} used to load values into the cache
	 * @param expirationConsumer the {@link Consumer} accepting batches of expired values
	 */
	public BatchExpirationLoadingCache(final long retention, @NotNull final Function<K, V> load,
		@NotNull final Consumer<Collection<V>> expirationConsumer) {
		this(retention, load, expirationConsumer, 1024, 5000);
	}

	/**
	 * Constructs a new BatchExpirationLoadingCache.
	 *
	 * @param retention the cache retention duration
	 * @param load the {@link Function} used to load values into the cache
	 * @param expirationConsumer the {@link Consumer} accepting batches of expired values
	 * @param maxBatchSize the maximum batch size to expire simultaneously
	 * @param batchDelay the delay to await a full batch for expiration
	 */
	public BatchExpirationLoadingCache(final long retention, @NotNull final Function<K, V> load,
			@NotNull final Consumer<Collection<V>> expirationConsumer, int maxBatchSize, long batchDelay) {
		expirationMap = new ExpirationMap<>(retention);

		// Wrap load function to update expiration when used
		this.load = key -> {
			V value = load.apply(key);
			if (value != null) {
				// Only update expiration when loading yields a result.
				expirationMap.add(key);
			}
			checkExpiration();
			return value;
		};

		this.expirationConsumer = expirationConsumer;
		if (maxBatchSize < 1) {
			throw new IllegalArgumentException("Max batch size cannot be smaller than 1");
		}
		this.maxBatchSize = maxBatchSize;
		this.batchDelay = batchDelay;
	}

	/**
	 * Gets a {@link CompletableFuture} which either gets or loads a value for the specified key as necessary.
	 *
	 * @param key the key
	 * @return a {@link CompletableFuture} providing the requested value
	 */
	@NotNull
	public CompletableFuture<V> get(@NotNull K key) {
		V value = getIfPresent(key);
		if (value != null) {
			return CompletableFuture.completedFuture(value);
		}
		return CompletableFuture.supplyAsync(() -> load.apply(key));
	}

	/**
	 * Gets a value for the specified key or {@code null} if it is not present in the cache.
	 *
	 * @param key the key
	 * @return the loaded value or {@code null}
	 */
	@Nullable
	public V getIfPresent(@NotNull K key) {
		V value = internal.get(key);
		if (value != null) {
			expirationMap.add(key);
		}
		checkExpiration();
		return value;
	}

	/**
	 * Gets a value for the specified key or computes it and adds it to the cache if not present.
	 *
	 * @param key the key
	 * @return the loaded value or {@code null}
	 */
	public @NotNull V computeIfAbsent(@NotNull K key, Function<K, V> supplier) {
		V value = getIfPresent(key);
		if (value != null) {
			return value;
		}
		value = supplier.apply(key);
		put(key, value);
		return value;
	}

	/**
	 * Insert a value into the cache.
	 *
	 * @param key the key associated with the value
	 * @param value the value to be inserted
	 */
	public void put(@NotNull K key, @NotNull V value) {
		internal.put(key, value);
		expirationMap.add(key);
	}

	/**
	 * Remove an existing cached mapping.
	 *
	 * @param key the key whose mapping is to be removed
	 */
	public void remove(@NotNull K key) {
		if (internal.remove(key) != null) {
			expirationMap.remove(key);
			expired.remove(key);
		}
	}

	/**
	 * Invalidate all expired keys that are not considered in use.
	 */
	private void checkExpiration() {
		if (!expirationQueued.get()) {
			expired.addAll(expirationMap.doExpiration());
		}

		if (expired.isEmpty() || !expirationQueued.compareAndSet(false, true)) {
			return;
		}

		new Thread(
				() -> {
					// If not yet at maximum batch size, wait before
					if (expired.size() < maxBatchSize) {
						try {
							Thread.sleep(batchDelay);
							expired.addAll(expirationMap.doExpiration());
						} catch (InterruptedException e) {
							System.err.println("Encountered exception while attempting to await larger batch:");
							e.printStackTrace();
						}
					}

					ArrayList<V> expiredValues = new ArrayList<>();
					Iterator<K> expiredIterator = expired.iterator();
					while (expiredValues.size() < maxBatchSize && expiredIterator.hasNext()) {
						K expiredKey = expiredIterator.next();
						expiredIterator.remove();
						// Don't remove if still in expiration map - still active.
						V value = expirationMap.contains(expiredKey) ? internal.get(expiredKey) : internal.remove(expiredKey);
						if (value != null) {
							expiredValues.add(value);
						}
					}

					expirationConsumer.accept(expiredValues);
					expirationQueued.set(false);

					// Re-run expiration check to queue next batch if necessary
					checkExpiration();
				}, "BatchExpiration"
		).start();
	}

	/**
	 * Mark all keys for removal using the internal expiration system.
	 */
	public void lazyExpireAll() {
		expired.addAll(internal.keySet());
		checkExpiration();
	}

	/**
	 * Expire all keys immediately.
	 */
	public void expireAll() {
		expired.clear();
		if (internal.size() <= maxBatchSize) {
			expirationConsumer.accept(internal.values());
			internal.clear();
		} else {
			Iterator<V> iterator = internal.values().iterator();

			Collection<Collection<V>> subsets = new ArrayList<>();
			while (iterator.hasNext()) {
				ArrayList<V> subset = new ArrayList<>(maxBatchSize);
				for (int i = 0; i < maxBatchSize && iterator.hasNext(); ++i) {
					subset.add(iterator.next());
					iterator.remove();
				}
				subsets.add(subset);
			}
			for (Collection<V> subset : subsets) {
				expirationConsumer.accept(subset);
			}
		}

		expirationMap.clear();
	}

	/**
	 * Get the current number of values in the cache.
	 *
	 * @return the cache size
	 */
	public int getCached() {
		return internal.size();
	}

	/**
	 * Get the current number of values in the process of being expired.
	 *
	 * @return the expiration queue size
	 */
	public int getQueued() {
		return expired.size();
	}

}
