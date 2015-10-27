/**
 * Copyright 2010 - 2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.env;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.hash.HashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

final class ReentrantTransactionDispatcher {

    private final int availablePermits;
    @NotNull
    private final Map<Thread, Integer> threadPermits;
    @NotNull
    private final NavigableSet<Long> regularQueue;
    @NotNull
    private final NavigableSet<Long> exclusiveQueue;
    @NotNull
    private final Object syncObject;
    private long acquireOrder;
    private int acquiredPermits;

    ReentrantTransactionDispatcher(final int maxSimultaneousTransactions) {
        if (maxSimultaneousTransactions < 1) {
            throw new IllegalArgumentException("maxSimultaneousTransactions < 1");
        }
        availablePermits = maxSimultaneousTransactions;
        threadPermits = new HashMap<>();
        regularQueue = new TreeSet<>();
        exclusiveQueue = new TreeSet<>();
        syncObject = new Object();
        acquireOrder = 0;
        acquiredPermits = 0;
    }

    int getAvailablePermits() {
        synchronized (syncObject) {
            return availablePermits - acquiredPermits;
        }
    }

    /**
     * Acquire transaction with a single permit in a thread. Transactions are acquired reentrantly, i.e.
     * with respect to transactions already acquired in the thread.
     */
    void acquireTransaction(@NotNull final Thread thread) {
        synchronized (syncObject) {
            final int currentThreadPermits = getThreadPermitsToAcquire(thread);
            if (acquiredPermits == availablePermits || !regularQueue.isEmpty()) {
                final long currentOrder = acquireOrder++;
                regularQueue.add(currentOrder);
                do {
                    waitForSyncObject();
                } while (acquiredPermits == availablePermits || regularQueue.first() != currentOrder);
                regularQueue.pollFirst();
            }
            ++acquiredPermits;
            threadPermits.put(thread, currentThreadPermits + 1);
        }
    }

    /**
     * Acquire exclusive transaction in a thread. Transactions are acquired reentrantly, i.e. with respect
     * to transactions already acquired in the thread.
     *
     * @return the number of acquired permits.
     */
    int acquireExclusiveTransaction(@NotNull final Thread thread) {
        synchronized (syncObject) {
            final int currentThreadPermits = getThreadPermitsToAcquire(thread);
            final int permitsToAcquire = availablePermits - currentThreadPermits;
            if (acquiredPermits > availablePermits - permitsToAcquire || !regularQueue.isEmpty()) {
                final long currentOrder = acquireOrder++;
                NavigableSet<Long> threadQueue = regularQueue;
                threadQueue.add(currentOrder);
                while (true) {
                    waitForSyncObject();
                    if (threadQueue.first() != currentOrder) {
                        continue;
                    }
                    if (acquiredPermits <= availablePermits - permitsToAcquire) {
                        break;
                    }
                    // if an exclusive transaction cannot be acquired fairly (in its turn)
                    // try to shuffle it to the queue of exclusive transactions
                    if (threadQueue == regularQueue) {
                        syncObject.notifyAll();
                        threadQueue.pollFirst();
                        threadQueue = exclusiveQueue;
                        threadQueue.add(currentOrder);
                    }
                }
                threadQueue.pollFirst();
            }
            acquiredPermits += permitsToAcquire;
            threadPermits.put(thread, currentThreadPermits + permitsToAcquire);
            return permitsToAcquire;
        }
    }

    /**
     * Acquire exclusive transaction in a thread if no other transaction tries to acquire exclusive permits.
     *
     * @return the number of acquired permits or 0 if acquisition failed.
     */
    int tryAcquireExclusiveTransaction(@NotNull final Thread thread, long timeout) {
        final long started = System.currentTimeMillis();
        synchronized (syncObject) {
            final int currentThreadPermits = getThreadPermitsToAcquire(thread);
            int permitsToAcquire = availablePermits - currentThreadPermits;
            if (acquiredPermits > availablePermits - permitsToAcquire || !regularQueue.isEmpty()) {
                final long currentOrder = acquireOrder++;
                NavigableSet<Long> threadQueue = regularQueue;
                threadQueue.add(currentOrder);
                while (true) {
                    waitForSyncObject(timeout);
                    if (threadQueue.first() == currentOrder) {
                        if (acquiredPermits <= availablePermits - permitsToAcquire) {
                            break;
                        }
                        // if an exclusive transaction cannot be acquired fairly (in its turn) try to shuffle
                        // it to the queue of exclusive transactions if only if the queue is empty
                        // otherwise return 0 permits, i.e. exclusive transaction cannot be acquired
                        if (threadQueue == regularQueue) {
                            syncObject.notifyAll();
                            threadQueue.pollFirst();
                            if (!exclusiveQueue.isEmpty()) {
                                return 0;
                            }
                            threadQueue = exclusiveQueue;
                            threadQueue.add(currentOrder);
                        }
                    }
                    final long currentTime = System.currentTimeMillis();
                    if (started + timeout <= currentTime) {
                        syncObject.notifyAll();
                        threadQueue.remove(currentOrder);
                        return 0;
                    }
                    timeout -= (currentTime - started);
                }
                threadQueue.pollFirst();
            }
            acquiredPermits += permitsToAcquire;
            threadPermits.put(thread, currentThreadPermits + permitsToAcquire);
            return permitsToAcquire;
        }
    }

    void acquireTransaction(@NotNull final TransactionBase txn, @NotNull final Environment env) {
        final Thread creatingThread = txn.getCreatingThread();
        if (txn.isExclusive()) {
            final boolean isGCTransaction = txn.isGCTransaction();
            if (txn.wasCreatedExclusive() && !isGCTransaction) {
                txn.setAcquiredPermits(acquireExclusiveTransaction(creatingThread));
                return;
            }
            final EnvironmentConfig ec = env.getEnvironmentConfig();
            final int acquiredPermits = tryAcquireExclusiveTransaction(creatingThread,
                    isGCTransaction ? ec.getGcTransactionAcquireTimeout() : ec.getEnvTxnReplayTimeout());
            if (acquiredPermits > 0) {
                txn.setAcquiredPermits(acquiredPermits);
                return;
            }
            txn.setExclusive(false);
        }
        acquireTransaction(creatingThread);
        txn.setAcquiredPermits(1);
    }

    /**
     * Release transaction that was acquired in a thread with specified permits.
     */
    void releaseTransaction(@NotNull final Thread thread, final int permits) {
        synchronized (syncObject) {
            int currentThreadPermits = getThreadPermits(thread);
            if (permits > currentThreadPermits) {
                throw new ExodusException("Can't release more permits than it was acquired");
            }
            acquiredPermits -= permits;
            currentThreadPermits -= permits;
            if (currentThreadPermits == 0) {
                threadPermits.remove(thread);
            } else {
                threadPermits.put(thread, currentThreadPermits);
            }
            syncObject.notifyAll();
        }
    }

    void releaseTransaction(@NotNull final TransactionBase txn) {
        releaseTransaction(txn.getCreatingThread(), txn.getAcquiredPermits());
    }

    int acquirerCount() {
        synchronized (syncObject) {
            return regularQueue.size();
        }
    }

    int exclusiveAcquirerCount() {
        synchronized (syncObject) {
            return exclusiveQueue.size();
        }
    }

    private int getThreadPermits(@NotNull final Thread thread) {
        final Integer result = threadPermits.get(thread);
        return result == null ? 0 : result;
    }

    private int getThreadPermitsToAcquire(@NotNull Thread thread) {
        final int currentThreadPermits = getThreadPermits(thread);
        if (currentThreadPermits == availablePermits) {
            throw new ExodusException("No more permits are available to acquire a transaction");
        }
        return currentThreadPermits;
    }

    private void waitForSyncObject() {
        try {
            syncObject.wait();
        } catch (InterruptedException e) {
            throw ExodusException.toExodusException(e);
        }
    }

    private void waitForSyncObject(final long timeout) {
        try {
            syncObject.wait(timeout);
        } catch (InterruptedException e) {
            throw ExodusException.toExodusException(e);
        }
    }
}
