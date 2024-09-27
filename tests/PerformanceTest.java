/*
MIT License
Copyright (c) 2024 Vít Procházka

This is a Performance test of the Multi-Array Queues in comparison with three Java built-in Queues.

Measured is the number of dequeue/enqueue pairs per second.

At the end there are checks for no messages lost or duplicated.

How to use: Download the Java files to a new directory, change to there and:

    javac -d . BlockingMultiArrayQueue.java ConcurrentMultiArrayQueue.java PerformanceTest.java
    java -classpath . com.github.MultiArrayQueue.PerformanceTest ArrayBlockingQueue 2 10
    java -classpath . com.github.MultiArrayQueue.PerformanceTest LinkedBlockingQueue 2 10
    java -classpath . com.github.MultiArrayQueue.PerformanceTest ConcurrentLinkedQueue 2 10
    java -classpath . com.github.MultiArrayQueue.PerformanceTest BlockingMultiArrayQueue 2 10
    java -classpath . com.github.MultiArrayQueue.PerformanceTest ConcurrentMultiArrayQueue 2 10

This will compile the Java files to subdirectory com/github/MultiArrayQueue and run the tests.

    command line args: PerformanceTest <type of Queue> <numberOfThreads> <preAllocatedObjects>
*/

package com.github.MultiArrayQueue;

import java.util.concurrent.atomic.AtomicLong;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

/*
 * The Object (quasi the usable message) whose references will be enqueued/dequeued
 */
class PerformanceTestMessage
{
    long sequenceNumber;  // used during testing
    long otherPayload1;  // dummy payload
    double otherPayload2;  // etc
}

/*
 * Runnable of the Threads
 */
class PerformanceTestThread implements Runnable
{
    long stopThreadsMillis;

    PerformanceTestThread(long stopThreadsMillis)
    {
        this.stopThreadsMillis = stopThreadsMillis;
    }

    @Override
    public void run()
    {
        try
        {
            PerformanceTestMessage object;
            long ownIterations = 0;

            for (; System.currentTimeMillis() < stopThreadsMillis ;)  // stop after stopThreadsMillis
            {
                // dequeue Object
                object = PerformanceTest.dequeue();

                // if Queue was empty, allocate a new Object
                if (null == object)
                {
                    object = new PerformanceTestMessage();
                    object.sequenceNumber = PerformanceTest.allocCounter.getAndIncrement();  // assign it a unique sequenceNumber
                    System.out.println(String.format("%s allocated [%d]", Thread.currentThread().getName(), object.sequenceNumber));
                }

                // enqueue Object
                if (! PerformanceTest.enqueue(object)) {
                    throw new AssertionError("Queue is full", null);
                }

                // Object is now enqueued, forget its reference
                object = null;

                // increment counters
                PerformanceTest.perfCounter.getAndIncrement();
                ownIterations ++;
            }

            System.out.println(String.format("%s stopped after no of iterations: %8d", Thread.currentThread().getName(), ownIterations));
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
public class PerformanceTest
{
    private static ArrayBlockingQueue<PerformanceTestMessage>        arrayBlockingQueue;
    private static LinkedBlockingQueue<PerformanceTestMessage>       linkedBlockingQueue;
    private static ConcurrentLinkedQueue<PerformanceTestMessage>     concurrentLinkedQueue;
    private static BlockingMultiArrayQueue<PerformanceTestMessage>   blockingMultiArrayQueue;
    private static ConcurrentMultiArrayQueue<PerformanceTestMessage> concurrentMultiArrayQueue;

    static AtomicLong allocCounter = new AtomicLong(0L);  // atomic counter of allocated (by any Thread) Objects
    static AtomicLong perfCounter = new AtomicLong(0L);  // atomic counter of dequeue/enqueue pairs (by all Threads)

    // method to enqueue into the Queue specified on the command line
    static boolean enqueue(PerformanceTestMessage object)
    throws InterruptedException
    {
               if (null != arrayBlockingQueue) {
            return arrayBlockingQueue.offer(object);
        } else if (null != linkedBlockingQueue) {
            return linkedBlockingQueue.offer(object);
        } else if (null != concurrentLinkedQueue) {
            return concurrentLinkedQueue.offer(object);
        } else if (null != blockingMultiArrayQueue) {
            return blockingMultiArrayQueue.enqueue(object, 0);
        } else if (null != concurrentMultiArrayQueue) {
            return concurrentMultiArrayQueue.enqueue(object);
        }
        throw new AssertionError("no Queue is set", null);
    }

    // method to dequeue from the Queue specified on the command line
    static PerformanceTestMessage dequeue()
    throws InterruptedException
    {
               if (null != arrayBlockingQueue) {
            return arrayBlockingQueue.poll();
        } else if (null != linkedBlockingQueue) {
            return linkedBlockingQueue.poll();
        } else if (null != concurrentLinkedQueue) {
            return concurrentLinkedQueue.poll();
        } else if (null != blockingMultiArrayQueue) {
            return blockingMultiArrayQueue.dequeue(0);
        } else if (null != concurrentMultiArrayQueue) {
            return concurrentMultiArrayQueue.dequeue();
        }
        throw new AssertionError("no Queue is set", null);
    }

    // main method
    public static void main(String[] args)
    throws InterruptedException, IllegalArgumentException
    {
        int numberOfThreads = 0;
        int preAllocatedObjects = 0;

        // handle command-line args
        try
        {
            if (3 == args.length)
            {
                numberOfThreads = Integer.parseInt(args[1]);

                preAllocatedObjects = Integer.parseInt(args[2]);

                int boundedQueueCapacity = (preAllocatedObjects < numberOfThreads) ? numberOfThreads : preAllocatedObjects;

                switch (args[0]) {
                case "ArrayBlockingQueue" :
                    arrayBlockingQueue = new ArrayBlockingQueue<PerformanceTestMessage>(boundedQueueCapacity, false);
                    break;
                case "LinkedBlockingQueue" :
                    linkedBlockingQueue = new LinkedBlockingQueue<PerformanceTestMessage>();
                    break;
                case "ConcurrentLinkedQueue" :
                    concurrentLinkedQueue = new ConcurrentLinkedQueue<PerformanceTestMessage>();
                    break;
                case "BlockingMultiArrayQueue" :
                    blockingMultiArrayQueue = new BlockingMultiArrayQueue<PerformanceTestMessage>("testQueue", 0, -1, false);
                    break;
                case "ConcurrentMultiArrayQueue" :
                    concurrentMultiArrayQueue = new ConcurrentMultiArrayQueue<PerformanceTestMessage>("testQueue", 0, -1);
                    break;
                default :
                    throw new IllegalArgumentException("unknown type of Queue");
                }

            } else throw new IllegalArgumentException("args.length != 3");
        }
        catch (Exception e)
        {
            System.out.println("usage: PerformanceTest <type of Queue> <numberOfThreads> <preAllocatedObjects>");
            throw e;
        }

        // set times of the phases of the test
        long initMillis = System.currentTimeMillis();
        long beginMeasureMillis = initMillis + 2000;  // start measuring (delayed to reach steady state)
        long endMeasureMillis   = initMillis + 3000;  // stop measuring (after 1 second)
        long stopThreadsMillis  = initMillis + 4000;  // the time at which all Threads should stop
        long postStopMillis     = initMillis + 5000;  // start checking after all Threads have stopped for sure

        System.out.println(String.format("------- testing %s -------", args[0]));

        // pre-allocate Objects
        if (0 < preAllocatedObjects)
        {
            System.out.print("pre-allocating:");
            for (int i = 0; i < preAllocatedObjects; i ++)
            {
                PerformanceTestMessage object = new PerformanceTestMessage();
                object.sequenceNumber = allocCounter.getAndIncrement();  // assign it a unique sequenceNumber
                System.out.print(String.format(" [%d]", object.sequenceNumber));
                if (! enqueue(object)) {
                    throw new AssertionError("Queue is full", null);
                }
            }
            System.out.println();
        }

        // start the Threads
        for (int i = 0; i < numberOfThreads; i ++)
        {
            Thread thread = new Thread(new PerformanceTestThread(stopThreadsMillis), String.format("Thread_%02d", i));
            thread.start();
        }
        System.out.println(String.format("%d Threads started, millis: %d", numberOfThreads, System.currentTimeMillis() - initMillis));

        // wait until beginMeasureMillis and then capture the value of perfCounter
        for (; System.currentTimeMillis() < beginMeasureMillis ;) {
            Thread.sleep(1);
        }

        System.out.println(String.format("begin measure time, millis: %d", System.currentTimeMillis() - initMillis));
        long perfCounterStart = perfCounter.get();

        // wait until endMeasureMillis and then capture and print the performance result
        for (; System.currentTimeMillis() < endMeasureMillis;) {
            Thread.sleep(1);
        }

        System.out.println(String.format("end measure time, millis: %d", System.currentTimeMillis() - initMillis));
        System.out.println(String.format("dequeue/enqueue pairs: %,d", perfCounter.get() - perfCounterStart));

        // wait until postStopMillis
        for (; System.currentTimeMillis() < postStopMillis ;) {
            Thread.sleep(1);
        }

        // check
        //
        // due to the non-deterministic scheduling and preempting of the PerformanceTestThreads,
        // the Objects in the Queue will not be in any meaningful order,
        // but no Object may be missing and no Object reference may be duplicated !

        System.out.println(String.format("start checking, millis: %d", System.currentTimeMillis() - initMillis));

        System.out.println(String.format("number of Objects allocated: %d", allocCounter.get()));
        boolean[] checkArray = new boolean[(int) allocCounter.get()];

        System.out.print("remained in Queue:");
        PerformanceTestMessage object;
        for (; null != (object = dequeue()) ;)
        {
            if (checkArray[(int) object.sequenceNumber]) {
                throw new AssertionError(String.format("Object [%d] is duplicated", object.sequenceNumber), null);
            } else {
                checkArray[(int) object.sequenceNumber] = true;
                System.out.print(String.format(" [%d]", object.sequenceNumber));
            }
        }
        System.out.println();
        for (int i = 0; i < checkArray.length; i ++) {
            if (! checkArray[i]) throw new AssertionError(String.format("Object [%d] is missing", i), null);
        }
        System.out.println("checking finished ok");
    }
}

