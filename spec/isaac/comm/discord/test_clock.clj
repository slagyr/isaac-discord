(ns isaac.comm.discord.test-clock
  "Test-only virtual clock for driving the discord gateway's heartbeat
   task without real wall-time delays.

   `make` returns a scheduler whose `:clock` reads a test-owned atom,
   with `:pool-size 1` so handler dispatches are serialized. `advance!`
   moves the clock, ticks, and blocks until the pool has drained — so
   assertions immediately after see a quiescent state, just like the
   old hand-rolled `gateway/advance-time!` did."
  (:require
    [isaac.scheduler :as scheduler])
  (:import
    (java.time Instant)
    (java.util.concurrent ExecutorService)))

(defn make []
  (let [now* (atom (Instant/parse "2026-05-21T10:00:00Z"))
        sch  (scheduler/create {:clock     (fn [] @now*)
                                :pool-size 1})]
    {:scheduler sch :now* now*}))

(defn advance! [clock ms]
  (swap! (:now* clock) (fn [^Instant t] (.plusMillis t ms)))
  (scheduler/tick! (:scheduler clock))
  ;; Drain the pool: submit a barrier task after tick! and wait. Pool
  ;; is size 1 so FIFO ordering guarantees prior handlers ran first.
  (let [^ExecutorService ex (:executor (:scheduler clock))]
    (.get (.submit ex ^Runnable (fn []))))
  clock)
