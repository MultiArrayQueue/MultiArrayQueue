/*
MIT License
Copyright (c) 2024 Vít Procházka

This is a single-threaded test that the Multi-Array Queues do not run out-of-sequence while repeatedly
enqueueing and dequeueing random numbers of Objects.

Empty Queues are randomly replaced by new Queues, to also test the extension phases.

The enqueued/dequeued Objects are long[1] (long arrays of size 1).

How to use: Download the Java files to a new directory, change to there and:

    javac -d . BlockingMultiArrayQueue.java ConcurrentMultiArrayQueue.java SequenceTest.java
    java -classpath . com.github.MultiArrayQueue.SequenceTest BlockingMultiArrayQueue 5 0 3
    java -classpath . com.github.MultiArrayQueue.SequenceTest ConcurrentMultiArrayQueue 5 0 3

This will compile the Java files to subdirectory com/github/MultiArrayQueue and run the tests.

    command line args: SequenceTest <type of Queue> <secondsToRun> <initialCapacity> <cntAllowedExtensions>
*/

package com.github.MultiArrayQueue;

import java.util.Random;

/**
 * Main class
 */
public class SequenceTest
{
    // main method
    public static void main(String[] args)
    throws InterruptedException, IllegalArgumentException
    {
        BlockingMultiArrayQueue<long[]> blockingMultiArrayQueue = null;
        ConcurrentMultiArrayQueue<long[]> concurrentMultiArrayQueue = null;
        long maximumCapacity;

        int secondsToRun;
        int initialCapacity;
        int cntAllowedExtensions;

        // handle command-line args
        try
        {
            if (4 == args.length)
            {
                secondsToRun = Integer.parseInt(args[1]);

                initialCapacity = Integer.parseInt(args[2]);

                cntAllowedExtensions = Integer.parseInt(args[3]);

                switch (args[0]) {
                case "BlockingMultiArrayQueue" :
                    blockingMultiArrayQueue = new BlockingMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions, false);
                    maximumCapacity = blockingMultiArrayQueue.getMaximumCapacity();
                    break;
                case "ConcurrentMultiArrayQueue" :
                    concurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions);
                    maximumCapacity = concurrentMultiArrayQueue.getMaximumCapacity();
                    break;
                default :
                    throw new IllegalArgumentException("unknown type of Queue");
                }

            } else throw new IllegalArgumentException("args.length != 4");
        }
        catch (Exception e)
        {
            System.out.println("usage: SequenceTest <type of Queue> <secondsToRun> <initialCapacity> <cntAllowedExtensions>");
            throw e;
        }

        if (0x0000_0000_7FFF_FFF0L <= maximumCapacity)
        {
            throw new IllegalArgumentException(String.format("maximumCapacity is too high (pool allocation issue): %,d", maximumCapacity));
        }
        int poolSize = (int)(1 + maximumCapacity);  // one extra Object in the pool to be able to test full Queue hits
        long[][] pool = new long[poolSize][];  // pool for borrowing/returning the Objects to avoid massive production of garbage
        for (int j = 0; j < poolSize; j ++)
        {
            pool[j] = new long[1];  // pre-fill the pool with Objects
        }

        long fullQueueHits = 0L;
        long emptyQueueHits = 0L;
        long queueReplaces = 0L;
        long inSequence = 0L;
        long outSequence = 0L;

        Random random = new Random();

        long stopMillis = System.currentTimeMillis() + (secondsToRun * 1000);

        System.out.printf("------- testing %s -------%n", args[0]);

        for (; System.currentTimeMillis() < stopMillis ;)
        {
            // enqueueing random number of Objects
            int enqueues  = random.nextInt(20);
            for (int e = 0; e < enqueues; e ++)
            {
                int poolIndex = (int)(inSequence % poolSize);
                long[] object = pool[poolIndex];  // borrow Object from the pool
                if (null == object) throw new AssertionError(String.format("pool contains null at inSequence %,d", inSequence), null);

                object[0] = inSequence;  // write inSequence into the Object

                boolean success = false;
                if (null != blockingMultiArrayQueue) success = blockingMultiArrayQueue.enqueue(object, 0L);
                if (null != concurrentMultiArrayQueue) success = concurrentMultiArrayQueue.enqueue(object);

                if (success)
                {
                    pool[poolIndex] = null;
                    inSequence ++;
                }
                else  // Queue was full
                {
                    if (maximumCapacity != (inSequence - outSequence))
                    {
                        throw new AssertionError(String.format("Queue is full at wrong size %,d, expected: %,d", (inSequence - outSequence), maximumCapacity), null);
                    }
                    fullQueueHits ++;
                    break;
                }
            }

            // dequeueing random number of Objects
            int dequeues = random.nextInt(20);
            for (int d = 0; d < dequeues; d ++)
            {
                long[] object = null;
                if (null != blockingMultiArrayQueue) object = blockingMultiArrayQueue.dequeue(0L);
                if (null != concurrentMultiArrayQueue) object = concurrentMultiArrayQueue.dequeue();

                if (null != object)  // success
                {
                    if (outSequence != object[0])  // check sequence in Object against outSequence
                    {
                        throw new AssertionError(String.format("Sequence broken, expected %,d, got %,d", outSequence, object[0]), null);
                    }

                    int poolIndex = (int)(outSequence % poolSize);
                    if (null != pool[poolIndex]) throw new AssertionError(String.format("pool contains Object at outSequence %,d", outSequence), null);
                    pool[poolIndex] = object;  // return Object to the pool

                    outSequence ++;
                }
                else  // Queue was empty
                {
                    if (0L != (inSequence - outSequence))
                    {
                        throw new AssertionError(String.format("Queue is empty at wrong size %,d, expected: 0", (inSequence - outSequence)), null);
                    }
                    emptyQueueHits ++;

                    // replace the old (empty) Queue in cca 10 percents of empty hits
                    if (0 == random.nextInt(10))
                    {
                        if (null != blockingMultiArrayQueue)
                        {
                            blockingMultiArrayQueue = null;  // this produces garbage (but it is just a test)
                            blockingMultiArrayQueue = new BlockingMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions, false);
                        }
                        if (null != concurrentMultiArrayQueue)
                        {
                            concurrentMultiArrayQueue = null;  // this produces garbage (but it is just a test)
                            concurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<long[]>("testQueue", initialCapacity, cntAllowedExtensions);
                        }
                        queueReplaces ++;
                    }
                    break;
                }
            }
        }

        System.out.printf("fullQueueHits:  %,15d%n", fullQueueHits);
        System.out.printf("emptyQueueHits: %,15d%n", emptyQueueHits);
        System.out.printf("queueReplaces:  %,15d%n", queueReplaces);
        System.out.printf("inSequence:     %,15d%n", inSequence);
        System.out.printf("outSequence:    %,15d%n", outSequence);
        System.out.printf("SequenceTest of %s finished successfully.%n", args[0]);
    }
}
