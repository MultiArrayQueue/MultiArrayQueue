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

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * BlockingMultiArrayQueue is a simple, optionally bounded or unbounded, thread-safe (lock-based)
 * and (by itself) garbage-free Queue of Objects for Java.
 *
 * <p>The Multi-Array Queue is described in the Paper available at
 * <a href="https://MultiArrayQueue.github.io/Paper_MultiArrayQueue.pdf">https://MultiArrayQueue.github.io/Paper_MultiArrayQueue.pdf</a>.
 * Measured performance figures are in the Paper as well.
 *
 * <p>The Queue is backed by arrays of Objects with exponentially growing sizes, of which all are in use,
 * but only the first one (with {@code initialCapacity}) is allocated up-front.
 *
 * <p>This Queue uses {@code ReentrantLock} for serializing of the enqueue and dequeue operations.
 * This also allows for waiting (if the Queue is empty on dequeue or full on enqueue).
 *
 * <p>If you require a concurrent variant of this Queue (based on atomic Compare-And-Swap (CAS) instructions),
 * use {@code ConcurrentMultiArrayQueue} instead.
 *
 * <p>BlockingMultiArrayQueue does not provide any iterators or size methods.
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
public class BlockingMultiArrayQueue<T>
{
    // Naming of the Queue is practical in bigger projects with many Queues
    private final String name;

    // Array of references to actual (exponentially growing) arrays of Objects
    private final Object[][] rings;  // the actual array of arrays of Objects
    private final int firstArraySize;  // size of rings[0] (the first array of Objects)
    private int ringsMaxIndex;  // maximum index that contains an allocated array of Objects: only grows

    // Array of diversions (to higher arrays of Objects) and writer/reader positions
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
    // Interpretation of writerPosition: The last position written.
    //
    // Interpretation of readerPosition: The last position read.
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
    //    mask 0x0000_0000_7FFF_FFFFL: location (index, ix) in the array of Objects
    //                                 (31 bits)
    //    mask 0x0000_000F_8000_0000L: location (rings index, rix) in the rings array
    //                                 (5 bits, also up to 31 is possible, which is sufficient)
    //    mask 0x0000_0010_0000_0000L: unused
    //                                 (1 bit)
    //    mask 0xFFFF_FFE0_0000_0000L: unused
    //                                 (27 bits)
    private final long[] diversions;
    private long writerPosition;
    private long readerPosition;

    private final ReentrantLock lock;
    private final Condition notEmpty;  // Condition for waiting readers
    private final Condition notFull;  // Condition for waiting writers

    // ____ ____ _  _ ____ ___ ____ _  _ ____ ___ ____ ____ ____
    // |    |  | |\ | [__   |  |__/ |  | |     |  |  | |__/ [__
    // |___ |__| | \| ___]  |  |  \ |__| |___  |  |__| |  \ ___]

    /**
     * Creates a BlockingMultiArrayQueue with default values:
     * named "Queue", with initial capacity 30 (size 31 of the first array of Objects), unbounded, no fair ordering policy.
     *
     * <p>This initial capacity allows for up to 26 subsequent (exponentially growing) arrays of Objects
     * which would altogether give a cumulative capacity of 4.160.749.537 minus 1 Objects.
     *
     * <p>The initial memory footprint of this Queue will be (27 + 26 + 31 + 9) 64-bit-words
     * + a couple of Java object headers, also circa 1 kilobyte.
     */
    public BlockingMultiArrayQueue()
    {
        this("Queue", 30, -1, false);
    }

    /**
     * Creates a BlockingMultiArrayQueue with the given name, given capacity of the first array of Objects,
     * a limit of how many times the Queue is allowed to extend and the given decision about fair ordering policy.
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
     *     giving the final capacity of 1515 minus 1 Objects.
     * </ul>
     *
     * @param name name of the Queue
     * @param initialCapacity capacity of the first array of Objects (its size will be by one bigger)
     * @param cntAllowedExtensions how many times is the Queue allowed to extend (see above)
     * @param fair true: ReentrantLock should use a fair ordering policy
     * @throws IllegalArgumentException if initialCapacity is negative
     * @throws IllegalArgumentException if initialCapacity is beyond maximum (less reserve)
     * @throws IllegalArgumentException if cntAllowedExtensions has invalid value
     * @throws IllegalArgumentException if cntAllowedExtensions is unreachable
     */
    public BlockingMultiArrayQueue(String name, int initialCapacity, int cntAllowedExtensions, boolean fair)
    throws IllegalArgumentException
    {
        this.name = name;
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": initialCapacity " + initialCapacity + " is negative");
        }
        if (0x7FFF_FFF0 <= initialCapacity) {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": initialCapacity " + initialCapacity + " is beyond maximum (less reserve)");
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
        if (cntAllowedExtensions < -1)
        {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": cntAllowedExtensions has invalid value " + cntAllowedExtensions);
        }
        else if (0 <= cntAllowedExtensions)
        {
            if (cntAllowedExtensions <= rixMax)
            {
                rixMax = cntAllowedExtensions;
            }
            else
            {
                throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": cntAllowedExtensions " + cntAllowedExtensions + " is unreachable");
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

        writerPosition = firstArraySize - 1;  // next prospective move leads to rings[0][0]
        readerPosition = firstArraySize - 1;  // ditto

        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull =  lock.newCondition();
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
     * Lock-based Enqueue of an Object
     *
     * @param object the Object to enqueue
     * @param waitNanos 0: return false immediately if Queue is full, -1: wait forever, positive: maximum nanoseconds to wait
     * @return true if enqueued, false if not enqueued due to full Queue
     * @throws IllegalArgumentException if the enqueued Object is null
     * @throws IllegalArgumentException if waitNanos has invalid value
     * @throws InterruptedException if the current thread is interrupted
     */
    public boolean enqueue(T object, long waitNanos)
    throws IllegalArgumentException, InterruptedException
    {
        if (null == object) {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": enqueued Object is null");
        }
        if (waitNanos < -1L) {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": waitNanos on enqueue has invalid value " + waitNanos);
        }

        ReentrantLock lock = this.lock;
        lock.lockInterruptibly();

        try
        {
            long writerPos, readerPos;
            int rixMax;
            int writerRix, writerIx;
            boolean extendQueue;

            start_anew:
            for (;;)
            {
                writerPos = writerPosition;

                readerPos = readerPosition;

                rixMax = ringsMaxIndex;
                boolean isQueueExtensionPossible = ((1 + rixMax) < rings.length);  // if there is room yet for the extension

                extendQueue = false;
                boolean queueIsFull = false;

                go_forward:
                for (;;)
                {
                    writerPos ++;  // prospective move forward (the increment never overflows into the rix due to the reserve)
                    writerRix = (int) ((writerPos & 0x0000_000F_8000_0000L) >>> 31);
                    writerIx  = (int)  (writerPos & 0x0000_0000_7FFF_FFFFL);

                    // if the prospective move goes "beyond" the end of rings[writerRix]
                    if ((firstArraySize << writerRix) == writerIx)
                    {
                        if (0 == writerRix)  // if in rings[0]
                        {
                            writerPos = 0L;  // move to rings[0][0]
                            writerRix = 0;
                            writerIx  = 0;
                            // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
                        }
                        else  // i.e. we are in a "higher" rings[N]
                        {
                            writerPos = diversions[writerRix - 1];  // follow diversion[N-1] back
                            writerRix = (int) ((writerPos & 0x0000_000F_8000_0000L) >>> 31);
                            writerIx  = (int)  (writerPos & 0x0000_0000_7FFF_FFFFL);

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

                                    throw new AssertionError("BlockingMultiArrayQueue " + name
                                                           + ": hit reader on the return path of a diversion", null);
                                }
                                else
                                {
                                    queueIsFull = true;
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

                    // if the prospective move has hit the reader (that is in the previous round) "from behind"
                    if (readerPos == writerPos)
                    {
                        if (isQueueExtensionPossible)
                        {
                            extendQueue = true;
                        }
                        else
                        {
                            queueIsFull = true;
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
                            testNextWriterRix = (int) ((testNextWriterPos & 0x0000_000F_8000_0000L) >>> 31);
                            testNextWriterIx  = (int)  (testNextWriterPos & 0x0000_0000_7FFF_FFFFL);
                        }
                    }
                    break go_forward;  // prospective move forward is now done
                }

                if (queueIsFull)
                {
                    if (-1L == waitNanos)  // wait forever
                    {
                        notFull.await();
                    }
                    else if (0L == waitNanos)  // return false immediately
                    {
                        return false;
                    }
                    else  // wait maximum nanoseconds
                    {
                        waitNanos = notFull.awaitNanos(waitNanos);
                        if (waitNanos < 0L) waitNanos = 0L;
                    }
                }
                else
                {
                    break start_anew;  // not full: go ahead
                }
            }

            // preparations are done, start the actual work
            if (extendQueue)
            {
                int rixMaxNew = 1 + rixMax;

                // impossible for writerPos to be already in the diversions array, but better check ...
                for (int rix = 1; rix <= rixMax; rix ++)
                {
                    if (diversions[rix - 1] == writerPos)
                    {
                        throw new AssertionError("BlockingMultiArrayQueue " + name
                                               + ": duplicity in the diversions array", null);
                    }
                }

                // allocate new array of Objects of size firstArraySize * (2 ^ ringsIndex)
                Object[] newArray = new Object[firstArraySize << rixMaxNew];

                rings[rixMaxNew] = newArray;  // put its reference into rings
                newArray[0] = object;  // put Object into the first array element of the new array

                diversions[rixMaxNew - 1] = writerPos;  // the new diversion = the prospective writer position

                ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex

                writerPosition = (((long) rixMaxNew) << 31);  // new writer position = first array element of the new array
            }
            else  // no extendQueue
            {
                writerPosition = writerPos;  // new writer position = prospective writer position
                Object[] array = rings[writerRix];
                array[writerIx] = object;  // write the Object
            }
            notEmpty.signal();  // signal waiting readers
            return true;
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Lock-based Dequeue of an Object
     *
     * @param waitNanos 0: return null immediately if Queue is empty, -1: wait forever, positive: maximum nanoseconds to wait
     * @return the dequeued Object, or null if the Queue is empty
     * @throws IllegalArgumentException if waitNanos has invalid value
     * @throws InterruptedException if the current thread is interrupted
     */
    @SuppressWarnings("unchecked")
    public T dequeue(long waitNanos)
    throws IllegalArgumentException, InterruptedException
    {
        if (waitNanos < -1L) {
            throw new IllegalArgumentException("BlockingMultiArrayQueue " + name + ": waitNanos on dequeue has invalid value " + waitNanos);
        }

        ReentrantLock lock = this.lock;
        lock.lockInterruptibly();

        try
        {
            long readerPos, writerPos;

            start_anew:
            for (;;)
            {
                readerPos = readerPosition;

                writerPos = writerPosition;

                if (writerPos == readerPos)  // the reader stands on the writer: the Queue is empty
                {
                    if (-1L == waitNanos)  // wait forever
                    {
                        notEmpty.await();
                    }
                    else if (0L == waitNanos)  // return null immediately
                    {
                        return null;
                    }
                    else  // wait maximum nanoseconds
                    {
                        waitNanos = notEmpty.awaitNanos(waitNanos);
                        if (waitNanos < 0L) waitNanos = 0L;
                    }
                }
                else
                {
                    break start_anew;  // not empty: go ahead
                }
            }

            int rixMax = ringsMaxIndex;

            int readerRix, readerIx;

            go_forward:
            for (;;)
            {
                readerPos ++;  // prospective move forward (the increment never overflows into the rix due to the reserve)
                readerRix = (int) ((readerPos & 0x0000_000F_8000_0000L) >>> 31);
                readerIx  = (int)  (readerPos & 0x0000_0000_7FFF_FFFFL);

                // if the prospective move goes "beyond" the end of rings[readerRix]
                if ((firstArraySize << readerRix) == readerIx)
                {
                    if (0 == readerRix)  // if in rings[0]
                    {
                        readerPos = 0L;  // move to rings[0][0]
                        readerRix = 0;
                        readerIx  = 0;
                        // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
                    }
                    else  // i.e. we are in a "higher" rings[N]
                    {
                        readerPos = diversions[readerRix - 1];  // follow diversion[N-1] back
                        readerRix = (int) ((readerPos & 0x0000_000F_8000_0000L) >>> 31);
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

            readerPosition = readerPos;  // new reader position = prospective reader position
            Object[] array = rings[readerRix];
            Object object = array[readerIx];  // read the Object
            array[readerIx] = null;  // clear the reader position
            notFull.signal();  // signal waiting writers
            return (T) object;  // return the Object
        }
        finally
        {
            lock.unlock();
        }
    }
}

