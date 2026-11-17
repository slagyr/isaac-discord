(ns isaac.comm.discord.gateway-resume-spec
  "Resume URL, close codes, invalid-session wait, reconnect backoff and
   close-path log levels (zane-lab discord-resume)."
  (:require
    [cheshire.core :as json]
    [isaac.agent.util.ws-client :as ws]
    [isaac.comm.discord.gateway :as sut]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.foundation.config.validation-lexicon]
    [isaac.foundation.logger :as log]
    [isaac.foundation.spec-helper :as helper]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def ^:private default-url "wss://gateway.discord.gg/?v=10&encoding=json")
(def ^:private resume-url "wss://gateway-us-east1-b.discord.gg")

(defn- recording-connect!
  "Fake connect-ws! that records every dial: url, the virtual time it was
   dialed, sent payloads and the close code each transport was closed with.
   `fail?` decides per dial (1-based) whether it throws."
  [clock fail?]
  (let [dials (atom [])]
    {:dials dials
     :connect!
     (fn [url callbacks]
       (let [n (inc (count @dials))
             dial {:url url :at @(:now* clock) :callbacks callbacks
                   :sent (atom []) :closed-with (atom nil)}]
         (swap! dials conj dial)
         (when (fail? n)
           (throw (java.net.ConnectException. "Connection refused")))
         {:callback-driven?  true
          :send!             (fn [payload] (swap! (:sent dial) conj payload))
          :close-with-code!  (fn [code] (reset! (:closed-with dial) code))}))}))

(defn- frame! [dial message]
  ((:on-message (:callbacks dial)) (json/generate-string message)))

(defn- ready! [dial]
  (frame! dial {:op 10 :d {:heartbeat_interval 45000}})
  (frame! dial {:op 0 :t "READY" :s 7 :d {:session_id         "sess-1"
                                          :resume_gateway_url resume-url
                                          :user               {:id "bot"}}}))

(defn- ms-between [^Instant a ^Instant b]
  (- (.toEpochMilli b) (.toEpochMilli a)))

(defn- client-with [clock connect! & {:as opts}]
  (sut/connect! (merge {:token       "test-token"
                        :url         default-url
                        :scheduler   (:scheduler clock)
                        :connect-ws! connect!}
                       opts)))

(defn- noisy-gateway-entries []
  (->> (log/get-entries)
       (filter #(#{:warn :error} (:level %)))
       (map (juxt :level :event))))

(describe "Discord gateway resume"

  (before
    (log/set-output! :memory)
    (log/clear-entries!))

  (context "resume_gateway_url"

    (it "resumes on READY's resume_gateway_url with the initial query"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= 2 (count @dials))
        (should= (str resume-url "/?v=10&encoding=json") (:url (second @dials)))
        (should= {:op 6 :d {:token "test-token" :session_id "sess-1" :seq 7}}
                 (first @(:sent (second @dials))))))

    (it "tolerates a trailing slash on resume_gateway_url"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (frame! (first @dials) {:op 10 :d {:heartbeat_interval 45000}})
        (frame! (first @dials) {:op 0 :t "READY" :s 1 :d {:session_id "s" :resume_gateway_url (str resume-url "/") :user {:id "bot"}}})
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= (str resume-url "/?v=10&encoding=json") (:url (second @dials)))))

    (it "re-identifies on the default URL and forgets the session after opcode 9 d=false"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            client (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 9 :d false})
        (test-clock/advance! clock 5000)
        (should= 2 (count @dials))
        (should= default-url (:url (second @dials)))
        (should= 2 (:op (first @(:sent (second @dials)))))
        (should-be-nil (:session-id @(:state client)))
        (should-be-nil (:resume-gateway-url @(:state client))))))

  (context "close codes"

    (it "closes with a non-1000 code when it means to resume, so Discord keeps the session"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (should= 4000 @(:closed-with (first @dials)))))

    (it "closes with 1000 on stop"
      (let [clock  (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            client (client-with clock connect!)]
        (ready! (first @dials))
        (sut/stop! client)
        (should= 1000 @(:closed-with (first @dials))))))

  (context "opcode 9 d=false"

    (it "waits a random 1-5 s before the fresh IDENTIFY (rand 0.5 -> 3000 ms)"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 9 :d false})
        (test-clock/advance! clock 2999)
        (should= 1 (count @dials))
        (test-clock/advance! clock 1)
        (should= 2 (count @dials))
        (should= 2 (:op (first @(:sent (second @dials)))))))

    (it "never waits less than 1 s (rand 0.0)"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.0))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 9 :d false})
        (test-clock/advance! clock 999)
        (should= 1 (count @dials))
        (test-clock/advance! clock 1)
        (should= 2 (count @dials))))

    (it "never waits 5 s or more (rand just under 1.0)"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.9999))]
        (ready! (first @dials))
        (frame! (first @dials) {:op 9 :d false})
        (test-clock/advance! clock 5000)
        (should= 2 (count @dials))
        (should (< (ms-between (:at (first @dials)) (:at (second @dials))) 5000)))))

  (context "reconnect backoff"

    (it "backs off exponentially with jitter, capped, measured from each failure"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock #(> % 1))
            _     (client-with clock connect!
                               :reconnect-delay-ms 1000
                               :reconnect-max-delay-ms 8000
                               :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        ((:on-close (:callbacks (first @dials))) {:status 1006 :reason "transport-error"})
        (dotimes [_ 60] (test-clock/advance! clock 1000))
        (let [ats  (map :at (rest @dials))
              gaps (map ms-between ats (rest ats))]
          ;; delay(n) = d/2 + rand*d/2 with d = min(cap, base*2^(n-1)); rand 0.5 -> 0.75d
          (should= [1500 3000 6000 6000 6000 6000 6000] (vec gaps)))))

    ;; 2026-11-15 16:34-16:36Z: attempt 1 hung 30 s in connect, and the retry
    ;; delays (+1, +2, +4, +8 s) were anchored to the attempt's scheduled time,
    ;; so attempts 2-6 fired back-to-back as soon as each one failed.
    (it "schedules the next attempt from the failure time, not the attempt's scheduled time"
      (let [clock     (test-clock/make)
            failed-at (atom nil)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            hang!     (fn [url callbacks]
                        (if (empty? @dials)
                          (connect! url callbacks)
                          (do (swap! (:now* clock) #(.plusMillis ^Instant % 30000))
                              (reset! failed-at @(:now* clock))
                              (throw (java.net.ConnectException. "connect timed out")))))
            client    (client-with clock hang!
                                   :reconnect-delay-ms 1000
                                   :reconnect-max-delay-ms 30000
                                   :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        ((:on-close (:callbacks (first @dials))) {:status 1006 :reason "transport-error"})
        (test-clock/advance! clock 750)
        (should-not-be-nil @failed-at)
        (let [task-id (:reconnect-task-id @(:state client))
              task    (first (filter #(= task-id (:id %)) (scheduler/list-tasks (:scheduler clock))))]
          (should= 1500 (ms-between @failed-at (:next-fire-at task))))))

    (it "logs a failed attempt once, as a gateway warning with the exception class"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock #(> % 1))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        ((:on-close (:callbacks (first @dials))) {:status 1006 :reason "transport-error"})
        (test-clock/advance! clock 2000)
        (let [entries (log/get-entries)]
          (should-not-contain :scheduler/handler-error (set (map :event entries)))
          (should (some #(and (= :discord.gateway/reconnect-failed (:event %))
                              (= :warn (:level %))
                              (= "java.net.ConnectException" (:error-class %)))
                        entries)))))

    (it "still parks after the attempt budget"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock #(> % 1))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        ((:on-close (:callbacks (first @dials))) {:status 1006 :reason "transport-error"})
        (dotimes [_ 40] (test-clock/advance! clock 10000))
        (should= 9 (count @dials))
        (should (some #(= :discord.gateway/reconnect-exhausted (:event %)) (log/get-entries))))))

  (context "routine close logging"

    (it "an opcode 7 reconnect logs no warn or error"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (recording-connect! clock (constantly false))
            _     (client-with clock connect! :rand-fn (constantly 0.5))]
        (ready! (first @dials))
        (log/clear-entries!)
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (frame! (second @dials) {:op 10 :d {:heartbeat_interval 45000}})
        (frame! (second @dials) {:op 0 :t "RESUMED" :s 8 :d nil})
        (should= [] (noisy-gateway-entries))
        (should (some #(and (= :discord.gateway/reconnect-requested (:event %)) (= :info (:level %)))
                      (log/get-entries)))))

    (it "a superseded polling reader exits without closing again"
      (let [clock  (test-clock/make)
            pairs  (atom [])
            conn!  (fn [_url _callbacks]
                     (let [p (ws/loopback-pair)] (swap! pairs conj p) (:client p)))
            client (client-with clock conn! :rand-fn (constantly 0.5))
            server #(:server (nth @pairs %))]
        (ws/ws-send! (server 0) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        (ws/ws-send! (server 0) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "s" :resume_gateway_url resume-url :user {:id "bot"}}}))
        (helper/await-condition #(sut/connected? client) 2000)
        (should (sut/connected? client))
        (log/clear-entries!)
        (ws/ws-send! (server 0) (json/generate-string {:op 7}))
        (helper/await-condition #(some (fn [e] (= :discord.gateway/disconnected (:event e))) (log/get-entries)) 2000)
        (test-clock/advance! clock 1000)
        (helper/await-condition #(= 2 (count @pairs)) 2000)
        ;; The old reader reports supersession after it receives the close sentinel.
        ;; Wait for that event before asserting it did not trigger a second close.
        (helper/await-condition #(some (fn [e] (= :discord.gateway/reader-superseded (:event e)))
                                       (log/get-entries)) 2000)
        (should (some #(= :discord.gateway/reader-superseded (:event %)) (log/get-entries)))
        (should= 1 (count (filter #(= :discord.gateway/disconnected (:event %)) (log/get-entries))))
        (should= [] (noisy-gateway-entries))))

    (it "stop logs no warn or error even when the socket is already closed"
      (let [clock  (test-clock/make)
            pair   (ws/loopback-pair)
            client (client-with clock (fn [_ _] (:client pair)))]
        (ws/ws-send! (:server pair) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        (helper/await-condition #(some? (:heartbeat-task-id @(:state client))) 2000)
        (should (some? (:heartbeat-task-id @(:state client))))
        (log/clear-entries!)
        (sut/stop! client)
        (helper/await-condition #(some (fn [e] (= :discord.gateway/reader-superseded (:event e)))
                                       (log/get-entries)) 2000)
        (should (some #(= :discord.gateway/reader-superseded (:event %)) (log/get-entries)))
        (should= [] (noisy-gateway-entries))))))
