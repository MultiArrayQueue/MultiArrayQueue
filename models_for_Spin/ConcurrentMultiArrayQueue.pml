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
 *    cc -O2 -DMEMLIM=512 -o pan pan.c
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

// Hint: For construction of the pre-fill scenario it is helpful to use the Interactive Simulator:
// https://MultiArrayQueue.github.io/Simulator_MultiArrayQueue.html

#define PREFILL_STEPS 6

int prefill[6] = { 1, 0, 1, 0, 1, 1 }  // 1 = enqueue, 0 = dequeue

#define WRITERS 2
#define READERS 2

int cntEnqueued = 0;
int cntEnqueueFull = 0;

int cntDequeued = 0;
int cntDequeueEmpty = 0;

// this can be used to remember a "stale" state from after the given pre-fill step
// and let the concurrent writers start with this stale writerPosition and readerPosition

#define STALE_DATA_STEP -1

int staleWriterPositionRound = -1;
int staleWriterPositionRix = -1;
int staleWriterPositionIx = -1;

int staleReaderPositionRound = -1;
int staleReaderPositionRix = -1;
int staleReaderPositionIx = -1;

/*********************************************
 private data of the ConcurrentMultiArrayQueue
 *********************************************/

#define FIRST_ARRAY_SIZE 1
#define CNT_ALLOWED_EXTENSIONS 3

// MAX_ARRAY_SIZE = FIRST_ARRAY_SIZE * (2 ^ CNT_ALLOWED_EXTENSIONS)
#define MAX_ARRAY_SIZE 8

// MAXIMUM_CAPACITY = SUM( SIZES OF ALL ARRAYS ) - 1
#define MAXIMUM_CAPACITY (1+2+4+8-1)

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

bool preferExtensionOverWaitForB = true;
bool preferReturnEmptyOverWaitForA = false;

/*********************************************
 enqueue process
 *********************************************/
inline EXTEND_QUEUE(spot)
{
    printf("PID %d indicates Queue extension (%d)\n", _pid, spot);
    extendQueue = true;
}

inline QUEUE_IS_FULL(spot)
{
    cntEnqueueFull ++;
    printf("PID %d found the Queue full on enqueue (%d)\n", _pid, spot);
    assert(MAXIMUM_CAPACITY == (cntEnqueuedOnLPFull - cntDequeuedOnLPFull));  // must compare with counts from the linearization point!
    queueIsFull = true;
}

proctype enqueue(bool useStale)
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
    bool queueIsFullCheck;
    bool recheckFromFullyExtended = false;
    int  rixMaxNew;
    int  valueToEnqueue;
    int  tmpRix;

start_anew : skip;

    d_step  // block if the extension-in-progress flag is set, then read writer position
    {
        if
        :: (useStale && (-1 != staleWriterPositionRound)) ->
        {
            origWriterRound = staleWriterPositionRound;
            origWriterFlag  = false;
            origWriterRix   = staleWriterPositionRix;
            origWriterIx    = staleWriterPositionIx;
            printf("PID %d starts with stale writerPosition (%d,%d,%d)\n",
            _pid, origWriterRound, origWriterRix, origWriterIx);
        }
        :: (( !(useStale && (-1 != staleWriterPositionRound)) ) && (! writerPositionFlag)) ->
        {
            origWriterRound = writerPositionRound;
            origWriterFlag  = writerPositionFlag;
            origWriterRix   = writerPositionRix;
            origWriterIx    = writerPositionIx;
            printf("PID %d has read writerPosition (%d,%d,%d)\n",
            _pid, origWriterRound, origWriterRix, origWriterIx);
        }
        fi
        writerRound = origWriterRound;
        writerRix   = origWriterRix;
        writerIx    = origWriterIx;
        assert(writerIx < (FIRST_ARRAY_SIZE << writerRix));
    }

    /*TLWACCH*/

    d_step  // read reader position
    {
        if
        :: (useStale && (-1 != staleReaderPositionRound)) ->
        {
            readerRound = staleReaderPositionRound;
            readerRix   = staleReaderPositionRix;
            readerIx    = staleReaderPositionIx;
            printf("PID %d starts with stale readerPosition (%d,%d,%d)\n",
            _pid, readerRound, readerRix, readerIx);
        }
        :: else ->
        {
            readerRound = readerPositionRound;
            readerRix   = readerPositionRix;
            readerIx    = readerPositionIx;
            printf("PID %d has read readerPosition (%d,%d,%d)\n",
            _pid, readerRound, readerRix, readerIx);
        }
        fi
        assert(readerIx < (FIRST_ARRAY_SIZE << readerRix));
        assert(writerRound <= (readerRound + 1));

        // linearization point for Queue full: remember cntEnqueued, cntDequeued
        cntEnqueuedOnLPFull = cntEnqueued;
        cntDequeuedOnLPFull = cntDequeued;

        useStale = false;  // stale state is used only initially
    }

    /*TLWACCH*/

    d_step  // read ringsMaxIndex + work on local variables + read diversions up to ringsMaxIndex
    {
        rixMax = ringsMaxIndex;
        printf("PID %d has read ringsMaxIndex %d\n", _pid, rixMax);

        isQueueExtensionPossible = (rixMax < CNT_ALLOWED_EXTENSIONS);  // if there is room yet for the extension
        extendQueue = false;
        queueIsFull = false;
        queueIsFullCheck = false;

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

                // if the prospective move has hit the reader (that is in the previous round) "from behind"
                if
                :: ((readerRix == writerRix) && (readerIx == writerIx)) ->
                {
                    if
                    :: ((readerRound + 1) == writerRound) ->
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
                            QUEUE_IS_FULL(1);
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
            :: ((readerRound + 1) == writerRound) ->
            {
                if
                :: (isQueueExtensionPossible) ->
                {
                    EXTEND_QUEUE(1);
                    goto go_forward_done;
                }
                :: else ->  // the Queue is now fully extended (but might not have been at the reading of origWriter)
                // (the following checks are necessary because there is no CAS that would guard the "Queue is full" outcome)
                {
                    if
                    :: (recheckFromFullyExtended) ->  // we have already re-checked from here, i.e. from the fully extended state
                    {
                        QUEUE_IS_FULL(2);
                        goto go_forward_done;
                    }
                    :: else ->
                    {
                        if
                        :: (CNT_ALLOWED_EXTENSIONS == origWriterRix) ->  // then origWriter must be from the fully extended state
                        {
                            QUEUE_IS_FULL(3);
                            goto go_forward_done;
                        }
                        :: else ->
                        {
                            queueIsFullCheck = true;  // the next check requires writerPosition, also it must be done after a TLWACCH
                            goto go_forward_done;
                        }
                        fi
                    }
                    fi
                }
                fi
            }
            :: else;
            fi
        }
        :: else;
        fi

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
                :: ((readerRound + 1) == writerRound) ->
                {
                    if
                    :: (isQueueExtensionPossible) ->
                    {
                        EXTEND_QUEUE(2);
                    }
                    :: else;
                    fi
                    break;
                }
                :: else;
                fi
            }
            :: else;
            fi

            if
            :: (preferExtensionOverWaitForB) ->
            {
                // preferExtensionOverWaitForB:
                //
                // also prevent the next writer from running into waiting for a reader that is in spot B
                // on the return path of a diversion
                if
                :: (0 != rings[testNextWriterRix].element[testNextWriterIx]) ->
                {
                    if
                    :: (isQueueExtensionPossible) ->
                    {
                        EXTEND_QUEUE(3);
                    }
                    :: else;
                    fi
                    break;
                }
                :: else;
                fi
            }
            :: else;
            fi
        }
        :: else -> break;
        od

go_forward_done :  // prospective move forward is now done
    }

    /*TLWACCH*/

    // preparations are done, start the actual work

    atomic
    {
        if
        :: (queueIsFull)  // just go ahead
        :: (queueIsFullCheck)  // just go ahead
        :: (extendQueue)  // just go ahead

        :: else ->
        {
            // wait for the prospective writer position to become cleared by the respective reader
            // (that is in the previous round)
            //
            // three scenarios are possible:
            //
            //   1. this has most probably already happened
            //   2. this shall occur "soon" (if the reader is in spot B and is NOT preempted there)
            //   3. this may occur "very late" (if the reader is in spot B and IS preempted there)
            //
            // if the writerPosition has moved forward during the waiting, we have to stop it,
            // because this means that another writer has in the meantime obtained the position,
            // and possibly also has written to it, so we could wait forever
            //
            // (if writerPosition has moved, the CAS would fail anyway)

            if
            :: ((origWriterRound != writerPositionRound)
             || (origWriterFlag != writerPositionFlag)
             || (origWriterRix != writerPositionRix)
             || (origWriterIx != writerPositionIx)) ->  // writerPosition has moved --> Stop waiting, Start anew
            {
                goto start_anew;
            }

            :: ((origWriterRound == writerPositionRound)
             && (origWriterFlag == writerPositionFlag)
             && (origWriterRix == writerPositionRix)
             && (origWriterIx == writerPositionIx)
             && (0 == rings[writerRix].element[writerIx]))  // position is cleared, go ahead

            :: ((origWriterRound == writerPositionRound)
             && (origWriterFlag == writerPositionFlag)
             && (origWriterRix == writerPositionRix)
             && (origWriterIx == writerPositionIx)
             && (0 != rings[writerRix].element[writerIx])
             && preferExtensionOverWaitForB
             && isQueueExtensionPossible) ->
            {
                // preferExtensionOverWaitForB:
                // (the other part of this functionality is in the forward-looking check)
                //
                // What we are doing here is to avoid the waiting by extending the Queue instead.
                // This is of course possible only as long as the Queue is not yet fully extended.
                //
                // This solves the following problem for the writer threads:
                //
                // The waiting for the reader, if preempted exactly in spot B (scenario 3 above)
                // can last up into the milliseconds range (the reader's suspend time)
                // and this is where the reader threads can inflict ugly latency spikes
                // on the writer threads (that are possibly more time-critical).
                //
                // The extension operations are presumably quicker. Further, they cause
                // the Queue to grow to a size where the writerPosition and the readerPosition
                // will be so far apart that the problem fades away.
                // (This will of course cost memory!)

                EXTEND_QUEUE(4);  // extend the Queue instead of waiting for the reader that is in spot B
            }
            fi
        }
        fi
    }

    /*TLWACCH*/

    if
    :: (queueIsFull)  // just finish

    :: (queueIsFullCheck) ->  // continue the checks with the next check that needs writerPosition
    {
        atomic
        {
            if
            :: (  // if writerPosition has not changed, then origWriter must be from the fully extended state (as we are now)
                (origWriterRound == writerPositionRound)
             && (origWriterFlag == writerPositionFlag)
             && (origWriterRix == writerPositionRix)
             && (origWriterIx == writerPositionIx)
               ) ->
            {
                QUEUE_IS_FULL(4);
            }
            :: else ->  // origWriter is potentially stale from a past extension state of the Queue --> Start anew
            {
                recheckFromFullyExtended = true;
                goto start_anew;
            }
            fi
        }
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
                //
                // for preferExtensionOverWaitForB:
                // this check would also detect a scenario where we would erroneously extend the Queue
                // on the return path of a diversion to avoid waiting for a reader that is in spot B there
                // (also the scenario which the forward-looking check should have prevented)

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
                goto start_anew;  // CAS failed (i.e. we have lost the race against other writers) --> Start anew
            }
            fi
        }

        /*TLWACCH*/

        d_step
        {
            ringsMaxIndex = rixMaxNew;  // increment ringsMaxIndex (makes the work on rings and diversions visible)
            printf("PID %d incremented ringsMaxIndex to %d\n", _pid, rixMaxNew);
        }

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
                goto start_anew;  // CAS failed (i.e. we have lost the race against other writers) --> Start anew
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
        :: ((writerRix == readerRix) && (writerIx == readerIx) && (writerRound == readerRound)) ->
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

    d_step  // work on local variables + read ringsMaxIndex + read diversions up to ringsMaxIndex
    {
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
        // that starts at the diversion to 1 + readerRix suffices (i.e. a short linear search)

        rixMax = ringsMaxIndex;

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
    //
    // three scenarios are possible:
    //
    //   1. this has most probably already happened
    //   2. this shall occur "soon" (if the writer is in spot A and is NOT preempted there)
    //   3. this may occur "very late" (if the writer is in spot A and IS preempted there)
    //
    // if the readerPosition has moved forward during the waiting, we have to stop it,
    // because this means that another reader has in the meantime obtained the position,
    // and possibly also has cleared it, so we could wait forever
    //
    // (if readerPosition has moved, the CAS would fail anyway)

    atomic
    {
        if
        :: ((origReaderRound != readerPositionRound)
         || (origReaderRix != readerPositionRix)
         || (origReaderIx != readerPositionIx)) ->  // readerPosition has moved --> Stop waiting, Start anew
        {
            goto start_anew;
        }
        :: ((origReaderRound == readerPositionRound)
         && (origReaderRix == readerPositionRix)
         && (origReaderIx == readerPositionIx)
         && (0 != rings[readerRix].element[readerIx])) ->  // position is filled, go ahead
        {
            valueDequeued = rings[readerRix].element[readerIx];
        }
        :: ((origReaderRound == readerPositionRound)
         && (origReaderRix == readerPositionRix)
         && (origReaderIx == readerPositionIx)
         && (0 == rings[readerRix].element[readerIx])
         && (preferReturnEmptyOverWaitForA)) ->
        {
            // preferReturnEmptyOverWaitForA:
            //
            // What we are doing here is to avoid the waiting by returning "Queue is empty" instead.
            //
            // This goes at the cost of losing Linearizability, because "Queue is empty" can be returned
            // although the Queue has never been empty between the invocation of and the return from the Operation,
            // i.e. the count of successful Dequeue CASes was never equal to the count of successful Enqueue CASes
            // during that time.
            //
            // This can however benefit the reader threads:
            //
            // The waiting for the writer, if preempted exactly in spot A (scenario 3 above)
            // can last up into the milliseconds range (the writer's suspend time)
            // and this is where the writer threads can inflict ugly latency spikes
            // on the reader threads.
            //
            // Although the readers cannot force the writers to "speed up", they could spend the time elsewhere.
            // For example, if a reader reads from multiple Queues, it can read from the other Queues in the meantime
            // and come back to the would-be-waited-for Object in "this" Queue later!

            printf("PID %d returned Queue empty instead of waiting for the writer that is in spot A\n", _pid);
            goto dequeue_done;
        }
        fi
    }

    /*TLWACCH*/

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
            goto start_anew;  // CAS failed (i.e. we have lost the race against other readers) --> Start anew
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
            pids[0] = run enqueue(false);
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

        // remember the stale state if desired
        if
        :: (STALE_DATA_STEP == idx) ->
        {
            staleWriterPositionRound = writerPositionRound;
            staleWriterPositionRix = writerPositionRix;
            staleWriterPositionIx = writerPositionIx;

            staleReaderPositionRound = readerPositionRound;
            staleReaderPositionRix = readerPositionRix;
            staleReaderPositionIx = readerPositionIx;
        }
        :: else;
        fi
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
            pids[idx] = run enqueue(true);
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

