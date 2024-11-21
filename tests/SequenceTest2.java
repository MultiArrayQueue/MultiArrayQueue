/*
MIT License
Copyright (c) 2024 Vít Procházka

This is a two-threaded test that the Multi-Array Queues do not run out-of-sequence.
The random factor comes from the scheduling of the two Threads: the writer Thread and the reader Thread.

The tested Queue is regularly replaced by new Queues, to also test the extension phases.
In a two-threaded scenario, Queue replacements require the following technique:

    1. The reader Thread is the "orchestrator" of the situation.
    2. Generally, the writer Thread Enqueues into the Queue that is advertised by the reader Thread.
    3. The reader Thread creates a new empty Queue and advertises it.
    4. The writer Thread - in its next iteration - makes the first Enqueue into the new Queue.
    5. Meanwhile the reader Thread alternates between Dequeueing from the old Queue and the new Queue.
    6. Upon the first successful Dequeue from the new Queue:
    6a. The reader Thread remembers the received Object.
    6b. The reader Thread then Dequeues (drains) the old Queue empty (and checks the sequence of the received Objects).
    6c. The reader Thread then checks the sequence of the remembered Object from the new Queue.
    6d. The reader Thread then goes "back to normal": Dequeues and checks Objects from (only) the new Queue.

Another new technique deployed in this test is the use of a "recycle Queue" (to avoid massive production of garbage):
The reader Thread recycles Objects into the "recycle Queue" and the writer Thread gets recycled Objects from there.
This "recycle Queue" is of the same type as the tested Queue, but is unbounded (and not replaced).

The enqueued/dequeued Objects are long[1] (long arrays of size 1).

How to use: Download the Java files to a new directory, change to there and:

    javac -d . BlockingMultiArrayQueue.java ConcurrentMultiArrayQueue.java SequenceTest2.java
    java -classpath . com.github.MultiArrayQueue.SequenceTest2 BlockingMultiArrayQueue 5 0 3 5
    java -classpath . com.github.MultiArrayQueue.SequenceTest2 ConcurrentMultiArrayQueue 5 0 3 5

This will compile the Java files to subdirectory com/github/MultiArrayQueue and run the tests.

    command line args: SequenceTest2 <type of Queue> <secondsToRun> <initialCapacity> <cntAllowedExtensions> <replaceMillis>
*/

package com.github.MultiArrayQueue;

import java.util.concurrent.atomic.AtomicLong;

/*
 * Runnable of the writer Thread
 */
class SequenceTest2Thread implements Runnable
{
    @Override
    public void run()
    {
        try
        {
            long fullQueueHits = 0L;

            long[] object = null;

            writer_loop:
            for (;;)
            {
                boolean tmpReplacesHaveStopped = SequenceTest2.replacesHaveStopped;  // volatile read before Enqueue

                // if we do not have an Object from the last iteration (due to Queue full), try to get a recycled one
                if (null == object)
                {
                    object = SequenceTest2.recycleDequeue();
                }

                // if no recycled Object could be obtained, allocate a new one
                if (null == object)
                {
                    object = new long[1];
                    SequenceTest2.allocCounter.getAndIncrement();
                }

                object[0] = SequenceTest2.inSequence.get();  // write inSequence into the Object

                boolean success = SequenceTest2.enqueue(object);  // try Enqueue

                if (success)
                {
                    object = null;  // Object is now enqueued, forget its reference
                    SequenceTest2.inSequence.getAndIncrement();
                }
                else  // Queue was full
                {
                    fullQueueHits ++;
                    Thread.yield();
                }

                if (tmpReplacesHaveStopped) break writer_loop;
            }

            // put an eventual remaining Object back into the recycle Queue
            if (null != object) SequenceTest2.recycleEnqueue(object);

            System.out.printf("%s stopped at inSequence %,d and fullQueueHits %,d%n",
                Thread.currentThread().getName(), SequenceTest2.inSequence.get(), fullQueueHits);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            SequenceTest2.writerHasStopped = true;  // volatile write
        }
    }
}

/**
 * Main class
 */
public class SequenceTest2
{
    private enum State {
        STATE_NORMAL,
        STATE_REPLACE_WAIT_TRY_OLD,
        STATE_REPLACE_WAIT_TRY_NEW,
        STATE_REPLACE_DRAIN_OLD_AND_FINISH
      }

    private static volatile BlockingMultiArrayQueue<long[]> blockingMultiArrayQueue;
    private static volatile ConcurrentMultiArrayQueue<long[]> concurrentMultiArrayQueue;

    private static BlockingMultiArrayQueue<long[]> oldBlockingMultiArrayQueue;
    private static ConcurrentMultiArrayQueue<long[]> oldConcurrentMultiArrayQueue;

    private static BlockingMultiArrayQueue<long[]> recycleBlockingMultiArrayQueue;
    private static ConcurrentMultiArrayQueue<long[]> recycleConcurrentMultiArrayQueue;

    static AtomicLong allocCounter = new AtomicLong(0L);  // atomic counter of allocated (by any Thread) Objects
    static AtomicLong inSequence = new AtomicLong(0L);

    static volatile boolean replacesHaveStopped = false;
    static volatile boolean writerHasStopped = false;

    // method to Enqueue into the current Queue (type as specified on the command line)
    static boolean enqueue(long[] object)
    throws InterruptedException
    {
               if (null != blockingMultiArrayQueue) {
            return blockingMultiArrayQueue.enqueue(object, 0L);
        } else if (null != concurrentMultiArrayQueue) {
            return concurrentMultiArrayQueue.enqueue(object);
        }
        throw new AssertionError("no Queue is set", null);
    }

    // method to Dequeue from the current Queue (type as specified on the command line)
    private static long[] dequeue()
    throws InterruptedException
    {
               if (null != blockingMultiArrayQueue) {
            return blockingMultiArrayQueue.dequeue(0L);
        } else if (null != concurrentMultiArrayQueue) {
            return concurrentMultiArrayQueue.dequeue();
        }
        throw new AssertionError("no Queue is set", null);
    }

    // method to Dequeue from the old Queue (type as specified on the command line)
    private static long[] oldDequeue()
    throws InterruptedException
    {
               if (null != oldBlockingMultiArrayQueue) {
            return oldBlockingMultiArrayQueue.dequeue(0L);
        } else if (null != oldConcurrentMultiArrayQueue) {
            return oldConcurrentMultiArrayQueue.dequeue();
        }
        throw new AssertionError("no old Queue is set", null);
    }

    // method to recycle-Enqueue into the recycle Queue (type as specified on the command line)
    static void recycleEnqueue(long[] object)
    throws InterruptedException
    {
        boolean success = false;
               if (null != recycleBlockingMultiArrayQueue) {
            success = recycleBlockingMultiArrayQueue.enqueue(object, 0L);
        } else if (null != recycleConcurrentMultiArrayQueue) {
            success = recycleConcurrentMultiArrayQueue.enqueue(object);
        } else {
            throw new AssertionError("no recycle Queue is set", null);
        }
        if (! success) {
            throw new AssertionError("Enqueue into the recycle Queue failed", null);
        }
    }

    // method to recycle-Dequeue from the recycle Queue (type as specified on the command line)
    static long[] recycleDequeue()
    throws InterruptedException
    {
               if (null != recycleBlockingMultiArrayQueue) {
            return recycleBlockingMultiArrayQueue.dequeue(0L);
        } else if (null != recycleConcurrentMultiArrayQueue) {
            return recycleConcurrentMultiArrayQueue.dequeue();
        }
        throw new AssertionError("no recycle Queue is set", null);
    }

    //
    // main method
    //
    public static void main(String[] args)
    throws InterruptedException, IllegalArgumentException
    {
        int secondsToRun;
        int initialCapacity;
        int cntAllowedExtensions;
        int replaceMillis;

        // handle command-line args
        try
        {
            if (5 == args.length)
            {
                secondsToRun = Integer.parseInt(args[1]);

                initialCapacity = Integer.parseInt(args[2]);

                cntAllowedExtensions = Integer.parseInt(args[3]);

                replaceMillis = Integer.parseInt(args[4]);

                switch (args[0]) {
                case "BlockingMultiArrayQueue" :
                    blockingMultiArrayQueue = new BlockingMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions, false);
                    recycleBlockingMultiArrayQueue = new BlockingMultiArrayQueue<long[]>("recycleQueue", 30, -1, false);
                    break;
                case "ConcurrentMultiArrayQueue" :
                    concurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions);
                    recycleConcurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<long[]>("recycleQueue", 30, -1);
                    break;
                default :
                    throw new IllegalArgumentException("unknown type of Queue");
                }

            } else throw new IllegalArgumentException("args.length != 5");
        }
        catch (Exception e)
        {
            System.out.println("usage: SequenceTest2 <type of Queue> <secondsToRun> <initialCapacity> <cntAllowedExtensions> <replaceMillis>");
            throw e;
        }

        long emptyQueueHits = 0L;
        long oldEmptyQueueHits = 0L;
        long newEmptyQueueHits = 0L;
        long replacesBegun = 0L;
        long replacesFinished = 0L;
        long outSequence = 0L;
        long rememberedSeqFromNewQueue = 0L;

        State state = State.STATE_NORMAL;  // start in "normal state"

        long millis = System.currentTimeMillis();

        long stopReplaceMillis = millis + (secondsToRun * 1000);

        long lastReplaceMillis = millis;

        System.out.printf("------- testing %s -------%n", args[0]);

        // start the writer Thread
        Thread thread = new Thread(new SequenceTest2Thread(), "Thread_writer");
        thread.start();

        // we now continue as the reader Thread
        reader_loop:
        for (;;)
        {
            boolean tmpWriterHasStopped = writerHasStopped;  // volatile read before Dequeue

            long[] object;

            switch (state) {

            // normal state: Dequeue and check the sequence
            // and: start a Queue replacement once its time comes
            case STATE_NORMAL :

                object = dequeue();

                if (null != object)  // success
                {
                    if (outSequence != object[0])  // check sequence in Object against outSequence
                    {
                        throw new AssertionError(String.format("Sequence broken (1), expected %,d, got %,d", outSequence, object[0]), null);
                    }
                    outSequence ++;
                    recycleEnqueue(object);
                }
                else  // Queue was empty
                {
                    if (tmpWriterHasStopped) break reader_loop;
                    emptyQueueHits ++;
                    Thread.yield();
                }

                millis = System.currentTimeMillis();
                if (stopReplaceMillis < millis)  // replace only until stopReplaceMillis
                {
                    replacesHaveStopped = true;  // volatile write
                }
                if (! replacesHaveStopped)
                {
                    if ((lastReplaceMillis + replaceMillis) < millis)  // if the time has come to replace the Queue
                    {
                        oldBlockingMultiArrayQueue = null;  // this produces garbage (but it is just a test)
                        oldConcurrentMultiArrayQueue = null;  // this produces garbage (but it is just a test)

                        oldBlockingMultiArrayQueue = blockingMultiArrayQueue;
                        oldConcurrentMultiArrayQueue = concurrentMultiArrayQueue;

                        if (null != blockingMultiArrayQueue) {
                            blockingMultiArrayQueue = new BlockingMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions, false);
                        } else if (null != concurrentMultiArrayQueue) {
                            concurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions);
                        } else {
                            throw new AssertionError("no Queue is set", null);
                        }
                        replacesBegun ++;
                        lastReplaceMillis = millis;
                        state = State.STATE_REPLACE_WAIT_TRY_OLD;
                    }
                }
                break;

            // try Dequeue from the old Queue
            // if success: check the sequence
            // otherwise: alternate to STATE_REPLACE_WAIT_TRY_NEW
            case STATE_REPLACE_WAIT_TRY_OLD :

                object = oldDequeue();

                if (null != object)  // success
                {
                    if (outSequence != object[0])  // check sequence in Object against outSequence
                    {
                        throw new AssertionError(String.format("Sequence broken (2), expected %,d, got %,d", outSequence, object[0]), null);
                    }
                    outSequence ++;
                    recycleEnqueue(object);
                }
                else  // Queue was empty
                {
                    oldEmptyQueueHits ++;
                    state = State.STATE_REPLACE_WAIT_TRY_NEW;
                }
                break;

            // try (the first) Dequeue from the new Queue
            // if success: remember the sequence and switch to STATE_REPLACE_DRAIN_OLD_AND_FINISH
            // otherwise: alternate to STATE_REPLACE_WAIT_TRY_OLD
            case STATE_REPLACE_WAIT_TRY_NEW :

                object = dequeue();

                if (null != object)  // success
                {
                    rememberedSeqFromNewQueue = object[0];
                    recycleEnqueue(object);
                    state = State.STATE_REPLACE_DRAIN_OLD_AND_FINISH;
                }
                else  // Queue was empty
                {
                    if (tmpWriterHasStopped) break reader_loop;
                    newEmptyQueueHits ++;
                    state = State.STATE_REPLACE_WAIT_TRY_OLD;
                    Thread.yield();
                }
                break;

            // drain the old Queue
            // when fully drained: check the remembered sequence from the new Queue and finish by switching to STATE_NORMAL
            case STATE_REPLACE_DRAIN_OLD_AND_FINISH :

                object = oldDequeue();

                if (null != object)  // success
                {
                    if (outSequence != object[0])  // check sequence in Object against outSequence
                    {
                        throw new AssertionError(String.format("Sequence broken (3), expected %,d, got %,d", outSequence, object[0]), null);
                    }
                    outSequence ++;
                    recycleEnqueue(object);
                }
                else  // the old Queue is now fully drained
                {
                    oldEmptyQueueHits ++;
                    if (outSequence != rememberedSeqFromNewQueue)  // check rememberedSeqFromNewQueue against outSequence
                    {
                        throw new AssertionError(String.format("Sequence broken (4), expected %,d, got %,d", outSequence, rememberedSeqFromNewQueue), null);
                    }
                    outSequence ++;
                    replacesFinished ++;
                    state = State.STATE_NORMAL;
                }
                break;

            default :
                throw new AssertionError("unknown state", null);
            }
        }

        if (replacesBegun != replacesFinished)
        {
            throw new AssertionError(String.format("replacesFinished %,d not equal to replacesBegun %,d", replacesFinished, replacesBegun), null);
        }

        if (inSequence.get() != outSequence)
        {
            throw new AssertionError(String.format("outSequence %,d not equal to inSequence %,d", outSequence, inSequence.get()), null);
        }

        // Dequeue and count Objects in the recycle Queue
        long recycledObjects = 0L;
        for (; null != recycleDequeue() ;)
        {
            recycledObjects ++;
        }

        if (allocCounter.get() != recycledObjects)
        {
            throw new AssertionError(String.format("recycledObjects %,d not equal to allocCounter %,d", recycledObjects, allocCounter.get()), null);
        }

        System.out.printf("allocCounter:      %,15d%n", allocCounter.get());
        System.out.printf("recycledObjects:   %,15d%n", recycledObjects);
        System.out.printf("emptyQueueHits:    %,15d%n", emptyQueueHits);
        System.out.printf("oldEmptyQueueHits: %,15d%n", oldEmptyQueueHits);
        System.out.printf("newEmptyQueueHits: %,15d%n", newEmptyQueueHits);
        System.out.printf("replacesBegun:     %,15d%n", replacesBegun);
        System.out.printf("replacesFinished:  %,15d%n", replacesFinished);
        System.out.printf("inSequence:        %,15d%n", inSequence.get());
        System.out.printf("outSequence:       %,15d%n", outSequence);
        System.out.printf("SequenceTest2 of %s finished successfully.%n", args[0]);
    }
}
