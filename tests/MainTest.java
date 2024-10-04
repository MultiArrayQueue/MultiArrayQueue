/*
MIT License
Copyright (c) 2024 Vít Procházka

This is the main test of the Multi-Array Queues.

The test creates a ring-buffer of 1024 Queues and puts <numberOfObjects> Objects into the first Queue
and marks this Queue as "current" via an AtomicLong variable.

It then creates <numberOfThreads> "worker" threads that in each iteration do:

 1. read the AtomicLong variable (position of the "current" Queue in the ring-buffer)
 2. try dequeue from the third/second/first Queue before the "current" Queue or the "current" Queue (random choice)
 3. enqueue into the "current" Queue

The test then constantly switches the AtomicLong variable forward, effectively switching the "current" Queue
to new and new Queues. Before each switch, it checks if the next "current" Queue is empty and if yes,
it replaces it by a new Queue (this is to test the extension phases of the Queues as much as possible).

At the end of the test (after all "worker" threads have terminated), the test goes over all Queues, dequeues
all Objects from them and checks if none is missing or duplicated (the order of the Objects will of course be random).

The enqueued/dequeued Objects are long[1] (long arrays of size 1).

How to use: Download the Java files to a new directory, change to there and:

    javac -d . BlockingMultiArrayQueue.java ConcurrentMultiArrayQueue.java MainTest.java
    java -classpath . com.github.MultiArrayQueue.MainTest BlockingMultiArrayQueue 20 4 200 5
    java -classpath . com.github.MultiArrayQueue.MainTest ConcurrentMultiArrayQueue 20 4 200 5

This will compile the Java files to subdirectory com/github/MultiArrayQueue and run the tests.

    command line args: MainTest <type of Queue> <secondsToRun> <numberOfThreads> <numberOfObjects> <switchMillis>
*/

package com.github.MultiArrayQueue;

import java.util.Random;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Runnable of the Threads
 */
class MainTestThread implements Runnable
{
    long stopThreadsMillis;

    MainTestThread(long stopThreadsMillis)
    {
        this.stopThreadsMillis = stopThreadsMillis;
    }

    @Override
    public void run()
    {
        try
        {
            long ownIterations = 0L;
            long ownDequeueEnqueuePairs = 0L;

            Random random = new Random();
            long[] object = null;

            for (; System.currentTimeMillis() < stopThreadsMillis ;)  // stop after stopThreadsMillis
            {
                // try dequeue from the third/second/first Queue before the "current" Queue or the "current" Queue (random choice)
                long qIndex = MainTest.queueIndex.get();
                int randomOffset = random.nextInt(4);
                object = MainTest.dequeue(qIndex - randomOffset);

                // if successfully dequeued: enqueue into the "current" Queue
                if (null != object)
                {
                    if (! MainTest.enqueue(qIndex, object)) {
                        throw new AssertionError(String.format("Queue %s is full", MainTest.queueGetName(qIndex)), null);
                    }

                    // Object is now enqueued, forget its reference
                    object = null;

                    ownDequeueEnqueuePairs ++;
                }

                ownIterations ++;
            }

            System.out.printf("%s stopped after %,8d iterations (%,8d dequeue/enqueue pairs)%n",
                Thread.currentThread().getName(), ownIterations, ownDequeueEnqueuePairs);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}

/*
 * Main class
 */
public class MainTest
{
    private static ArrayList<BlockingMultiArrayQueue<long[]>>   blockingMultiArrayQueueRingBuffer;
    private static ArrayList<ConcurrentMultiArrayQueue<long[]>> concurrentMultiArrayQueueRingBuffer;

    static AtomicLong queueIndex = new AtomicLong(1024L);  // index of the "current" Queue in the ring-buffer

    // test if the Queue specified on the command line is null (i.e. was not yet created) in the ring-buffer
    static boolean queueIsNull(long qIndex)
    {
        if (null != blockingMultiArrayQueueRingBuffer) {
            return (null == blockingMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)));
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            return (null == concurrentMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)));
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // create (or replace) the Queue specified on the command line in the ring-buffer
    static void createOrReplaceQueue(long qIndex)
    {
        String name = String.format("testQueue_%04d", (qIndex & 0x3FFL));
        if (null != blockingMultiArrayQueueRingBuffer) {
            blockingMultiArrayQueueRingBuffer.set(((int)(qIndex & 0x3FFL)), null);  // this produces garbage (but it is just a test)
            blockingMultiArrayQueueRingBuffer.set(((int)(qIndex & 0x3FFL)), new BlockingMultiArrayQueue<long[]>(name, 0, -1, false));
            return;
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            concurrentMultiArrayQueueRingBuffer.set(((int)(qIndex & 0x3FFL)), null);  // this produces garbage (but it is just a test)
            concurrentMultiArrayQueueRingBuffer.set(((int)(qIndex & 0x3FFL)), new ConcurrentMultiArrayQueue<long[]>(name, 0, -1));
            return;
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // getName method of the Queue specified on the command line
    static String queueGetName(long qIndex)
    {
        if (null != blockingMultiArrayQueueRingBuffer) {
            return blockingMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).getName();
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            return concurrentMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).getName();
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // method to enqueue into the Queue specified on the command line
    static boolean enqueue(long qIndex, long[] object)
    throws InterruptedException
    {
        if (null != blockingMultiArrayQueueRingBuffer) {
            return blockingMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).enqueue(object, 0);
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            return concurrentMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).enqueue(object);
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // method to dequeue from the Queue specified on the command line
    static long[] dequeue(long qIndex)
    throws InterruptedException
    {
        if (null != blockingMultiArrayQueueRingBuffer) {
            return blockingMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).dequeue(0);
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            return concurrentMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).dequeue();
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // isEmpty method of the Queue specified on the command line
    static boolean queueIsEmpty(long qIndex)
    throws InterruptedException
    {
        if (null != blockingMultiArrayQueueRingBuffer) {
            return blockingMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).isEmpty();
        } else if (null != concurrentMultiArrayQueueRingBuffer) {
            return concurrentMultiArrayQueueRingBuffer.get((int)(qIndex & 0x3FFL)).isEmpty();
        }
        throw new AssertionError("no ring-buffer of Queues is set", null);
    }

    // main method
    public static void main(String[] args)
    throws InterruptedException, IllegalArgumentException
    {
        int secondsToRun = 0;
        int numberOfThreads = 0;
        int numberOfObjects = 0;
        int switchMillis = 0;

        // handle command-line args
        try
        {
            if (5 == args.length)
            {
                secondsToRun = Integer.parseInt(args[1]);

                numberOfThreads = Integer.parseInt(args[2]);

                numberOfObjects = Integer.parseInt(args[3]);

                switchMillis = Integer.parseInt(args[4]);
                if (switchMillis < 1) throw new IllegalArgumentException("<switchMillis> is less than 1 ms");

                switch (args[0]) {
                case "BlockingMultiArrayQueue" :
                    blockingMultiArrayQueueRingBuffer = new ArrayList<BlockingMultiArrayQueue<long[]>>(1024);
                    for (int i = 0; i < 1024; i ++) blockingMultiArrayQueueRingBuffer.add(null);
                    break;
                case "ConcurrentMultiArrayQueue" :
                    concurrentMultiArrayQueueRingBuffer = new ArrayList<ConcurrentMultiArrayQueue<long[]>>(1024);
                    for (int i = 0; i < 1024; i ++) concurrentMultiArrayQueueRingBuffer.add(null);
                    break;
                default :
                    throw new IllegalArgumentException("unknown type of Queue");
                }

            } else throw new IllegalArgumentException("args.length != 5");
        }
        catch (Exception e)
        {
            System.out.println("usage: MainTest <type of Queue> <secondsToRun> <numberOfThreads> <numberOfObjects> <switchMillis>");
            throw e;
        }

        // set times of the phases of the test
        long initMillis = System.currentTimeMillis();
        long stopThreadsMillis = initMillis + (secondsToRun * 1000);  // the time at which all "worker" threads should stop
        long postStopMillis    = stopThreadsMillis + 1000;  // start checking after all "worker" threads have stopped for sure

        System.out.printf("------- testing %s -------%n", args[0]);

        long qIndex = queueIndex.get();

        // create the initial set of Queues
        createOrReplaceQueue(qIndex - 3);
        createOrReplaceQueue(qIndex - 2);
        createOrReplaceQueue(qIndex - 1);
        createOrReplaceQueue(qIndex);

        // allocate and enqueue the Objects
        System.out.printf("allocating and enqueueing into Queue %s:", queueGetName(qIndex));
        for (int i = 0; i < numberOfObjects; i ++)
        {
            long[] object = new long[1];
            object[0] = (long) i;  // assign it a unique sequenceNumber
            System.out.printf(" [%d]", object[0]);
            if (! enqueue(qIndex, object)) {
                throw new AssertionError(String.format("Queue %s is full", queueGetName(qIndex)), null);
            }
        }
        System.out.println();

        // start the "worker" threads
        for (int i = 0; i < numberOfThreads; i ++)
        {
            Thread thread = new Thread(new MainTestThread(stopThreadsMillis), String.format("Thread_%04d", i));
            thread.start();
        }
        System.out.printf("%d Threads started, millis: %d%n", numberOfThreads, System.currentTimeMillis() - initMillis);

        // continuously switch the "current" Queue to new Queues
        //
        // we strongly assume that no "worker" thread is still active on 1 + queueIndex anymore,
        // because this would mean that such thread has been preempted for at least 1020 x 1 ms = cca 1 second
        // (1020 ms = (size of the ring-buffer minus 4) x smallest possible value of <switchMillis>)

        long queueCreates = 0L;
        long queueReplaces = 0L;
        long queueKeeps = 0L;

        for (; System.currentTimeMillis() < stopThreadsMillis ;)
        {
            long qIndexNext = 1 + queueIndex.get();

            if (queueIsNull(qIndexNext))
            {
                createOrReplaceQueue(qIndexNext);
                queueCreates ++;
            }
            else if (queueIsEmpty(qIndexNext))
            {
                createOrReplaceQueue(qIndexNext);
                queueReplaces ++;
            }
            else  // Queue is not empty: possible: the "worker" threads did not manage to empty it (in the previous pass over the ring-buffer)
            {
                queueKeeps ++;
            }
            queueIndex.getAndIncrement();  // actually switch queueIndex forward (this will be visible to the "worker" threads)
            Thread.sleep((long) switchMillis);
        }

        System.out.printf("stopped switching, millis: %d%n", System.currentTimeMillis() - initMillis);

        // wait for all "worker" threads to have stopped for sure
        for (; System.currentTimeMillis() < postStopMillis ;)
        {
            Thread.sleep(1);
        }

        // check
        // no Object may be missing and no Object reference may be duplicated !

        System.out.printf("start checking, millis: %d%n", System.currentTimeMillis() - initMillis);

        boolean[] checkArray = new boolean[numberOfObjects];

        for (long i = 0L; i < 1024L; i ++)
        {
            if ((! queueIsNull(i)) && (! queueIsEmpty(i)))
            {
                System.out.printf("remained in Queue %s:", queueGetName(i));
                long[] object;
                for (; null != (object = dequeue(i)) ;)
                {
                    if (checkArray[(int) object[0]]) {
                        throw new AssertionError(String.format("Object [%d] is duplicated", object[0]), null);
                    } else {
                        checkArray[(int) object[0]] = true;
                        System.out.printf(" [%d]", object[0]);
                    }
                }
                System.out.println();
            }
        }
        for (int i = 0; i < checkArray.length; i ++) {
            if (! checkArray[i]) throw new AssertionError(String.format("Object [%d] is missing", i), null);
        }
        System.out.printf("queueCreates:   %,15d%n", queueCreates);
        System.out.printf("queueReplaces:  %,15d%n", queueReplaces);
        System.out.printf("queueKeeps:     %,15d%n", queueKeeps);
        System.out.printf("MainTest of %s finished successfully.%n", args[0]);
    }
}

