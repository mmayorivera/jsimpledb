
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb.kv.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.dellroad.stuff.java.Predicate;
import org.dellroad.stuff.java.TimedWait;
import org.jsimpledb.util.ByteUtil;

/**
 * Manager of read/write locks on {@code byte[]} key ranges that ensures isolation and serialization while allowing concurrent
 * access by multiple threads to a single underlying {@code byte[]} key/value store.
 *
 * <p>
 * This implementation is straightforward: read locks can overlap, but write locks may not, and all locks owned
 * by the same owner remain in force until all are {@linkplain #release released} at the same time.
 * </p>
 *
 * <p>
 * Two timeout values are supported:
 * <ul>
 *  <li>The wait timeout (specified as a parameter to {@link #lock lock()}) limits how long a thread will wait
 *      on a lock held by another thread before giving up</li>
 *  <li>The {@linkplain #getHoldTimeout hold timeout} limits how long a thread may hold
 *      on to a contested lock before being forced to release all its locks; after that, the
 *      next call to {@link #lock lock} or {@link #release release} will fail</li>
 * </ul>
 * </p>
 */
public class LockManager {

    private static final long TEN_YEARS_MILLIS = 10L * 365L * 24L * 60L * 60L * 1000L;

    private final WeakHashMap<LockOwner, Long> holdDeadlines = new WeakHashMap<>();         // hold deadline, or null if expired
    private final TreeSet<Lock> locksByMin = new TreeSet<>(Lock.MIN_COMPARATOR);            // locks ordered by minimum
    private final TreeSet<Lock> locksByMax = new TreeSet<>(Lock.MAX_COMPARATOR);            // locks ordered by maximum
    private final long nanoBasis = System.nanoTime();

    private long holdTimeout;

    /**
     * Get the hold timeout configured for this instance.
     *
     * <p>
     * The hold timeout limits how long a thread may hold on to a contested lock before being forced to release
     * all of its locks; after that, the next call to {@link #lock lock} or {@link #release release} will fail.
     * </p>
     *
     * @return hold timeout in milliseconds
     */
    public long getHoldTimeout() {
        return this.holdTimeout;
    }

    /**
     * Set the hold timeout for this instance. Default is zero (unlimited).
     *
     * @param holdTimeout how long a thread may hold a contestested lock before {@link LockResult#HOLD_TIMEOUT_EXPIRED}
     *  will be returned by {@link #lock lock()} or {@link #release release()} in milliseconds, or zero for unlimited
     * @throws IllegalArgumentException if {@code holdTimeout} is negative
     */
    public void setHoldTimeout(long holdTimeout) {
        if (holdTimeout < 0)
            throw new IllegalArgumentException("holdTimeout < 0");
        this.holdTimeout = Math.min(holdTimeout, TEN_YEARS_MILLIS);             // limit to 10 years to avoid overflow
    }

    /**
     * Acquire a lock on behalf of the specified owner.
     *
     * <p>
     * This method will block for up to {@code waitTimeout} milliseconds if the lock is held by
     * another thread, after which point {@link LockResult#WAIT_TIMEOUT_EXPIRED} is returned.
     * </p>
     *
     * <p>
     * If the owner's {@linkplain #getHoldTimeout hold timeout} has expired, then
     * {@link LockResult#HOLD_TIMEOUT_EXPIRED} is returned.
     * </p>
     *
     * <p>
     * Once a lock is acquired it stays acquired, until all locks are released together via {@link #release release()}.
     * </p>
     *
     * @param owner lock owner
     * @param minKey minimum key (inclusive), or null for no minimum
     * @param maxKey maximum key (exclusive), or null for no maximum
     * @param write true for a write lock, false for a read lock
     * @param waitTimeout how long to wait before returning {@link LockResult#WAIT_TIMEOUT_EXPIRED}
     *  in milliseconds, or zero for unlimited
     * @return a {@link LockResult}
     * @throws InterruptedException if the current thread is interrupted while waiting for the lock
     * @throws IllegalArgumentException if {@code owner} or {@code range} is null
     * @throws IllegalArgumentException if {@code minKey > maxKey}
     * @throws IllegalArgumentException if {@code waitTimeout} is negative
     */
    public synchronized LockResult lock(LockOwner owner, byte[] minKey, byte[] maxKey, boolean write, long waitTimeout)
      throws InterruptedException {

        // Sanity check
        if (owner == null)
            throw new IllegalArgumentException("null owner");
        if (waitTimeout < 0)
            throw new IllegalArgumentException("waitTimeout < 0");
        waitTimeout = Math.min(waitTimeout, TEN_YEARS_MILLIS);                  // limit to 10 years to avoid overflow

        // Create lock
        Lock lock = new Lock(owner, minKey, maxKey, write);

        // Hold timeout expired? Also calculate our wait deadline
        if (this.holdTimeout != 0 && this.holdDeadlines.containsKey(owner) && this.holdDeadlines.get(owner) == null)
            return LockResult.HOLD_TIMEOUT_EXPIRED;

        // Build lock tester
        final LockChecker lockChecker = new LockChecker(lock);

        // Wait for lockability
        if (!TimedWait.wait(this, waitTimeout, lockChecker))
            return LockResult.WAIT_TIMEOUT_EXPIRED;

        // Merge the lock with other locks it can merge with, removing those locks in the process
        for (Lock that : lockChecker.getMergers()) {
            final Lock mergedLock = lock.mergeWith(that);
            if (mergedLock != null) {
                this.locksByMin.remove(that);
                this.locksByMax.remove(that);
                owner.locks.remove(that);
                lock = mergedLock;
            }
        }

        // Add lock
        this.locksByMin.add(lock);
        this.locksByMax.add(lock);
        owner.locks.add(lock);

        // Done
        return LockResult.SUCCESS;
    }

    /**
     * Release all locks held by the specified owner.
     *
     * <p>
     * If the owner's {@linkplain #getHoldTimeout hold timeout} has expired, then
     * {@link LockResult#HOLD_TIMEOUT_EXPIRED} is returned.
     * </p>
     *
     * @param owner lock owner
     * @return one of {@link LockResult#HOLD_TIMEOUT_EXPIRED} or {@link LockResult#SUCCESS}
     * @throws IllegalArgumentException if {@code owner} is null
     */
    public synchronized LockResult release(LockOwner owner) {

        // Sanity check
        if (owner == null)
            throw new IllegalArgumentException("null owner");

        // Check if hold timeout has alread expired; if not, remove deadline
        if (this.holdTimeout != 0 && this.holdDeadlines.containsKey(owner)) {
            final Long holdDeadline = this.holdDeadlines.remove(owner);
            if (holdDeadline == null)
                return LockResult.HOLD_TIMEOUT_EXPIRED;         // sorry, released too late
        }

        // Release all locks
        this.doRelease(owner);

        // Done
        return LockResult.SUCCESS;
    }

    // Release all locks held by owner
    private /*synchronized*/ void doRelease(LockOwner owner) {
        for (Lock lock : owner.locks) {
            this.locksByMin.remove(lock);
            this.locksByMax.remove(lock);
        }
        owner.locks.clear();
        this.notifyAll();
    }

    // Check whether we can lock, and fill the list of mergers if so
    private /*synchronized*/ boolean checkLock(Lock lock, List<Lock> mergers) {

        // Get lock's min & max
        final byte[] lockMin = lock.getMin();
        final byte[] lockMax = lock.getMax();

    startOver:
        while (true) {

            // Get locks whose min is < lockMax
            final NavigableSet<Lock> lhs = lockMax == null ? this.locksByMin :
              this.locksByMin.headSet(Lock.getMinKey(lockMax, false), false);

            // Get locks whose max is > lockMin
            final NavigableSet<Lock> rhs = lockMin == null ? this.locksByMax : this.locksByMax.tailSet(
              Lock.getMaxKey(ByteUtil.getNextKey(lockMin), false), true);

            // Find overlapping locks and check for conflicts
            final HashSet<Lock> overlaps = new HashSet<>();
            for (Lock other : lhs) {

                // Does this lock overlap?
                if (!rhs.contains(other))
                    continue;

                // Do this lock & other lock conflict?
                if (lock.conflictsWith(other)) {

                    // Handle possible hold timeout of other lock's owner
                    if (this.holdTimeout != 0) {
                        final long currentTime = System.nanoTime() - this.nanoBasis;
                        if (this.holdDeadlines.containsKey(other)) {
                            final long holdDeadline = this.holdDeadlines.get(other.owner);
                            if (currentTime >= holdDeadline) {                              // hold timeout has expired for 'other'
                                this.holdDeadlines.put(other.owner, null);
                                this.doRelease(other.owner);
                                continue startOver;
                            }
                        } else {
                            final long holdDeadline = currentTime + (this.holdTimeout * 1000000L);
                            this.holdDeadlines.put(other.owner, holdDeadline);
                        }
                    }

                    // Can't acquire lock due to conflict
                    return false;
                }

                // Add overlap
                overlaps.add(other);
            }

            //System.out.println(Thread.currentThread().getName() + ": LockChecker: BEFORE: lock = " + lock + "\n"
            //+ "  lockByMin = " + this.locksByMin + "\n"
            //+ "  lockByMax = " + this.locksByMax + "\n"
            //+ "   overlaps = " + overlaps + "\n");

            // Find overlaps we can merge with
            for (Lock other : overlaps) {
                if (lock.mergeWith(other) != null)
                    mergers.add(other);
            }

            //System.out.println(Thread.currentThread().getName() + ": LockChecker: AFTER: lock = " + lock + "\n"
            //+ "  lockByMin = " + this.locksByMin + "\n"
            //+ "  lockByMax = " + this.locksByMax + "\n"
            //+ "    mergers = " + mergers + "\n");

            // Done
            return true;
        }
    }

// LockResult

    /**
     * Possible return values from {@link LockManager#lock LockManager.lock()}.
     */
    public enum LockResult {

        /**
         * The lock was successfully acquired.
         */
        SUCCESS,

        /**
         * The timeout expired while waiting to acquire the lock.
         */
        WAIT_TIMEOUT_EXPIRED,

        /**
         * The owner's hold timeout expired.
         */
        HOLD_TIMEOUT_EXPIRED;
    }

// LockChecker predicate

    private class LockChecker implements Predicate {

        private final Lock lock;
        private final ArrayList<Lock> mergers = new ArrayList<>();

        LockChecker(Lock lock) {
            this.lock = lock;
        }

        public List<Lock> getMergers() {
            return this.mergers;
        }

        @Override
        public boolean test() {

            // Reset state
            this.mergers.clear();

            // See if we can lock
            return LockManager.this.checkLock(this.lock, this.mergers);
        }
    }
}
