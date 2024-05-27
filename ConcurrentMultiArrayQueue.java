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
 * thread-safe and (by itself) garbage-free Queue of Objects for Java.
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
 * <p>ConcurrentMultiArrayQueue does not provide any iterators or size methods, because they would be
 * inherently inaccurate in multi-threaded regime and of less value.
 *
 * <p>The Queue can also be used as a pool of Objects in garbage-free environments, e.g. for recycling
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
    // Naming of the Queue is practical in bigger projects with many Queues
    private final String name;

    // Array of references to actual (exponentially growing) arrays of Objects
    private final Object[][] rings;  // the actual array of arrays of Objects
    private final int firstArraySize;  // size of rings[0] (the first array of Objects)
    private volatile int ringsMaxIndex;  // maximum index that contains an allocated array of Objects: only grows + volatile

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
    // The Queue is full if the writer stands immediately behind the reader (that is in the previous round).
    //
    // This implies that the Queue can take at most one less Objects than there are positions in the array(s).
    // This is a traditional way of implementing ring-buffers and it leads to a simpler and faster code
    // compared to an alternative implementation which would utilize the array(s) fully.
    //
    // When the writerPosition/readerPosition stands on a diversion then it means that the writer/reader
    // is on the return path of that diversion (also not on its entry side!).
    // (The edge case rings[0][0] where firstArraySize == 1 obeys to that rule as well: the next move would
    // go "beyond array size", also: move to begin of rings[0] (i.e. to the same position rings[0][0])
    // which is then the entry side of the diversion from rings[0] that will be immediately followed forward.)
    //
    // Each long has this structure:
    //
    //    mask 0x0000_0000_7FFF_FFFFL: location (index, ix) in the array of Objects
    //                                 (31 bits)
    //    mask 0x0000_000F_8000_0000L: location (rings index, rix) in the rings array
    //                                 (5 bits, also up to 31 is possible, which is sufficient)
    //    mask 0x0000_0010_0000_0000L: flag that an extension by new array of Objects + new diversion is in progress
    //                                 (1 bit, in writerPosition only)
    //    mask 0xFFFF_FFE0_0000_0000L: round number to prevent the ABA problem, incremented on each passing of rings[0][0]
    //                                 (27 bits, in writerPosition and readerPosition only, leftmost item, so overflow is ok)
    //
    // ad ABA problem: There still exists a (miniature) chance for it to occur: If a thread gets preempted
    // for such an excessive time during which the 27-bit round number rolls over back to its old value.

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
     * which would altogether give a cumulative capacity of 4.160.749.537 minus 1 Objects.
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
     * <li>(if {@code cntAllowedExtensions < 0}) an unbounded Queue (more precisely: bounded only by the
     *     technical limit that none of the (exponentially growing) arrays of Objects would become bigger
     *     than the maximum value of an (signed) int (with some reserve, as Java itself does not allow
     *     to allocate arrays exactly to that limit)
     * <li>(if {@code cntAllowedExtensions == 0}) bounded Queue with all capacity pre-allocated and final
     * <li>(if {@code 0 < cntAllowedExtensions}) bounded Queue with only a partial capacity pre-allocated
     *     and allowed to extend (grow) the given number of times. E.g. if initialCapacity == 100 and
     *     cntAllowedExtensions == 3, then the Queue can grow up to four arrays with sizes 101, 202, 404 and 808,
     *     giving the final capacity of 1515 minus 1 Objects.
     * </ul>
     *
     * @param name name of the Queue
     * @param initialCapacity capacity of the first array of Objects (its size will be by one bigger)
     * @param cntAllowedExtensions how many times is the Queue allowed to extend (see above)
     * @throws IllegalArgumentException if initialCapacity is negative
     * @throws IllegalArgumentException if cntAllowedExtensions is unreachable
     */
    public ConcurrentMultiArrayQueue(String name, int initialCapacity, int cntAllowedExtensions)
    throws IllegalArgumentException
    {
        this.name = name;
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("ConcurrentMultiArrayQueue " + name + ": initialCapacity is negative");
        }
        firstArraySize = 1 + initialCapacity;
        int rixMax = 0;
        long arraySize = firstArraySize;
        for (;;)
        {
            arraySize <<= 1;  // times two
            if (0x0000_0000_7FFF_FFF0L < arraySize) break;  // stop if bigger than the maximum size of an int minus a reserve
            rixMax ++;
        }
        if (0 <= cntAllowedExtensions)
        {
            if (cntAllowedExtensions <= rixMax)
            {
                rixMax = cntAllowedExtensions;
            }
            else
            {
                throw new IllegalArgumentException("ConcurrentMultiArrayQueue " + name + ": cntAllowedExtensions is unreachable");
            }
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

        writerPosition = new AtomicLong(firstArraySize - 1);  // next prospective move leads to rings[0][0]
        readerPosition = new AtomicLong(firstArraySize - 1);  // ditto
    }

    // ___  _  _ ___  _    _ ____    _  _ ____ ___ _  _ ____ ___  ____
    // |__] |  | |__] |    | |       |\/| |___  |  |__| |  | |  \ [__
    // |    |__| |__] |___ | |___    |  | |___  |  |  | |__| |__/ ___]

    /**
     * Gets the name of the Queue
     *
     * @return name of the Queue
     */
    public String getName() { return name; }

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
            throw new IllegalArgumentException("ConcurrentMultiArrayQueue " + name + ": enqueued Object is null");
        }

        // HINT:
        // Before reading this program it might be easier to read BlockingMultiArrayQueue
        // (that is free of the temporal intricacies that must handled by this concurrent code)

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
            // do not go ahead with the extension-in-progress flag, because origWriter == the expected value in the CAS
            // (so wait for the other writer to finish the extension (or fail))
            if (0 != (origWriter & 0x0000_0010_0000_0000L))
            {
                Thread.yield();  // the other writer is in spot C, so give him time
                continue start_anew;
            }

            long writerRound = (origWriter & 0xFFFF_FFE0_0000_0000L);
            long writerPos   = (origWriter & 0x0000_000F_FFFF_FFFFL);

            long origReader = readerPosition.get();  // volatile read

            long readerRound = (origReader & 0xFFFF_FFE0_0000_0000L);
            long readerPos   = (origReader & 0x0000_000F_FFFF_FFFFL);

            int rixMax = ringsMaxIndex;  // volatile read
            boolean isQueueExtensionPossible = ((1 + rixMax) < rings.length);  // if there is room yet for the extension

            // as we have not read the three volatiles atomically at one instant, we have to discuss the possible implications:
            //
            // generally: this method consists of a preparation phase (where certain decisions are taken) and the execution phases
            // (that actually change state by writing the volatiles) that are guarded by the CASes (on writerPosition)
            //
            // in the argumentation below we must respect that after having read the three volatiles, "our" values
            // are "loosened" from the "current" state (in the "now" sense), so e.g. we might report "Queue is full"
            // although just "now" (e.g. at the instant of return from the method) space has become available
            //
            // writerPosition --> (time lag) --> readerPosition --> (preparation phase) --> CASes
            // ----------------------------------------------------------------------------------
            // we see a readerPosition that is newer (or same) than it was at the instant when we have read the writerPosition
            //
            // during the time lag the readerPosition might have moved forward and might even have overtaken "our" origWriter
            // in the next round (in which case the writerPosition must have moved too, so the CASes would fail (good!))
            //
            // the lesson however is: in the decisions about reporting "Queue is full" we must compare
            // not only the positions but also the rounds!
            //
            // writerPosition --> (time lag) --> ringsMaxIndex --> (preparation phase) --> CASes
            // --------------------------------------------------------------------------------
            // this order means that we DO see the eventual diversion created by the writer that brought writerPosition
            // to the place from which we start (which would be in the new array already)
            //
            // what if an eventual later writer created a diversion during the time lag (i.e. we DO see the incremented ringsMaxIndex):
            // - we WOULD use that diversion while moving forward
            // - because writerPosition must have moved forward in that case, our CASes would fail (good!)
            // - in that situation: what if we hit the reader (that is in the previous round) "from behind":
            // -- if "our" isQueueExtensionPossible is false, we report "Queue is full" (correct: this means
            //    that the final set of diversions was at our disposal and we have hit the reader nevertheless).
            //    And: isQueueExtensionPossible being false is a terminal state (it never becomes true anymore).
            // -- if "our" isQueueExtensionPossible is true, we attempt to extend the Queue (of which the CAS will fail (good!))
            //
            // what if an eventual later writer created a diversion during the preparation phase (i.e. we DO NOT see
            // the incremented ringsMaxIndex):
            // - we WILL NOT use that diversion while going forward, so caution:
            // - because writerPosition must have moved forward in that case, our CASes would fail (good!)
            // - because an extension was created AFTER we have read ringsMaxIndex, we MUST have isQueueExtensionPossible true,
            //   so when we hit the reader "from behind" (possibly falsely due to not seeing a diversion) we will always attempt
            //   to extend the Queue (of which the CAS will fail (good!))
            //
            // combination of the two cases above (of which the latter case must be the last):
            // argumentation from the latter case applies
            //
            // if none of the cases above occurred, we have a ringsMaxIndex (and isQueueExtensionPossible) that corresponds
            // to "our" origWriter
            //
            // the above argumentation applies to the forward-looking check too, the only difference is
            // that "do nothing" is the analogon to reporting "Queue is full" on that place
            //
            // the last place to discuss is where we have hit the reader "from behind" on the return path of a diversion:
            // this is a specific situation where we go exactly one step "back" on the return path of the diversion
            // that has (historically) brought us to the array where we currently are (i.e. known to us for sure),
            // so we are not concerned about new diversions created by eventual later writers
            // but only about isQueueExtensionPossible:
            // - if "our" isQueueExtensionPossible is true, then it must have been true at the volatile reads
            //   of the writer/reader positions as well and we have the impossible situation to rightly throw the AssertionError
            // - if "our" isQueueExtensionPossible is false, then we have two possibilities:
            // -- it has been false too at the instant when we have read the writerPosition: in that case we correctly
            //    report "Queue is full"
            // -- it has been true at the instant when we have read the writerPosition but was false at the instant
            //    when we have read ringsMaxIndex: so yes - this still is the impossible situation that we guard
            //    with the AssertionError, but here we would report "Queue is full" instead.
            //    But, well: the situation IS impossible (and the contrary would have been raised to attention
            //    by the AssertionError on many other occasions), so this edge case is ok too.

            int writerRix, writerIx;
            boolean extendQueue = false;

            go_forward:
            for (;;)
            {
                writerPos ++;  // prospective move forward (the increment never overflows into the rix due to the reserve)
                writerRix = (int) ((writerPos & 0x0000_000F_8000_0000L) >> 31);
                writerIx  = (int)  (writerPos & 0x0000_0000_7FFF_FFFFL);

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
                        writerRix = (int) ((writerPos & 0x0000_000F_8000_0000L) >> 31);
                        writerIx  = (int)  (writerPos & 0x0000_0000_7FFF_FFFFL);

                        if ((readerRound + 0x0000_0020_0000_0000L) == writerRound)
                        {
                            // if the prospective move has hit the reader (that is in the previous round) "from behind"
                            if (readerPos == writerPos)
                            {
                                if (isQueueExtensionPossible)
                                {
                                    // context: the writer that preceded us (the one that successfully moved to the last position
                                    // in the array (i.e. to the position from which we start)) made a forward-looking check
                                    // to prevent the next writer from hitting the reader on the return path of a diversion
                                    // and has not seen the reader there (otherwise it would have created a new diversion and gone there)
                                    //
                                    // so now: as the reader cannot move back, it is impossible that we hit him, but better check ...

                                    throw new AssertionError("ConcurrentMultiArrayQueue " + name
                                                           + ": hit reader on the return path of a diversion", null);
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
                // that starts at 1 + writerRix suffices (i.e. a short linear search)

                for (int rix = 1 + writerRix; rix <= rixMax; rix ++)
                {
                    if (diversions[rix - 1] == writerPos)
                    {
                        writerPos = (((long) rix) << 31);  // move to the first element of the array of Objects the diversion leads to
                        writerRix = rix;
                        writerIx  = 0;
                    }
                }

                if ((readerRound + 0x0000_0020_0000_0000L) == writerRound)
                {
                    // if the prospective move has hit the reader (that is in the previous round) "from behind"
                    if (readerPos == writerPos)
                    {
                        if (isQueueExtensionPossible)
                        {
                            extendQueue = true;
                        }
                        else
                        {
                            return false;  // Queue is full
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
                        for (; (0 != testNextWriterRix) && ((firstArraySize << testNextWriterRix) == (1 + testNextWriterIx)) ;)
                        {
                            testNextWriterPos = diversions[testNextWriterRix - 1];  // follow the diversion back
                            if (readerPos == testNextWriterPos)  // if we would hit the reader
                            {
                                if (isQueueExtensionPossible)
                                {
                                    extendQueue = true;
                                }
                                break test_next;
                            }
                            testNextWriterRix = (int) ((testNextWriterPos & 0x0000_000F_8000_0000L) >> 31);
                            testNextWriterIx  = (int)  (testNextWriterPos & 0x0000_0000_7FFF_FFFFL);
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

                    boolean inProgressFlagCleared = false;
                    int rixMaxNew = 1 + rixMax;

                    try
                    {
                        // impossible for writerPos to be already in the diversions array, but better check ...
                        for (int rix = 1; rix <= rixMax; rix ++)
                        {
                            if (diversions[rix - 1] == writerPos)
                            {
                                throw new AssertionError("ConcurrentMultiArrayQueue " + name
                                                       + ": duplicity in the diversions array", null);
                            }
                        }

                        // allocate new array of Objects of size firstArraySize * (2 ^ ringsIndex)
                        Object[] newArray = new Object[firstArraySize << rixMaxNew];

                        rings[rixMaxNew] = newArray;  // put its reference into rings
                        newArray[0] = object;  // put Object into the first array element of the new array

                        diversions[rixMaxNew - 1] = writerPos;  // the new diversion = the prospective writer position

                        ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex (volatile write AFTER writes to rings and diversions)

                        writerPos = (((long) rixMaxNew) << 31);  // new writer position = first array element of the new array

                        // AtomicLong.compareAndSet has the memory effects of both reading and writing volatile variables
                        // (so no writes can get re-ordered after it)

                        if (! writerPosition.compareAndSet(origWriter | 0x0000_0010_0000_0000L, writerRound | writerPos))
                        {
                            throw new AssertionError("ConcurrentMultiArrayQueue " + name
                                                   + ": CAS to advance from in-progress flag failed", null);
                        }

                        // visibility of the just-written data to other writers and readers:
                        //
                        // we write: rings and diversions --> ringsMaxIndex (volatile) --> writerPosition (CAS)
                        //
                        // they read: writerPosition (volatile) --> ringsMaxIndex (volatile) --> rings and diversions
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
                                throw new AssertionError("ConcurrentMultiArrayQueue " + name
                                                       + ": CAS to revert in-progress flag failed", null);
                            }
                        }
                    }
                }
                else
                {
                    continue start_anew;  // CAS failed, start anew
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

                Object[] array = null;

                wait_pos_cleared:
                for (;;)
                {
                    if (origWriter != writerPosition.get()) continue start_anew;
                    if (null == array) array = rings[writerRix];  // set array
                    if (null == array[writerIx]) break wait_pos_cleared;  // position is cleared, go ahead
                    Thread.yield();  // here it is very probable that the reader is in spot B, so give him time
                }

                // CAS the prospective writer position
                if (writerPosition.compareAndSet(origWriter, writerRound | writerPos))
                {
                    // (spot A relevant to lock-freedom, see Paper)
                    // the writer position is now "ours", so write the Object
                    // (this cannot get re-ordered before the CAS, because it depends on the CAS)
                    // (however it can get re-ordered to later - after the return)
                    // (but: readers wait till they see the position filled)

                    array[writerIx] = object;
                    return true;
                }
                else
                {
                    continue start_anew;  // CAS failed, start anew
                }
            }
        }
    }

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

            if ((writerRound == readerRound) && (writerPos == readerPos))
            {
                return null;  // the reader stands on the writer: the Queue is empty
            }

            int rixMax = ringsMaxIndex;  // volatile read

            // as we have not read the three volatiles atomically at one instant, we have to discuss the possible implications
            // (for more general comments see the enqueue method)
            //
            // readerPosition --> (time lag) --> writerPosition --> (preparation phase) --> CAS
            // --------------------------------------------------------------------------------
            // we see a writerPosition that is newer (or same) than it was at the instant when we have read the readerPosition
            //
            // during the time lag the writerPosition might have moved forward and might even have caught "our" origReader
            // in the next round (in which case the readerPosition must have moved too, so the CAS would fail (good!))
            //
            // the lesson however is: in the decision about reporting "Queue is empty" we must compare
            // not only the positions but also the rounds!
            //
            // writerPosition --> (time lag) --> ringsMaxIndex --> (preparation phase) --> CAS
            // ------------------------------------------------------------------------------
            // we get beyond the "Queue is empty" return only if origWriter is ahead of us by at least one "move".
            // We are moving forward by exactly that one "move". The above order of reads ensures that we know about
            // (and do not miss) the eventual diversion created by the writer that went ahead of us by that one "move".
            //
            // diversions created by later writers would not stand "in our way". The writer could only reach "our way" again
            // one round later and that would mean that readerPosition must have moved too, so the CAS would fail (good!)
            //
            // would it be possible that we jump over the writer in the course of going over the diversions forward?
            // (asking also because the writer first writes the new diversion and then only it updates writerPosition).
            // answer no: We (the reader) move forward only if we see that we can do so, based on the writerPosition.
            // The writer does not (temporarily) step onto the entry side of the diversion it creates.
            // If we see a writer on an entry side of a diversion then that would mean that "our" origWriter is old
            // (i.e. from the previous round or even earlier) and then also "our" origReader would be old,
            // so the CAS would fail (good!).
            //
            // the last interesting situation is when a writer has hit us "from behind" and created a new diversion
            // on that place - also we suddenly appear on the return path of the just-inserted new diversion.
            // Here it is irrelevant if we see the new diversion or not, because for leaving the position we do not need
            // that information.

            int readerRix, readerIx;

            go_forward:
            for (;;)
            {
                readerPos ++;  // prospective move forward (the increment never overflows into the rix due to the reserve)
                readerRix = (int) ((readerPos & 0x0000_000F_8000_0000L) >> 31);
                readerIx  = (int)  (readerPos & 0x0000_0000_7FFF_FFFFL);

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
                        readerRix = (int) ((readerPos & 0x0000_000F_8000_0000L) >> 31);
                        readerIx  = (int)  (readerPos & 0x0000_0000_7FFF_FFFFL);
                        break go_forward;  // the prospective move forward is done, we are on the return path of a diversion
                    }
                }

                // if the prospective move reached (an entry side of) a diversion: follow it - to the beginning of respective rings[rix]
                // (another diversion may sit there, so then continue following)
                //
                // a diversion that leads to an array of Objects always precedes (in the diversions array) any diversions
                // that lead from that array of Objects, so one bottom-up pass through the diversions array
                // that starts at 1 + readerRix suffices (i.e. a short linear search)

                for (int rix = 1 + readerRix; rix <= rixMax; rix ++)
                {
                    if (diversions[rix - 1] == readerPos)
                    {
                        readerPos = (((long) rix) << 31);  // move to the first element of the array of Objects the diversion leads to
                        readerRix = rix;
                        readerIx  = 0;
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

            Object[] array = null;
            Object object;

            wait_pos_filled:
            for (;;)
            {
                if (origReader != readerPosition.get()) continue start_anew;
                if (null == array) array = rings[readerRix];  // set array
                if (null != (object = array[readerIx])) break wait_pos_filled;  // position is filled, go ahead
                Thread.yield();  // here it is very probable that the writer is in spot A, so give him time
            }

            // CAS the prospective reader position
            if (readerPosition.compareAndSet(origReader, readerRound | readerPos))
            {
                // (spot B relevant to lock-freedom, see Paper)
                // the reader position is now "ours", so clear it and return the Object
                // (this cannot get re-ordered before the CAS, because it depends on the CAS)
                // (however it can get re-ordered to later - after the return)
                // (but: writers wait till they see the position cleared)

                array[readerIx] = null;
                return (T) object;
            }
            else
            {
                continue start_anew;  // CAS failed, start anew
            }
        }
    }
}

