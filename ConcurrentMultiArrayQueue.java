/*
MIT License

Copyright (c) 2024 Vít Procházka

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package com.github.MultiArrayQueue;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ConcurrentMultiArrayQueue is a simple, optionally bounded or unbounded, multiple-writer multiple-reader
 * thread-safe and (by itself) garbage-free FIFO Queue of Objects for Java.
 *
 * <p>The Multi-Array Queue is described in the Paper available at
 * <a href="https://MultiArrayQueue.github.io/Paper_MultiArrayQueue.pdf">https://MultiArrayQueue.github.io/Paper_MultiArrayQueue.pdf</a>.
 * Measured performance figures are in the Paper as well.
 *
 * <p>The Queue is backed by arrays of Objects with exponentially growing sizes, of which all are in use,
 * but only the first one (with {@code initialCapacity}) is allocated up-front.
 *
 * <p>This Queue uses atomic Compare-And-Swap (CAS) instructions for serializing of the enqueue and dequeue operations.
 *
 * <p>The Queue does not strictly fulfill the requirement for "lock-free" that "in a bounded number of my steps
 * somebody makes progress" because there exist three spots in the program code (A and B tiny and C the extension
 * operation which is not so tiny) where preemption of a thread could block other threads for beyond
 * "bounded number of my steps". A theoretical termination of a thread in one of these spots
 * would leave the Queue in a blocked state.
 *
 * <p>If you require a blocking variant of this Queue (based on {@code ReentrantLock}) that also allows for waiting
 * (if the Queue is empty on dequeue or full on enqueue), use {@code BlockingMultiArrayQueue} instead.
 *
 * <p>The Queue can also be used as a pool for re-use of Objects in garbage-free environments, e.g. for recycling
 * of allocated memory blocks (of the same size), messages, connections to (the same) database and the like.
 * For differing sorts of Objects use different pools (Queues).
 *
 * <p>Currently (2024) this code is in its early stage and only for academic interest, not for production use.
 * No public JAR is provided: If interested, use this source code file.
 * Reviews, tests and comments are welcome.
 *
 * <p>GitHub Repository:
 * <a href="https://github.com/MultiArrayQueue/MultiArrayQueue">https://github.com/MultiArrayQueue/MultiArrayQueue</a>
 *
 * @author Vít Procházka
 * @param <T> the type of Object held in the Queue
 */
public class ConcurrentMultiArrayQueue<T>
{
    private static final String qType = "ConcurrentMultiArrayQueue";

    // Naming of the Queue is practical in bigger projects with many Queues
    private final String name;

    // Array of references to actual (exponentially growing) arrays of Objects
    private final Object[][] rings;  // the actual array of arrays of Objects
    private volatile int ringsMaxIndex;  // maximum index that contains an allocated array of Objects: only grows + volatile
    private final int firstArraySize;  // size of rings[0] (the first array of Objects)

    // Array of diversions (to higher arrays of Objects) and Atomic writer/reader positions
    //
    // diversions[0]: position of the diversion that leads to rings[1]
    // diversions[1]: position of the diversion that leads to rings[2]
    //    ... and so on
    //
    // Interpretation of diversion[ringsIndex]:
    // Divert to rings[ringsIndex] immediately before it + on the return path go back exactly onto it.
    // It is not allowed for two or more diversions to exist on one place (because otherwise we could not conclude
    // from the position alone on which diversion we are). A diversion, once inserted, is immutable.
    //
    // Interpretation of writerPosition:
    // The last position written. The writer (enqueuer) prepares a prospective writer position and tries to CAS it.
    // If successful, it then writes the Object into this new writer position.
    //
    // Interpretation of readerPosition:
    // The last position read. The reader (dequeuer) prepares a prospective reader position and tries to CAS it.
    // If successful, it then reads and removes the Object from this new reader position.
    //
    // The Queue is empty if the reader stands on the same position as the writer.
    // The Queue is full if the writer stands immediately behind the reader (that is in the previous round)
    // and the Queue cannot extend anymore.
    //
    // This implies that the Queue can take at most one less Objects than there are positions in the array(s).
    // This is a traditional way of implementing ring-buffers and it leads to a simpler and faster code
    // compared to an alternative implementation which would utilize the array(s) fully.
    //
    // When the writerPosition/readerPosition stands on a diversion then it means that the writer/reader
    // is on the return path of that diversion (also not on its entry side!).
    // (The edge case rings[0][0] where firstArraySize == 1 obeys that rule as well: the next move would
    // go "beyond array size", also: move to begin of rings[0] (i.e. to the same position rings[0][0])
    // which is then the entry side of the diversion from rings[0] that will be immediately followed forward.)
    //
    // Each long has this structure:
    //
    //    mask 0x0000_0000_0000_001FL: location (rings index, rix) in the rings array
    //                                 (5 bits, also up to 31 is possible, which is sufficient)
    //    mask 0x0000_000F_FFFF_FFE0L: location (index, ix) in the array of Objects
    //                                 (31 bits)
    //    mask 0x0000_0010_0000_0000L: flag that an extension by new array of Objects + new diversion is in progress
    //                                 (1 bit, in writerPosition only)
    //    mask 0xFFFF_FFE0_0000_0000L: round number to prevent the ABA problem, incremented on each passing of rings[0][0]
    //                                 (27 bits, in writerPosition and readerPosition only, leftmost item, so overflow is ok)
    //
    // ad ABA problem: There still exists a (miniature) chance for it to occur: If a thread gets preempted
    // for such an excessive time during which the 27-bit round number would roll over back to its old value.
    //
    // Design footnote 1: It has been proposed from the public, giving reference to the paper
    // Resizable Arrays in Optimal Time and Space by Brodnik, Carlsson, Demaine, Munro and Sedgewick,
    // to address the rings array and the position in the array of Objects using a single index.
    // This would result in better packing of rix and ix at the cost of extra computation (of MSB position).
    // The most appealing benefit would be the reduction of the size of the diversions array (from long[] to int[]).
    // (Let's earmark this proposal as a candidate for future optimizations.)

    private final long[] diversions;
    private final AtomicLong writerPosition;
    private final AtomicLong readerPosition;

    // ____ ____ _  _ ____ ___ ____ _  _ ____ ___ ____ ____ ____
    // |    |  | |\ | [__   |  |__/ |  | |     |  |  | |__/ [__
    // |___ |__| | \| ___]  |  |  \ |__| |___  |  |__| |  \ ___]

    /**
     * Creates a ConcurrentMultiArrayQueue with default values:
     * named "Queue", with initial capacity 30 (size 31 of the first array of Objects), unbounded.
     *
     * <p>This initial capacity allows for up to 26 subsequent (exponentially growing) arrays of Objects
     * which would altogether give a maximum (cumulative) capacity of 4.160.749.537 minus 1 Objects.
     *
     * <p>The initial memory footprint of this Queue will be (27 + 26 + 31 + 6) 64-bit-words
     * + 2 AtomicLongs + a couple of Java object headers, also circa 1 kilobyte.
     */
    public ConcurrentMultiArrayQueue()
    {
        this("Queue", 30, -1);
    }

    /**
     * Creates a ConcurrentMultiArrayQueue with the given name, given capacity of the first array of Objects
     * and a limit of how many times the Queue is allowed to extend.
     *
     * <p>The input parameters allow for the following three modes:
     * <ul>
     * <li>(if {@code cntAllowedExtensions == -1}) an unbounded Queue (more precisely: bounded only by the
     *     technical limit that none of the (exponentially growing) arrays of Objects would become bigger
     *     than the maximum value of an (signed) int (with some reserve, as Java itself does not allow
     *     to allocate arrays exactly to that limit)
     * <li>(if {@code cntAllowedExtensions == 0}) bounded Queue with all capacity pre-allocated and final
     * <li>(if {@code 0 < cntAllowedExtensions}) bounded Queue with only a partial capacity pre-allocated
     *     and allowed to extend (grow) the given number of times. E.g. if initialCapacity == 100 and
     *     cntAllowedExtensions == 3, then the Queue can grow up to four arrays with sizes 101, 202, 404 and 808,
     *     giving a maximum capacity of 1515 minus 1 Objects.
     * </ul>
     *
     * @param name name of the Queue
     * @param initialCapacity capacity of the first array of Objects (its size will be by one bigger)
     * @param cntAllowedExtensions how many times is the Queue allowed to extend (see above)
     * @throws IllegalArgumentException if initialCapacity is negative
     * @throws IllegalArgumentException if initialCapacity is beyond maximum (less reserve)
     * @throws IllegalArgumentException if cntAllowedExtensions has invalid value
     * @throws IllegalArgumentException if cntAllowedExtensions is unreachable
     */
    public ConcurrentMultiArrayQueue(String name, int initialCapacity, int cntAllowedExtensions)
    throws IllegalArgumentException
    {
        this.name = name;
        if (initialCapacity < 0) {
            throw new IllegalArgumentException(String.format(
                "%s %s: initialCapacity %,d is negative", qType, name, initialCapacity));
        }
        if (0x7FFF_FFF0 <= initialCapacity) {
            throw new IllegalArgumentException(String.format(
                "%s %s: initialCapacity %,d is beyond maximum (less reserve)", qType, name, initialCapacity));
        }
        if (cntAllowedExtensions < -1) {
            throw new IllegalArgumentException(String.format(
                "%s %s: cntAllowedExtensions has invalid value %d", qType, name, cntAllowedExtensions));
        }
        firstArraySize = 1 + initialCapacity;
        int rixMax = 0;
        for (long arraySize = (long) firstArraySize ;;)
        {
            if ((0 <= cntAllowedExtensions) && (cntAllowedExtensions == rixMax)) break;
            arraySize <<= 1;  // times two
            if (0x0000_0000_7FFF_FFF0L < arraySize) break;  // stop if bigger than the maximum size of an int minus a reserve
            rixMax ++;
        }
        if ((0 <= cntAllowedExtensions) && (rixMax < cntAllowedExtensions)) {
            throw new IllegalArgumentException(String.format(
                "%s %s: cntAllowedExtensions %d is unreachable", qType, name, cntAllowedExtensions));
        }
        rings = new Object[1 + rixMax][];  // allocate the rings array
        rings[0] = new Object[firstArraySize];  // allocate the first array of Objects
        if (0 != rixMax)
        {
            diversions = new long[rixMax];  // allocate the array of diversions
        }
        else
        {
            diversions = null;
        }
        ringsMaxIndex = 0;  // we now start with only rings[0] allocated

        writerPosition = new AtomicLong(((long)(firstArraySize - 1)) << 5);  // next prospective move leads to rings[0][0]
        readerPosition = new AtomicLong(((long)(firstArraySize - 1)) << 5);  // ditto
    }

    /**
     * Gets the name of the Queue
     *
     * @return name of the Queue
     */
    public String getName() { return name; }

    /**
     * Gets the maximum capacity (when the Queue is fully extended)
     *
     * @return maximum capacity of the Queue
     */
    public long getMaximumCapacity()
    {
        long maxCapacity = (long)(firstArraySize - 1);
        int arraySize = firstArraySize;
        for (int i = 1; i < rings.length; i ++)
        {
            arraySize <<= 1;  // times two
            maxCapacity += arraySize;
        }
        return maxCapacity;
    }

    // ____ _  _ ____ _  _ ____ _  _ ____
    // |___ |\ | |  | |  | |___ |  | |___
    // |___ | \| |_\| |__| |___ |__| |___

    /**
     * Concurrent Enqueue of an Object
     *
     * <p>This method does not provide waiting if the Queue is full (because waiting is only possible with locks),
     * so if needed call this method repeatedly (with {@code Thread.yield()} recommended) to implement waiting.
     *
     * @param object the Object to enqueue
     * @return true if enqueued, false if not enqueued due to full Queue
     * @throws IllegalArgumentException if the enqueued Object is null
     */
    public boolean enqueue(T object)
    throws IllegalArgumentException
    {
        if (null == object) {
            throw new IllegalArgumentException(String.format(
                "%s %s: enqueued Object is null", qType, name));
        }

        // HINT:
        // Before reading this program it might be easier to read BlockingMultiArrayQueue
        // (that is free of the temporal intricacies that must handled by this concurrent code)

        boolean recheckFromFullyExtended = false;

        start_anew:
        for (;;)
        {
            // read the three volatiles in this order: writerPosition --> readerPosition --> ringsMaxIndex
            //
            // volatile ensures that the reads do not get re-ordered, because reading of other variables cannot get re-ordered
            // before reading a volatile variable
            //
            // AtomicLong.get() has the memory effects of reading a volatile variable
            // according to https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/package-summary.html

            long origWriter = writerPosition.get();  // volatile read

            // if another writer is currently extending the Queue:
            //
            // do not go ahead with the extension-in-progress flag (prevent CASes that would compare with an origWriter that contains
            // the extension-in-progress flag) --> wait for the other writer to finish the extension operation (or fail)
            if (0L != (origWriter & 0x0000_0010_0000_0000L))
            {
                Thread.yield();  // the other writer is in spot C, so give him time
                continue start_anew;
            }

            long writerRound = (origWriter & 0xFFFF_FFE0_0000_0000L);
            long writerPos   = (origWriter & 0x0000_000F_FFFF_FFFFL);

            long origReader = readerPosition.get();  // volatile read

            long readerRound = (origReader & 0xFFFF_FFE0_0000_0000L);
            long readerPos   = (origReader & 0x0000_000F_FFFF_FFFFL);

            int rixMax = -1, writerRix, writerIx;
            boolean extendQueue = false;

            go_forward:
            for (;;)
            {
                writerPos += 0x0000_0000_0000_0020L;  // prospective move forward (the increment never overflows due to the reserve)
                writerRix = (int) (writerPos & 0x0000_0000_0000_001FL);
                writerIx  = (int) (writerPos >>> 5);

                // if the prospective move goes "beyond" the end of rings[writerRix]
                if ((firstArraySize << writerRix) == writerIx)
                {
                    if (0 == writerRix)  // if in rings[0]
                    {
                        writerRound += 0x0000_0020_0000_0000L;  // we are passing rings[0][0], so increment round (overflow is ok)
                        writerPos = 0L;  // move to rings[0][0]
                        writerRix = 0;
                        writerIx  = 0;
                        // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
                    }
                    else  // i.e. we are in a "higher" rings[N]
                    {
                        writerPos = diversions[writerRix - 1];  // follow diversion[N-1] back
                        writerRix = (int) (writerPos & 0x0000_0000_0000_001FL);
                        writerIx  = (int) (writerPos >>> 5);

                        if ((readerRound + 0x0000_0020_0000_0000L) == writerRound)
                        {
                            // if the prospective move has hit the reader (that is in the previous round) "from behind"
                            if (readerPos == writerPos)
                            {
                                rixMax = ringsMaxIndex;  // volatile read
                                boolean isQueueExtensionPossible = ((1 + rixMax) < rings.length);  // if there is room yet for the extension
                                if (isQueueExtensionPossible)
                                {
                                    // context: the writer that preceded us (the one that successfully moved to the last position
                                    // in the array (i.e. to the position from which we start)) made a forward-looking check
                                    // to prevent the next writer from hitting the reader on the return path of a diversion
                                    // and has not seen the reader there (otherwise it would have created a new diversion and gone there)
                                    //
                                    // so now: as the reader cannot move back, it is impossible that we hit him, but better check ...

                                    throw new AssertionError(String.format(
                                        "%s %s: hit reader on the return path of a diversion (0x%X 0x%X 0x%X)",
                                        qType, name, origWriter, origReader, (writerRound | writerPos)), null);
                                }
                                else
                                {
                                    return false;  // Queue is full
                                }
                            }
                        }
                        break go_forward;  // the prospective move forward is done, we are on the return path of a diversion
                    }
                }

                // if the prospective move reached (an entry side of) a diversion: follow it - to the beginning of respective rings[rix]
                // (another diversion may sit there, so then continue following)
                //
                // a diversion that leads to an array of Objects always precedes (in the diversions array) any diversions
                // that lead from that array of Objects, so one bottom-up pass through the diversions array
                // that starts at the diversion to 1 + writerRix suffices (i.e. a short linear search)

                rixMax = ringsMaxIndex;  // volatile read

                for (int dix = writerRix; dix < rixMax; dix ++)  // for optimization: dix == rix - 1
                {
                    if (diversions[dix] == writerPos)
                    {
                        writerRix = 1 + dix;  // move to the first element of the array of Objects the diversion leads to
                        writerIx  = 0;
                        writerPos = ((long) writerRix);
                    }
                }

                if ((readerRound + 0x0000_0020_0000_0000L) == writerRound)
                {
                    // if the prospective move has hit the reader (that is in the previous round) "from behind"
                    if (readerPos == writerPos)
                    {
                        boolean isQueueExtensionPossible = ((1 + rixMax) < rings.length);  // if there is room yet for the extension
                        if (isQueueExtensionPossible)
                        {
                            extendQueue = true;
                        }
                        else  // the Queue is now fully extended (but might not have been at the reading of origWriter)
                        {
                            // (the following checks are necessary because there is no CAS that would guard the "Queue is full" outcome)
                            if (recheckFromFullyExtended)  // we have already re-checked from here, i.e. from the fully extended state
                            {
                                return false;  // Queue is full
                            }
                            else
                            {
                                int origWriterRix = (int) (origWriter & 0x0000_0000_0000_001FL);
                                if (rings.length == (1 + origWriterRix))  // then origWriter must be from the fully extended state
                                {
                                    return false;  // Queue is full
                                }
                                // if writerPosition has not changed, then origWriter must be from the fully extended state (as we are now)
                                else if (origWriter == writerPosition.get())  // volatile read
                                {
                                    return false;  // Queue is full
                                }
                                else  // origWriter is potentially stale from a past extension state of the Queue --> Start anew
                                {
                                    recheckFromFullyExtended = true;
                                    continue start_anew;
                                }
                            }
                        }
                    }

                    // the forward-looking check to prevent the next writer from hitting the reader "from behind"
                    // on the return path of a diversion (see Paper for explanation)
                    else
                    {
                        long testNextWriterPos = writerPos;
                        int testNextWriterRix = writerRix;
                        int testNextWriterIx = writerIx;

                        test_next:
                        for (; ((0 != testNextWriterRix) && ((firstArraySize << testNextWriterRix) == (1 + testNextWriterIx))) ;)
                        {
                            testNextWriterPos = diversions[testNextWriterRix - 1];  // follow the diversion back
                            if (readerPos == testNextWriterPos)  // if we would hit the reader
                            {
                                boolean isQueueExtensionPossible = ((1 + rixMax) < rings.length);  // if there is room yet for the extension
                                if (isQueueExtensionPossible)
                                {
                                    extendQueue = true;
                                }
                                break test_next;
                            }
                            testNextWriterRix = (int) (testNextWriterPos & 0x0000_0000_0000_001FL);
                            testNextWriterIx  = (int) (testNextWriterPos >>> 5);
                        }
                    }
                }
                break go_forward;  // prospective move forward is now done
            }

            // preparations are done, start the actual work
            if (extendQueue)
            {
                // CAS into the writer position our copy of the writer position + the extension-in-progress flag
                if (writerPosition.compareAndSet(origWriter, (origWriter | 0x0000_0010_0000_0000L)))
                {
                    // (spot C relevant to lock-freedom, see Paper)
                    // other writers are now "locked-out", so go ahead with extending the Queue + creating the new diversion
                    // (readers can continue their work but once they deplete the Queue, they cannot go past the writerPosition)

                    // Design footnote 2: It is by principle impossible to make the extension operation both garbage-free
                    // and lock-free in the strict sense, because that would require the allocation of the new array to be
                    // part of the preparation phase, executed potentially by multiple threads, but only one thread's CAS
                    // would succeed and its new array would be used, and the arrays prepared by the other threads
                    // would be useless (even useless for any future extensions).

                    boolean inProgressFlagCleared = false;

                    try
                    {
                        // impossible for writerPos to be already in the diversions array, but better check ...
                        for (int dix = 0; dix < rixMax; dix ++)  // for optimization: dix == rix - 1
                        {
                            if (diversions[dix] == writerPos)
                            {
                                throw new AssertionError(String.format(
                                    "%s %s: duplicity in the diversions array (0x%X 0x%X 0x%X)",
                                    qType, name, origWriter, origReader, (writerRound | writerPos)), null);
                            }
                        }

                        int rixMaxNew = 1 + rixMax;

                        // allocate new array of Objects of size firstArraySize * (2 ^ ringsIndex)
                        Object[] newArray = new Object[firstArraySize << rixMaxNew];

                        rings[rixMaxNew] = newArray;  // put its reference into rings
                        newArray[0] = object;  // put Object into the first array element of the new array

                        diversions[rixMax] = writerPos;  // the new diversion (index rixMaxNew - 1) = the prospective writer position

                        ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex (volatile write AFTER writes to rings and diversions)

                        writerPos = ((long) rixMaxNew);  // new writer position = first array element of the new array

                        // AtomicLong.compareAndSet has the memory effects of both reading and writing volatile variables
                        // according to https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/package-summary.html
                        // (so no writes can get re-ordered after it)

                        if (! writerPosition.compareAndSet((origWriter | 0x0000_0010_0000_0000L), (writerRound | writerPos)))
                        {
                            throw new AssertionError(String.format(
                                "%s %s: CAS to advance from in-progress flag failed (0x%X 0x%X 0x%X)",
                                qType, name, origWriter, origReader, (writerRound | writerPos)), null);
                        }

                        // visibility of the just-written data to other writers and readers:
                        //
                        // we write: rings and diversions --> ringsMaxIndex (volatile write) --> writerPosition (CAS)
                        //
                        // they read: writerPosition (AtomicLong.get()) --> ringsMaxIndex (volatile read) --> rings and diversions
                        //
                        // No our writes can get re-ordered after our CAS and no their reads can get re-ordered
                        // before their writerPosition.get()

                        inProgressFlagCleared = true;
                        return true;
                    }
                    finally
                    {
                        if (! inProgressFlagCleared)
                        {
                            if (! writerPosition.compareAndSet((origWriter | 0x0000_0010_0000_0000L), origWriter))
                            {
                                throw new AssertionError(String.format(
                                    "%s %s: CAS to revert in-progress flag failed (0x%X 0x%X 0x%X)",
                                    qType, name, origWriter, origReader, (writerRound | writerPos)), null);
                            }
                        }
                    }
                }
                else
                {
                    continue start_anew;  // CAS failed (i.e. lost the race against other writers) --> Start anew
                }
            }
            else  // no extendQueue
            {
                // wait for the prospective writer position to become cleared by the respective reader
                // (this has most probably already happened or shall occur "soon" (if the reader is in spot B))
                //
                // (writes to and reads of references are always atomic (JLS 17.7))
                //
                // if the writerPosition has moved forward during the waiting, we have to stop it,
                // because then another writer has in the meantime obtained (and written again) the position,
                // so we would wait forever
                //
                // (if writerPosition has moved, the CAS would fail anyway)
                //
                // the optimal order of the two tests has been found by measurements (i.e. is not a dogma)

                Object[] array = null;

                wait_pos_cleared:
                for (;;)
                {
                    if (origWriter != writerPosition.get()) continue start_anew;  // (volatile read)
                    if (null == array) array = rings[writerRix];  // set array if not yet set
                    if (null == array[writerIx]) break wait_pos_cleared;  // position is cleared, go ahead
                    Thread.yield();  // here it is very probable that the reader is in spot B, so give him time
                }

                // CAS the prospective writer position
                if (writerPosition.compareAndSet(origWriter, (writerRound | writerPos)))
                {
                    // (spot A relevant to lock-freedom, see Paper)
                    // the writer position is now "ours", so write the Object
                    // (this cannot get re-ordered before the CAS, because it depends on the CAS)
                    // (however it can get re-ordered to later - after the return)
                    // (but: readers wait till they see the position filled)

                    // Design footnote 3: The missing lock-freedom on this spot has perhaps the biggest
                    // practical relevance: Imagine the queue almost empty, i.e. the readers are
                    // close behind the writers. If a writer successfully moves writerPosition forward
                    // and gets preempted before actually writing the Object, then other writers
                    // can continue beyond that place, but readers will be blocked at that place
                    // until the preempted writer wakes up again and actually writes the Object.

                    // Design footnote 4: Theoretically it would be possible to re-arrange the algorithm
                    // to achieve lock-freedom in the strict sense on this spot, using the principles
                    // of the Michael & Scott Queue, i.e.:
                    // The linearization operation would be writing the Object to the array,
                    // and moving writerPosition forward could be helped by other threads.
                    // This would however require each Object to be accompanied by the round number,
                    // effectively doubling the memory consumption, and it would further require
                    // a double-width (128 bit) CAS, which is available e.g. on i86-64 (CMPXCHG16B),
                    // but is not accessible from Java.

                    // Design footnote 5: To implement lock-freedom on this spot by the principles of
                    // the a.m. Michael & Scott Queue via the operations available in Java,
                    // one option would be to store integers (instead of Objects) in the queue,
                    // so that the integers AND the round numbers could be together accommodated
                    // in the 64-bit AtomicLongs. This would however require the translation between
                    // the integers and the Objects to be done "somewhere" outside of the queue ...

                    // Design footnote 6: The other means of approaching lock-freedom on this spot
                    // are in the Paper: Double-Location CAS (rather theoretical) and pinning
                    // of threads to cores (== avoiding thread preemptions).

                    array[writerIx] = object;
                    return true;
                }
                else
                {
                    continue start_anew;  // CAS failed (i.e. lost the race against other writers) --> Start anew
                }
            }
        }
    }

    // ___  ____ ____ _  _ ____ _  _ ____
    // |  \ |___ |  | |  | |___ |  | |___
    // |__/ |___ |_\| |__| |___ |__| |___

    /**
     * Concurrent Dequeue of an Object
     *
     * <p>This method does not provide waiting if the Queue is empty (because waiting is only possible with locks),
     * so if needed call this method repeatedly (with {@code Thread.yield()} recommended) to implement waiting.
     *
     * @return the dequeued Object, or null if the Queue is empty
     */
    @SuppressWarnings("unchecked")
    public T dequeue()
    {
        start_anew:
        for (;;)
        {
            // read the three volatiles in this order: readerPosition --> writerPosition --> ringsMaxIndex
            //
            // volatile ensures that the reads do not get re-ordered, because reading of other variables cannot get re-ordered
            // before reading a volatile variable
            //
            // AtomicLong.get() has the memory effects of reading a volatile variable

            long origReader = readerPosition.get();  // volatile read

            long readerRound = (origReader & 0xFFFF_FFE0_0000_0000L);
            long readerPos   = (origReader & 0x0000_000F_FFFF_FFFFL);

            long origWriter = writerPosition.get();  // volatile read

            long writerRound = (origWriter & 0xFFFF_FFE0_0000_0000L);
            long writerPos   = (origWriter & 0x0000_000F_FFFF_FFFFL);

            // Design footnote 7: The reader does not evaluate the extension-in-progress flag.
            // This means that if the reader stands on the writer, the Queue is seen as empty
            // even when an extension operation is in progress (but has not yet finished).
            // This was a design decision. An alternative ("slower/stickier") solution is imaginable
            // where the reader would wait for the writer to finish the extension operation in such case.

            if ((writerRound == readerRound) && (writerPos == readerPos))
            {
                return null;  // the reader stands on the writer: the Queue is empty
            }

            // An interesting question:
            // Would it be possible that we (the reader) overtake the writer in the course of "cascading" over the diversions forward?
            //
            // Let's think through that and gain some extra insights:
            //
            //    As a diversion always leads to beginning of an array, an eventual "cascade" can only have this form:
            //
            //    rings [x][y] --> rings [m][0] --> rings [n][0] --> rings [p][0] --> ...
            //
            //    The extension operation consists of allocation of a new array, registering the respective diversion
            //    and moving writerPosition to the first element of the new array. From this follows that an eventual "cascade"
            //    can get prolonged at most by one in any given round.
            //
            //    The writerPosition does not (temporarily) step onto the entry side of the diversion that is being created,
            //    nor onto any place in the middle of the "cascade".
            //
            //    We (the reader) move forward only if we see that we can do so, based on observing writerPosition.
            //    If we cannot move forward, we immediately return "Queue is empty" (without even reading ringsMaxIndex).
            //
            //    Only after we have seen that we do not stand on writerPosition, we move forward, that might include the "cascading"
            //    until we arrive at the first element of the new array. If we pass along the writer during this "cascading",
            //    then it can only be the writer on the return path of a diversion.
            //
            //    To construct a case where we would genuinely overtake the writer: We would capture the readerPosition
            //    and writerPosition and then get preempted. During that time things in the Queue move forward by at least
            //    one round and the "cascade" gets prolonged. Then we wake up, read ringsMaxIndex and see the prolonged "cascade".
            //    If "our" origWriter sits in the middle of the now-prolonged "cascade", then "our" prospective reader could overtake it.
            //    But this whole means that "our" origWriter would be (strictly) more than one round old, and then also
            //    "our" origReader would be at least that old (because it was read first), so the CAS would fail (good!).

            int readerRix, readerIx;

            go_forward:
            for (;;)
            {
                readerPos += 0x0000_0000_0000_0020L;  // prospective move forward (the increment never overflows due to the reserve)
                readerRix = (int) (readerPos & 0x0000_0000_0000_001FL);
                readerIx  = (int) (readerPos >>> 5);

                // if the prospective move goes "beyond" the end of rings[readerRix]
                if ((firstArraySize << readerRix) == readerIx)
                {
                    if (0 == readerRix)  // if in rings[0]
                    {
                        readerRound += 0x0000_0020_0000_0000L;  // we are passing rings[0][0], so increment round (overflow is ok)
                        readerPos = 0L;  // move to rings[0][0]
                        readerRix = 0;
                        readerIx  = 0;
                        // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
                    }
                    else  // i.e. we are in a "higher" rings[N]
                    {
                        readerPos = diversions[readerRix - 1];  // follow diversion[N-1] back
                        readerRix = (int) (readerPos & 0x0000_0000_0000_001FL);
                        readerIx  = (int) (readerPos >>> 5);
                        break go_forward;  // the prospective move forward is done, we are on the return path of a diversion
                    }
                }

                // if the prospective move reached (an entry side of) a diversion: follow it - to the beginning of respective rings[rix]
                // (another diversion may sit there, so then continue following)
                //
                // a diversion that leads to an array of Objects always precedes (in the diversions array) any diversions
                // that lead from that array of Objects, so one bottom-up pass through the diversions array
                // that starts at the diversion to 1 + readerRix suffices (i.e. a short linear search)

                int rixMax = ringsMaxIndex;  // volatile read

                for (int dix = readerRix; dix < rixMax; dix ++)  // for optimization: dix == rix - 1
                {
                    if (diversions[dix] == readerPos)
                    {
                        readerRix = 1 + dix;  // move to the first element of the array of Objects the diversion leads to
                        readerIx  = 0;
                        readerPos = ((long) readerRix);
                    }
                }
                break go_forward;  // prospective move forward is now done
            }

            // wait for the prospective reader position to become filled by the respective writer
            // (this has most probably already happened or shall occur "soon" (if the writer is in spot A))
            //
            // (writes to and reads of references are always atomic (JLS 17.7))
            //
            // if the readerPosition has moved forward during the waiting, we have to stop it,
            // because then another reader has in the meantime obtained (and cleared again) the position,
            // so we would wait forever
            //
            // (if readerPosition has moved, the CAS would fail anyway)
            //
            // the optimal order of the two tests has been found by measurements (i.e. is not a dogma)

            Object[] array = null;
            Object object;

            wait_pos_filled:
            for (;;)
            {
                if (origReader != readerPosition.get()) continue start_anew;  // (volatile read)
                if (null == array) array = rings[readerRix];  // set array if not yet set
                if (null != (object = array[readerIx])) break wait_pos_filled;  // position is filled, go ahead
                Thread.yield();  // here it is very probable that the writer is in spot A, so give him time
            }

            // CAS the prospective reader position
            if (readerPosition.compareAndSet(origReader, (readerRound | readerPos)))
            {
                // (spot B relevant to lock-freedom, see Paper)
                // the reader position is now "ours", so clear it and return the Object
                // (this cannot get re-ordered before the CAS, because it depends on the CAS)
                // (however it can get re-ordered to later - after the return)
                // (but: writers wait till they see the position cleared)

                // Design footnotes 4,5,6 apply on this spot accordingly.

                array[readerIx] = null;
                return (T) object;
            }
            else
            {
                continue start_anew;  // CAS failed (i.e. lost the race against other readers) --> Start anew
            }
        }
    }

    // Design footnote 8: Performance-wise, as a Queue that uses CAS, the ConcurrentMultiArrayQueue
    // presumably cannot keep up with competitor Queues that use Fetch-and-Increment.

    // Design footnote 9: On machines with a high number of CPU cores, it might make sense to comment-out the Thread.yield()s.

    // Design footnote 10: No countermeasures against False Sharing (like paddings) were attempted / implemented.

    /**
     * Concurrent isEmpty method
     *
     * @return true if the Queue is empty, false otherwise
     */
    public boolean isEmpty()
    {
        long origReader = readerPosition.get();  // volatile read

        long readerRound = (origReader & 0xFFFF_FFE0_0000_0000L);
        long readerPos   = (origReader & 0x0000_000F_FFFF_FFFFL);

        long origWriter = writerPosition.get();  // volatile read

        long writerRound = (origWriter & 0xFFFF_FFE0_0000_0000L);
        long writerPos   = (origWriter & 0x0000_000F_FFFF_FFFFL);

        return ((writerRound == readerRound) && (writerPos == readerPos));
    }

    /* _    _ _  _ ____ ____ ____ _ ___  ____ ___  _ _    _ ___ _   _
       |    | |\ | |___ |__| |__/ |   /  |__| |__] | |    |  |   \_/
       |___ | | \| |___ |  | |  \ |  /__ |  | |__] | |___ |  |    |

    Linearizability (mainly by Herlihy and Wing) is an important concept in proving correctness of concurrent algorithms.

    In practical terms, linearizability condenses to establishing linearization points, which are indivisible points in time
    at which the operations instantaneously take effect.

    The idea is that by ordering the concurrently running operations by their linearization points, one obtains
    a linear (i.e. sequential / single threaded) execution history of that operations that give the same results.

    It is advantageous to prove linearizability theoretically via the linearization points,
    not only because it provides insights, but also because testing it experimentally may be intractable:
    Imagine a situation with 10 threads running operations on the Queue concurrently. With how many linear execution histories
    (permutations) of the 10 operations one would have to compare the results: 10! = 3,6 million.

    In the following we identify the linearization points of all Operations and prove that if any actions occur
    outside of the linearization points (i.e. not atomically with them), then that actions are either irrelevant
    to the concurrent Operations or correctly handled in them.

    The proofs are given in Plain English without mathematical formalisms.

    Operation 1: The regular Enqueue (without Queue extension)
    ----------------------------------------------------------

    The linearization point is the successful CAS on writerPosition that exchanges the
    original writer position against the newly prepared (prospective) writer position.

    Nothing happens before this linearization point (except of reads and work on local variables).

    After this linearization point the Object is actually written to the respective array (on the new writerPosition).
    This does not go atomically together with the CAS! So let's investigate it:

    This ex-post write is irrelevant to concurrent Operations 1, 2, 3 and 5, but is relevant to Operation 4
    that would need to Dequeue that Object:

    Operation 4 handles this by program code the allows it to reach its CAS on readerPosition (its own linearization point)
    only after it has seen that the Object was actually written to that position in the array.

    A more complete picture is that the following four-state diagram (for each array position) is in place:

    1. Operation 1 moves writerPosition to the position via its CAS (introducing a short-lived state in spot A of the writer)
    2. Operation 1 actually writes the Object to the position
    3. Operation 4 moves readerPosition to the position via its CAS (introducing a short-lived state in spot B of the reader)
    4. Operation 4 actually reads and clears the Object from the position

    Operation 2. The Enqueue with Queue extension
    ---------------------------------------------

    The linearization point is the successful CAS on writerPosition that exchanges the
    original writer position against the new writer position that is the first element of the new array.

    Note that this CAS only concludes the previous (non-atomic) series of steps of the extension of the Queue
    which begins with the opening CAS (that implants the extension-in-progress flag into the writerPosition,
    thus locking-out eventual concurrent Operations 1 and 2). Of the mentioned extension steps,
    the allocation of the new array is the one that may (theoretically) fail, in which case the program code
    reverts the opening CAS. Hence, it is the concluding CAS that must be the linearization point.

    This concluding CAS is the last step of the Operation.

    There is however one write before the concluding CAS (but after the opening CAS) that may be relevant
    to concurrent Operations: The increment of ringsMaxIndex (that in turn exposes the new array and the new diversion
    to the other Operations)! Let's investigate it:

    * Impact on concurrent Operations 1 and 2: The increment of ringsMaxIndex is enclosed between the opening CAS
      and the concluding CAS (both on writerPosition). Operations 1 and 2 start their processing only if they
      (at the beginning) have read a writerPosition that was without the extension-in-progress flag,
      and their CASes check if writerPosition has not changed since. So the increment of ringsMaxIndex cannot go
      unnoticed by the concurrent Operations 1 and 2 (their CASes will fail (good!), so they will start anew).

    * Impact on concurrent Operation 3: This Operation is not guarded by a CAS. On the other hand, it "just"
      needs to know that it has read writerPosition and readerPosition from the fully extended state of the Queue
      (which is its terminal state). Details on obtaining that assurance under a possible concurrent scenario
      are outlined under Operation 3.

    * Impact on concurrent Operation 4: New diversions are created in the gap between the writerPosition
      and the readerPosition (that is in the previous round). Three situations are possible:

    ** The most probable situation is that the readerPosition suddenly appears on the return path of the just-inserted
       new diversion. Here it is irrelevant if Operation 4 sees the new diversion or not, because for leaving that place
       it does not need that information.

    ** The opposite extreme (possible, especially in the initial phases) is that the readerPosition stands on the writerPosition
       from which the Operation 2 starts. Then as long as Operation 2 has not moved writerPosition forward, Operation 4 cannot start
       and the outcome is "Queue is empty". Operation 2 moves writerPosition forward as the last step of the extension of the Queue.
       Only then Operation 4 starts its processing, and because it reads ringsMaxIndex after having read writerPosition, it will see
       the incremented ringsMaxIndex and will not miss the newly created diversion.

    ** If the readerPosition is between these two extremes, then it is irrelevant if Operation 4 sees the incremented ringsMaxIndex
       or not, because it cannot encounter the entry side of the newly created diversion in that range.

    * Impact on concurrent Operation 5: no impact (it does not evaluate ringsMaxIndex)

    Last remark on Operation 2: The distinction between "extend Queue" and "Queue is full" is controlled by ringsMaxIndex
    as well (whether it is below its maximum or at its maximum). As ringsMaxIndex only grows, chances are that Operation 2
    has read its below-maximum value but "now" it is at its maximum value. In that concurrent case "extend Queue" will be chosen,
    of which the CAS will fail (good!) for reasons already discussed.

    This last remark applies to the forward-looking check too, the only difference is that "do nothing"
    is the analogon to reporting "Queue is full" on that spot.

    Operation 3. A failed Enqueue (due to full Queue)
    -------------------------------------------------

    This outcome is reached if the prospective new writer position hits the readerPosition (that is in the previous round)
    "from behind". In other words: readerPosition is one step ahead in the previous round (diversions considered).

    Because writerPosition is read first, and both positions can only move forward,
    and writerPosition can never catch the readerPosition in the previous round,
    that condition means that the writerPosition must not have moved forward between the two reads.

    So the second read is the linearization point and at that instant the Queue must have indeed been full
    (i.e. the difference between the counts of successful Enqueue CASes and successful Dequeue CASes
    at that instant must have been equal to the maximum capacity of the Queue).

    This all must have happened in a situation when the Queue was already fully extended and the writerPosition
    (and then implicitly also the readerPosition (because read second)) has been read from that state of the Queue.

    If the last mentioned circumstance cannot be assured from the data at the respective spot in the program code,
    the Operation starts anew and in the second iteration that assurance is given (because the Queue never shrinks).

    If the "hit from behind" scenario occurs against a readerPosition that is on the return path of a diversion,
    then the "Queue fully extended" condition is implicitly given, due to the forward-looking feature of the writer (see Paper).
    The program code however still (can be removed in the future) evaluates (simplistically) the condition in order to throw
    an AssertionError when violated (more precisely (keeping in mind a possible concurrent scenario): when violated without doubt
    from the data at the respective spot).

    Operation 4. The regular Dequeue
    --------------------------------

    The linearization point is the successful CAS on readerPosition that exchanges the
    original reader position against the newly prepared (prospective) reader position.

    Nothing happens before this linearization point (except of reads and work on local variables).

    After this linearization point the Object is actually read and cleared from the respective array (on the new readerPosition).
    This does not go atomically together with the CAS! So let's investigate it:

    This ex-post write is irrelevant to concurrent Operations 2, 3, 4 and 5, but is relevant to Operation 1
    (in the next round) that would need to re-use that array position for a new Enqueue:

    Operation 1 handles this by program code the allows it to reach its CAS on writerPosition (its own linearization point)
    only after it has seen that the Object was actually cleared from that position in the array.

    Operation 5. A failed Dequeue (due to empty Queue)
    --------------------------------------------------

    This outcome is reached if readerPosition stands on the writerPosition in the same round
    (the extension-in-progress flag not considered).

    Because readerPosition is read first, and both positions can only move forward,
    and readerPosition can never get ahead of the writerPosition in the same round,
    that equality means that the readerPosition must not have moved forward between the two reads.

    So the second read is the linearization point and at that instant the Queue must have indeed been empty
    (i.e. the count of successful Enqueue CASes must have been equal to the count of successful Dequeue CASes
    at that instant).
    */
}

