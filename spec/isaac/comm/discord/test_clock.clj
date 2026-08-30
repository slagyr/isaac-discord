(ns isaac.comm.discord.test-clock
  "Test-only virtual clock for driving the discord gateway's heartbeat
   task without real wall-time delays.

   `make` returns a scheduler whose `:clock` reads a test-owned atom,
   with `:pool-size 1` so handler dispatches are serialized. `advance!`
   moves the clock, ticks, and blocks until the pool has drained — so
   assertions immediately after see a quiescent state, just like the
   old hand-rolled `gateway/advance-time!` did."
  (:require
    [isaac.scheduler.runtime :as scheduler])
  (:import
    (java.time Instant)
    (java.util.concurrent ExecutorService)))

(defn make []
  (let [now* (atom (Instant/parse "2026-05-21T10:00:00Z"))
        sch  (scheduler/create {:clock     (fn [] @now*)
                                :pool-size 1})]
    {:scheduler sch :now* now*}))

(defn- drain! [clock]
  (let [^ExecutorService ex (:executor (:scheduler clock))]
    (.get (.submit ex ^Runnable (fn [])))))

(defn advance!
  "Move the virtual clock forward `ms` milliseconds, firing every scheduler
   task that would have come due in that window (retries included)."
  [clock ms]
  (let [^Instant target (.plusMillis ^Instant @(:now* clock) ms)
        sch             (:scheduler clock)]
    (loop [n 0]
      (when (> n 10000)
        (throw (ex-info "test clock advance ran away" {:ms ms})))
      (let [due (->> (scheduler/list-tasks sch)
                     (keep :next-fire-at)
                     (filter (fn [^Instant t] (not (.isAfter t target))))
                     sort)]
        (if-let [^Instant next (first due)]
          (do
            (reset! (:now* clock) next)
            (scheduler/tick! sch)
            (drain! clock)
            (recur (inc n)))
          (do
            (reset! (:now* clock) target)
            (scheduler/tick! sch)
            (drain! clock)))))
    clock))
