/***********************************************************
 * MIT License
 * Copyright (c) 2024 Vít Procházka
 *
 * Promela model of the BlockingMultiArrayQueue for Spin.
 *
 * An exhaustive verification with more than 14 writers
 * plus 14 readers reaches feasibility limits and
 * requires bitstate hashing (-DBITSTATE).
 *
 * Control the number of processes by editing
 * WRITERS and READERS below.
 *
 * Recommend to always set a memory limit, e.g.
 *
 *    spin -a BlockingMultiArrayQueue.pml
 *    cc -O2 -DMEMLIM=256 -o pan pan.c
 *    ./pan
 *
 * A random simulation with Spin, on the contrary,
 * can have a much higher number of processes:
 *
 *    spin BlockingMultiArrayQueue.pml
 *
 * The Queue is tested in empty state with FIRST_ARRAY_SIZE == 1
 * which is where the structure is "most dense".
 *
 * However, an optional pre-fill scenario can be specified.
 ***********************************************************/

/*********************************************
 verification data
 *********************************************/

// Hint: For construction of the pre-fill scenario it is helpful to use the Interactive Simulator:
// https://MultiArrayQueue.github.io/Simulator_MultiArrayQueue.html

#define PREFILL_STEPS 5

int prefill[5] = { 1, 0, 1, 1, 1 }  // 1 = enqueue, 0 = dequeue

#define WRITERS 14
#define READERS 14

int cntEnqueued = 0;
int cntEnqueueFull = 0;

int cntDequeued = 0;
int cntDequeueEmpty = 0;

/*********************************************
 private data of the BlockingMultiArrayQueue
 *********************************************/

#define FIRST_ARRAY_SIZE 1
#define CNT_ALLOWED_EXTENSIONS 2

// MAX_ARRAY_SIZE = FIRST_ARRAY_SIZE * (2 ^ CNT_ALLOWED_EXTENSIONS)
#define MAX_ARRAY_SIZE 4

// MAXIMUM_CAPACITY = SUM( SIZES OF ALL ARRAYS ) - 1
#define MAXIMUM_CAPACITY (1+2+4-1)

typedef array {
    int element[MAX_ARRAY_SIZE];  // under-utilized except of the last array
}

array rings[1 + CNT_ALLOWED_EXTENSIONS];

int ringsMaxIndex = 0;

typedef diversion {
    int rix = 0;
    int ix = 0;
}

diversion diversions[1 + CNT_ALLOWED_EXTENSIONS];  // plus one to avoid an error when testing with CNT_ALLOWED_EXTENSIONS == 0

int  writerPositionRix = 0;
int  writerPositionIx = 0;

int  readerPositionRix = 0;
int  readerPositionIx = 0;

/*********************************************
 enqueue process
 *********************************************/
proctype enqueue()
{
    int  writerRix;  // writer prospective
    int  writerIx;
    int  readerRix;  // reader
    int  readerIx;
    int  rixMax;
    bool isQueueExtensionPossible;
    bool extendQueue;
    bool queueIsFull;
    int  rixMaxNew;
    int  tmpRix;

    d_step  // the whole process is one big d_step due to the lock
    {
        writerRix   = writerPositionRix;  // read the writer position
        writerIx    = writerPositionIx;
        assert(writerIx < (FIRST_ARRAY_SIZE << writerRix));

        readerRix   = readerPositionRix;  // read the reader position
        readerIx    = readerPositionIx;
        assert(readerIx < (FIRST_ARRAY_SIZE << readerRix));

        rixMax = ringsMaxIndex;  // read ringsMaxIndex

        isQueueExtensionPossible = (rixMax < CNT_ALLOWED_EXTENSIONS);  // if there is room yet for the extension
        extendQueue = false;
        queueIsFull = false;

        writerIx ++;  // prospective move forward

        // if the prospective move goes "beyond" the end of rings[writerRix]
        if
        :: ((FIRST_ARRAY_SIZE << writerRix) == writerIx) ->
        {
            if
            :: (0 == writerRix) ->  // if in rings[0]
            {
                writerRix = 0;  // move to rings[0][0]
                writerIx  = 0;
                // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
            }
            :: else ->  // i.e. we are in a "higher" rings[N]
            {
                tmpRix = writerRix;
                writerRix = diversions[tmpRix - 1].rix;  // follow diversion[N-1] back
                writerIx  = diversions[tmpRix - 1].ix;

                // if the prospective move has hit the reader (that is in the previous round) "from behind"
                if
                :: ((readerRix == writerRix) && (readerIx == writerIx)) ->
                {
                    if
                    :: (isQueueExtensionPossible) ->
                    {
                        // context: the writer that preceded us (the one that successfully moved to the last position
                        // in the array (i.e. to the position from which we start)) made a forward-looking check
                        // to prevent the next writer from hitting the reader on the return path of a diversion
                        // and has not seen the reader there (otherwise it would have created a new diversion and gone to it)
                        //
                        // so now: as the reader cannot move back, it is impossible that we hit him, but better check ...

                        assert(false);
                    }
                    :: else ->
                    {
                        queueIsFull = true;
                    }
                    fi
                }
                :: else;
                fi
                goto go_forward_done;  // the prospective move forward is done, we are on the return path of a diversion
            }
            fi
        }
        :: else;
        fi

        // if the prospective move reached (an entry side of) a diversion: follow it - to the beginning of respective rings[rix]
        // (another diversion may sit there, so then continue following)
        //
        // a diversion that leads to an array of Objects always precedes (in the diversions array) any diversions
        // that lead from that array of Objects, so one bottom-up pass through the diversions array
        // that starts at the diversion to 1 + writerRix suffices (i.e. a short linear search)

        for (tmpRix : (1 + writerRix) .. rixMax)
        {
            if
            :: ((diversions[tmpRix - 1].rix == writerRix)
             && (diversions[tmpRix - 1].ix  == writerIx)) ->
            {
                writerRix = tmpRix;  // move to the first element of the array of Objects the diversion leads to
                writerIx  = 0;
            }
            :: else;
            fi
        }

        // if the prospective move has hit the reader (that is in the previous round) "from behind"
        if
        :: ((readerRix == writerRix) && (readerIx == writerIx)) ->
        {
            if
            :: (isQueueExtensionPossible) ->
            {
                extendQueue = true;
            }
            :: else ->
            {
                queueIsFull = true;
            }
            fi
        }
        :: else ->
        {
            // the forward-looking check to prevent the next writer from hitting the reader "from behind"
            // on the return path of a diversion (see Paper for explanation)

            int testNextWriterRix = writerRix;
            int testNextWriterIx  = writerIx;

            do
            :: ((0 != testNextWriterRix) && ((FIRST_ARRAY_SIZE << testNextWriterRix) == (1 + testNextWriterIx))) ->
            {
                tmpRix = testNextWriterRix;
                testNextWriterRix = diversions[tmpRix - 1].rix;  // follow the diversion back
                testNextWriterIx  = diversions[tmpRix - 1].ix;
                if
                :: ((readerRix == testNextWriterRix) && (readerIx == testNextWriterIx)) ->  // if we would hit the reader
                {
                    if
                    :: (isQueueExtensionPossible) ->
                    {
                        extendQueue = true;
                    }
                    :: else;
                    fi
                    break;
                }
                :: else;
                fi
            }
            :: else -> break;
            od
        }
        fi

go_forward_done :  // prospective move forward is now done

        // preparations are done, start the actual work
        if
        :: (queueIsFull) ->
        {
            cntEnqueueFull ++;
            printf("PID %d found the Queue full on enqueue\n", _pid);
            assert(MAXIMUM_CAPACITY == (cntEnqueued - cntDequeued));
        }
        :: (extendQueue) ->
        {
            rixMaxNew = 1 + rixMax;

            assert(rixMax == ringsMaxIndex);
            assert(rixMaxNew <= CNT_ALLOWED_EXTENSIONS);
            assert(0 == rings[rixMaxNew].element[0]);

            // impossible for writerPos to be already in the diversions array, but better check ...
            for (tmpRix : 1 .. rixMax)
            {
                if
                :: ((diversions[tmpRix - 1].rix == writerRix)
                 && (diversions[tmpRix - 1].ix  == writerIx)) -> assert(false);
                :: else;
                fi
            }

            // increment cntEnqueued and enqueue it
            cntEnqueued ++;
            rings[rixMaxNew].element[0] = cntEnqueued;  // enqueue into the first array element of the new array
            printf("PID %d extended Queue and enqueued %d in rings[%d][%d]\n", _pid, cntEnqueued, rixMaxNew, 0);

            diversions[rixMaxNew - 1].rix = writerRix;  // the new diversion = the prospective writer position
            diversions[rixMaxNew - 1].ix  = writerIx;

            ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex

            writerPositionRix = rixMaxNew;  // new writer position = first array element of the new array
            writerPositionIx  = 0;
        }
        :: else ->  // no extendQueue
        {
            writerPositionRix = writerRix;  // new writer position = prospective writer position
            writerPositionIx  = writerIx;

            // increment cntEnqueued and enqueue it
            cntEnqueued ++;
            int valueBefore = rings[writerRix].element[writerIx];
            rings[writerRix].element[writerIx] = cntEnqueued;
            printf("PID %d enqueued %d in rings[%d][%d]\n", _pid, cntEnqueued, writerRix, writerIx);
            assert(0 == valueBefore);
        }
        fi
    }
}

/*********************************************
 dequeue process
 *********************************************/
proctype dequeue()
{
    int  readerRix;  // reader prospective
    int  readerIx;
    int  writerRix;  // writer
    int  writerIx;
    int  rixMax;
    int  tmpRix;

    d_step  // the whole process is one big d_step due to the lock
    {
        readerRix   = readerPositionRix;  // read the reader position
        readerIx    = readerPositionIx;
        assert(readerIx < (FIRST_ARRAY_SIZE << readerRix));

        writerRix   = writerPositionRix;  // read the writer position
        writerIx    = writerPositionIx;
        assert(writerIx < (FIRST_ARRAY_SIZE << writerRix));

        // if the reader stands on the writer: the Queue is empty
        if
        :: ((writerRix == readerRix) && (writerIx == readerIx)) ->
        {
            cntDequeueEmpty ++;
            printf("PID %d found the Queue empty on dequeue\n", _pid);
            assert(0 == (cntEnqueued - cntDequeued));
            goto dequeue_done;
        }
        :: else;
        fi

        rixMax = ringsMaxIndex;  // read ringsMaxIndex

        readerIx ++;  // prospective move forward

        // if the prospective move goes "beyond" the end of rings[readerRix]
        if
        :: ((FIRST_ARRAY_SIZE << readerRix) == readerIx) ->
        {
            if
            :: (0 == readerRix) ->  // if in rings[0]
            {
                readerRix = 0;  // move to rings[0][0]
                readerIx  = 0;
                // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
            }
            :: else ->  // i.e. we are in a "higher" rings[N]
            {
                tmpRix = readerRix;
                readerRix = diversions[tmpRix - 1].rix;  // follow diversion[N-1] back
                readerIx  = diversions[tmpRix - 1].ix;
                goto go_forward_done;  // the prospective move forward is done, we are on the return path of a diversion
            }
            fi
        }
        :: else;
        fi

        // if the prospective move reached (an entry side of) a diversion: follow it - to the beginning of respective rings[rix]
        // (another diversion may sit there, so then continue following)
        //
        // a diversion that leads to an array of Objects always precedes (in the diversions array) any diversions
        // that lead from that array of Objects, so one bottom-up pass through the diversions array
        // that starts at the diversion to 1 + readerRix suffices (i.e. a short linear search)

        for (tmpRix : (1 + readerRix) .. rixMax)
        {
            if
            :: ((diversions[tmpRix - 1].rix == readerRix)
             && (diversions[tmpRix - 1].ix  == readerIx)) ->
            {
                readerRix = tmpRix;  // move to the first element of the array of Objects the diversion leads to
                readerIx  = 0;
            }
            :: else;
            fi
        }

go_forward_done :  // prospective move forward is now done

        readerPositionRix = readerRix;  // new reader position = prospective reader position
        readerPositionIx  = readerIx;

        // increment cntDequeued, dequeue value and compare
        cntDequeued ++;
        int valueDequeued = rings[readerRix].element[readerIx];
        rings[readerRix].element[readerIx] = 0;  // clear the reader position
        printf("PID %d dequeued %d in rings[%d][%d]\n", _pid, valueDequeued, readerRix, readerIx);
        assert(cntDequeued == valueDequeued);  // this verifies the correct FIFO order

dequeue_done :
    }
}

/*********************************************
 init process
 *********************************************/
init
{
    pid pids[1];
    int idx;

    // prefill scenario (enqueues/dequeues one after the other)
    for (idx: 0 .. PREFILL_STEPS - 1)
    {
        // start process
        if
        :: (1 == prefill[idx]) ->
        {
            pids[0] = run enqueue();
            printf("init: pre-fill enqueue process %d\n", pids[0]);
        }
        :: else ->
        {
            pids[0] = run dequeue();
            printf("init: pre-fill dequeue process %d\n", pids[0]);
        }
        fi

        // join process
        (_nr_pr <= pids[0]);
        printf("init: joined pre-fill process %d\n", pids[0]);
    }

    int prefillCntEnqueued     = cntEnqueued;
    int prefillCntEnqueueFull  = cntEnqueueFull;
    int prefillCntDequeued     = cntDequeued;
    int prefillCntDequeueEmpty = cntDequeueEmpty;

    int cntFinishedEnqueues    = 0;
    int cntFinishedDequeues    = 0;

    // start the writer + reader processes one-after-the-other (this is OK as this is a lock-based Queue)
    //
    // The verification will try all permutations (more precisely: permutations with repetition)
    // of the writers and readers.
    //
    // Starting one-after-the-other creates a much smaller Spin state space than starting the processes concurrently,
    // allowing for verification with many more writers and readers!

    do
    :: (cntFinishedEnqueues < WRITERS) ->
    {
        // start process
        pids[0] = run enqueue();
        printf("init: enqueue process %d\n", pids[0]);

        // join process
        (_nr_pr <= pids[0]);
        printf("init: joined enqueue process %d\n", pids[0]);

        cntFinishedEnqueues ++;
    }
    :: (cntFinishedDequeues < READERS) ->
    {
        // start process
        pids[0] = run dequeue();
        printf("init: dequeue process %d\n", pids[0]);

        // join process
        (_nr_pr <= pids[0]);
        printf("init: joined dequeue process %d\n", pids[0]);

        cntFinishedDequeues ++;
    }
    :: else -> break;
    od

    // balance of enqueues
    assert(WRITERS == (cntEnqueued + cntEnqueueFull - (prefillCntEnqueued + prefillCntEnqueueFull)));

    // balance of dequeues
    assert(READERS == (cntDequeued + cntDequeueEmpty - (prefillCntDequeued + prefillCntDequeueEmpty)));

    // now: except when the "permutations phase" resulted in an empty Queue (unlikely but possible),
    // start reader processes one-after-the-other to empty the Queue
    // and then check that the Queue is indeed empty

    int leftInQueue = cntEnqueued - cntDequeued;
    printf("init: left in the Queue %d\n", leftInQueue);

    for (idx: 0 .. (leftInQueue - 1))
    {
        // start process
        pids[0] = run dequeue();
        printf("init: clean-up dequeue process %d\n", pids[0]);

        // join process
        (_nr_pr <= pids[0]);
        printf("init: joined clean-up process %d\n", pids[0]);
    }

    // the Queue must be empty now
    assert(cntEnqueued == cntDequeued);
    assert((writerPositionRix == readerPositionRix) && (writerPositionIx == readerPositionIx));

    int tmpRix;
    for (tmpRix: 0 .. CNT_ALLOWED_EXTENSIONS)
    {
        int tmpIx;
        for (tmpIx: 0 .. (MAX_ARRAY_SIZE - 1))
        {
            assert(0 == rings[tmpRix].element[tmpIx]);
        }
    }
}

