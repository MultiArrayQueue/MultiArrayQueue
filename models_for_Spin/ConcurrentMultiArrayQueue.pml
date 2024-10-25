/***********************************************************
 * MIT License
 * Copyright (c) 2024 Vít Procházka
 *
 * Promela model of the ConcurrentMultiArrayQueue for Spin.
 *
 * An exhaustive verification with more than 2 concurrent writers
 * plus 2 concurrent readers reaches feasibility limits and
 * requires bitstate hashing (-DBITSTATE).
 *
 * Keep in mind that all possible temporal interleaves
 * of all participating threads will be tested
 * (this is where the BlockingMultiArrayQueue is simpler
 * because there the threads cannot interleave "inside").
 *
 * Control the number of concurrent processes by editing
 * WRITERS and READERS below.
 *
 * Recommend to always set a memory limit, e.g.
 *
 *    spin -a ConcurrentMultiArrayQueue.pml
 *    cc -O2 -DMEMLIM=256 -o pan pan.c
 *    ./pan
 *
 * A random simulation with Spin, on the contrary,
 * can have a much higher number of concurrent processes:
 *
 *    spin ConcurrentMultiArrayQueue.pml
 *
 * The Queue is tested in empty state with FIRST_ARRAY_SIZE == 1
 * which is where the structure is "most dense".
 *
 * However, an optional pre-fill scenario can be specified.
 *
 * TLWACCH = Time Lag When Anything Concurrent Can Happen
 ***********************************************************/

/*********************************************
 verification data
 *********************************************/

#define PREFILL_STEPS 6

int prefill[6] = { 1, 1, 1, 1, 1, 1 }  // 1 = enqueue, 0 = dequeue

#define WRITERS 2
#define READERS 2

int cntEnqueued = 0;
int cntEnqueueFull = 0;

int cntDequeued = 0;
int cntDequeueEmpty = 0;

/*********************************************
 private data of the ConcurrentMultiArrayQueue
 *********************************************/

#define FIRST_ARRAY_SIZE 1
#define CNT_ALLOWED_EXTENSIONS 2

// MAX_ARRAY_SIZE = FIRST_ARRAY_SIZE * (2 ^ CNT_ALLOWED_EXTENSIONS)
#define MAX_ARRAY_SIZE 4

// TOTAL_CAPACITY = SUM( SIZES OF ALL ARRAYS ) - 1
#define TOTAL_CAPACITY (1+2+4-1)

typedef array {
    int element[MAX_ARRAY_SIZE];  // under-utilized except of the last array
}

array rings[1 + CNT_ALLOWED_EXTENSIONS];

int ringsMaxIndex = 0;

typedef diversion {
    int rix = 0;
    int ix = 0;
}

diversion diversions[CNT_ALLOWED_EXTENSIONS];

int  writerPositionRound = 0;
bool writerPositionFlag = false;
int  writerPositionRix = 0;
int  writerPositionIx = 0;

int  readerPositionRound = 0;
int  readerPositionRix = 0;
int  readerPositionIx = 0;

/*********************************************
 enqueue process
 *********************************************/
proctype enqueue()
{
    int  origWriterRound;  // writer original
    bool origWriterFlag;
    int  origWriterRix;
    int  origWriterIx;
    int  writerRound;  // writer prospective
    int  writerRix;
    int  writerIx;
    int  readerRound;  // reader
    int  readerRix;
    int  readerIx;
    int  cntEnqueuedOnLPFull;
    int  cntDequeuedOnLPFull;
    int  rixMax;
    bool isQueueExtensionPossible;
    bool extendQueue;
    bool queueIsFull;
    int  rixMaxNew;
    int  valueToEnqueue;
    int  tmpRix;

start_anew : skip;

    d_step  // block if the extension-in-progress flag is set, then read writer position
    {
        if
        :: (! writerPositionFlag) ->
        {
            origWriterRound = writerPositionRound;
            origWriterFlag  = writerPositionFlag;
            origWriterRix   = writerPositionRix;
            origWriterIx    = writerPositionIx;
            writerRound = origWriterRound;
            writerRix   = origWriterRix;
            writerIx    = origWriterIx;
            assert(writerIx < (FIRST_ARRAY_SIZE << writerRix));
        }
        fi
    }

    /*TLWACCH*/

    d_step  // read reader position
    {
        readerRound = readerPositionRound;
        readerRix   = readerPositionRix;
        readerIx    = readerPositionIx;
        assert(readerIx < (FIRST_ARRAY_SIZE << readerRix));
        assert(writerRound <= (readerRound + 1));

        // linearization point for Queue full: remember cntEnqueued, cntDequeued
        cntEnqueuedOnLPFull = cntEnqueued;
        cntDequeuedOnLPFull = cntDequeued;
    }

    /*TLWACCH*/

    d_step  // read ringsMaxIndex + work on local variables + read diversions up to ringsMaxIndex
    {
        rixMax = ringsMaxIndex;

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
                writerRound ++;  // we are passing rings[0][0], so increment round
                writerRix = 0;  // move to rings[0][0]
                writerIx  = 0;
                // do not break here because from rings[0][0] eventually diversion(s) shall be followed forward
            }
            :: else ->  // i.e. we are in a "higher" rings[N]
            {
                tmpRix = writerRix;
                writerRix = diversions[tmpRix - 1].rix;  // follow diversion[N-1] back
                writerIx  = diversions[tmpRix - 1].ix;
                if
                :: ((readerRound + 1) == writerRound) ->
                {
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
                            // and has not seen the reader there (otherwise it would have created a new diversion and gone there)
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
        // that starts at 1 + writerRix suffices (i.e. a short linear search)

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

        if
        :: ((readerRound + 1) == writerRound) ->
        {
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

            // the forward-looking check to prevent the next writer from hitting the reader "from behind"
            // on the return path of a diversion (see Paper for explanation)
            :: else ->
            {
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
        }
        :: else;
        fi

go_forward_done :  // prospective move forward is now done
    }

    /*TLWACCH*/

    // preparations are done, start the actual work
    if
    :: (queueIsFull) ->
    {
        cntEnqueueFull ++;
        printf("PID %d found the Queue full on enqueue\n", _pid);
        assert(TOTAL_CAPACITY == (cntEnqueuedOnLPFull - cntDequeuedOnLPFull));  // must compare with counts from the linearization point!
    }
    :: (extendQueue) ->
    {
        atomic  // CAS + work on local variables + work on rings and diversions that is not visible till the new ringsMaxIndex is published
        {
            // CAS into the writer position our copy of the writer position + the extension-in-progress flag
            if
            :: ((origWriterRound == writerPositionRound)
             && (origWriterFlag == writerPositionFlag)
             && (origWriterRix == writerPositionRix)
             && (origWriterIx == writerPositionIx)) ->
            {
                writerPositionFlag = true;  // CAS write part

                // (spot C relevant to lock-freedom, see Paper)
                // other writers are now "locked-out", so go ahead with extending the Queue + creating the new diversion
                // (readers can continue their work but once they deplete the Queue, they cannot go past the writerPosition)

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

                // here the actual program code writes into the first array element of the new array,
                // so we model it by preliminarily writing a wrong value that would throw the FIFO order assertion error
                // if indeed dequeued (and update it to the correct value later on the linearization point)
                rings[rixMaxNew].element[0] = -1;

                diversions[rixMaxNew - 1].rix = writerRix;  // the new diversion = the prospective writer position
                diversions[rixMaxNew - 1].ix  = writerIx;
            }
            :: else ->
            {
                goto start_anew;  // CAS failed (i.e. lost the race against other writers) --> Start anew
            }
            fi
        }

        /*TLWACCH*/

        ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex (makes the work on rings and diversions visible)

        /*TLWACCH*/

        d_step  // concluding CAS of the extension operation
        {
            // here is the linearization point, so increment cntEnqueued and write it to the array
            cntEnqueued ++;
            rings[rixMaxNew].element[0] = cntEnqueued;  // update to correct value the first array element of the new array
            printf("PID %d extended Queue and enqueued %d in rings[%d][%d]\n", _pid, cntEnqueued, rixMaxNew, 0);

            writerPositionRound = writerRound;  // write the writer position
            writerPositionFlag = false;
            writerPositionRix = rixMaxNew;  // new writer position = first array element of the new array
            writerPositionIx = 0;
        }
    }
    :: else ->  // no extendQueue
    {
        // wait for the prospective writer position to become cleared by the respective reader
        // (this has most probably already happened or shall occur "soon" (if the reader is in spot B))
        //
        // if the writerPosition has moved forward during the waiting, we have to stop it,
        // because then another writer has in the meantime obtained (and written again) the position,
        // so we would wait forever
        //
        // (if writerPosition has moved, the CAS would fail anyway)

        atomic
        {
            if
            :: ((origWriterRound != writerPositionRound)
             || (origWriterFlag != writerPositionFlag)
             || (origWriterRix != writerPositionRix)
             || (origWriterIx != writerPositionIx)) ->
            {
                goto start_anew;
            }
            :: (0 == rings[writerRix].element[writerIx]) ->
            {
                goto writer_cas;
            }
            fi
        }

        /*TLWACCH*/

writer_cas :

        // CAS the prospective writer position
        atomic
        {
            if
            :: ((origWriterRound == writerPositionRound)
             && (origWriterFlag == writerPositionFlag)
             && (origWriterRix == writerPositionRix)
             && (origWriterIx == writerPositionIx)) ->
            {
                writerPositionRound = writerRound;  // CAS write part
                writerPositionFlag = false;
                writerPositionRix = writerRix;
                writerPositionIx = writerIx;

                // here is the linearization point, so increment and remember cntEnqueued
                cntEnqueued ++;
                valueToEnqueue = cntEnqueued;
                printf("PID %d enqueued %d in rings[%d][%d]\n", _pid, valueToEnqueue, writerRix, writerIx);
            }
            :: else ->
            {
                goto start_anew;  // CAS failed (i.e. lost the race against other writers) --> Start anew
            }
            fi
        }

        /*TLWACCH*/

        // (spot A relevant to lock-freedom, see Paper)
        // the writer position is now "ours", so write into it

        d_step
        {
            assert(0 == rings[writerRix].element[writerIx]);
            rings[writerRix].element[writerIx] = valueToEnqueue;
        }
    }
    fi
}

/*********************************************
 dequeue process
 *********************************************/
proctype dequeue()
{
    int  origReaderRound;  // reader original
    int  origReaderRix;
    int  origReaderIx;
    int  readerRound;  // reader prospective
    int  readerRix;
    int  readerIx;
    int  writerRound;  // writer
    int  writerRix;
    int  writerIx;
    int  rixMax;
    int  valueDequeued;
    int  tmpRix;

start_anew : skip;

    d_step  // read reader position
    {
        origReaderRound = readerPositionRound;
        origReaderRix   = readerPositionRix;
        origReaderIx    = readerPositionIx;
        readerRound = origReaderRound;
        readerRix   = origReaderRix;
        readerIx    = origReaderIx;
        assert(readerIx < (FIRST_ARRAY_SIZE << readerRix));
    }

    /*TLWACCH*/

    atomic  // read writer position
    {
        writerRound = writerPositionRound;
        writerRix   = writerPositionRix;
        writerIx    = writerPositionIx;
        assert(writerIx < (FIRST_ARRAY_SIZE << writerRix));
        assert(readerRound <= writerRound);

        // linearization point for Queue empty
        // if the reader stands on the writer: the Queue is empty
        if
        :: ((writerRound == readerRound) && (writerRix == readerRix) && (writerIx == readerIx)) ->
        {
            cntDequeueEmpty ++;
            printf("PID %d found the Queue empty on dequeue\n", _pid);
            assert(0 == (cntEnqueued - cntDequeued));
            goto dequeue_done;
        }
        :: else;
        fi
    }

    /*TLWACCH*/

    d_step  // read ringsMaxIndex + work on local variables + read diversions up to ringsMaxIndex
    {
        rixMax = ringsMaxIndex;

        readerIx ++;  // prospective move forward

        // if the prospective move goes "beyond" the end of rings[readerRix]
        if
        :: ((FIRST_ARRAY_SIZE << readerRix) == readerIx) ->
        {
            if
            :: (0 == readerRix) ->  // if in rings[0]
            {
                readerRound ++;  // we are passing rings[0][0], so increment round
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
        // that starts at 1 + readerRix suffices (i.e. a short linear search)

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
    }

    /*TLWACCH*/

    // wait for the prospective reader position to become filled by the respective writer
    // (this has most probably already happened or shall occur "soon" (if the writer is in spot A))
    //
    // if the readerPosition has moved forward during the waiting, we have to stop it,
    // because then another reader has in the meantime obtained (and cleared again) the position,
    // so we would wait forever
    //
    // (if readerPosition has moved, the CAS would fail anyway)

    atomic
    {
        if
        :: ((origReaderRound != readerPositionRound)
         || (origReaderRix != readerPositionRix)
         || (origReaderIx != readerPositionIx)) ->
        {
            goto start_anew;
        }
        :: (0 != rings[readerRix].element[readerIx]) ->
        {
            valueDequeued = rings[readerRix].element[readerIx];
            goto reader_cas;
        }
        fi
    }

    /*TLWACCH*/

reader_cas :

    // CAS the prospective reader position
    atomic
    {
        if
        :: ((origReaderRound == readerPositionRound)
         && (origReaderRix == readerPositionRix)
         && (origReaderIx == readerPositionIx)) ->
        {
            readerPositionRound = readerRound;  // CAS write part
            readerPositionRix = readerRix;
            readerPositionIx = readerIx;

            // here is the linearization point, so increment cntDequeued and compare it with valueDequeued
            cntDequeued ++;
            printf("PID %d dequeued %d in rings[%d][%d]\n", _pid, valueDequeued, readerRix, readerIx);
            assert(cntDequeued == valueDequeued);  // this verifies the correct FIFO order
        }
        :: else ->
        {
            goto start_anew;  // CAS failed (i.e. lost the race against other readers) --> Start anew
        }
        fi
    }

    /*TLWACCH*/

    // (spot B relevant to lock-freedom, see Paper)
    // the reader position is now "ours", so clear it

    d_step
    {
        assert(valueDequeued == rings[readerRix].element[readerIx]);
        rings[readerRix].element[readerIx] = 0;
    }

dequeue_done :
}

/*********************************************
 init process
 *********************************************/
init
{
    pid pids[WRITERS + READERS];
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

    // start all writer + reader processes concurrently
    atomic
    {
        for (idx: 0 .. (WRITERS - 1))
        {
            pids[idx] = run enqueue();
            printf("init: enqueue process %d\n", pids[idx]);
        }
        for (idx: WRITERS .. (WRITERS + READERS - 1))
        {
            pids[idx] = run dequeue();
            printf("init: dequeue process %d\n", pids[idx]);
        }

        printf("init: initialized all processes\n");
    }

    // join the concurrent processes
    for (idx: 0 .. (WRITERS + READERS - 1))
    {
        (_nr_pr <= pids[WRITERS + READERS - 1 - idx]);
        printf("init: joined process %d\n", pids[WRITERS + READERS - 1 - idx]);
    }

    // balance of enqueues
    assert(WRITERS == (cntEnqueued + cntEnqueueFull - (prefillCntEnqueued + prefillCntEnqueueFull)));

    // balance of dequeues
    assert(READERS == (cntDequeued + cntDequeueEmpty - (prefillCntDequeued + prefillCntDequeueEmpty)));

    // now: except when the concurrent phase resulted in an empty Queue (unlikely but possible),
    // start reader processes one after the other to empty the Queue
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
    assert((writerPositionRound == readerPositionRound) && (writerPositionRix == readerPositionRix) && (writerPositionIx == readerPositionIx));

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

