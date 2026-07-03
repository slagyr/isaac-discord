(ns isaac.comm.discord.gateway-spec
  (:require
    [cheshire.core :as json]
    [isaac.comm.discord.gateway :as sut]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.logger :as log]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.util.ws-client :as ws]
    [speclj.core :refer :all]))

(defn- fake-connect! [sent callbacks*]
  (fn [_url callbacks]
    (reset! callbacks* callbacks)
    {:callback-driven? true
     :close!           (fn [] nil)
     :send!            (fn [payload] (swap! sent conj payload))}))

(defn- log-events []
  (set (map :event (log/get-entries))))

(defn- close-and-reconnect-logged? []
  (let [events (log-events)]
    (and (some events #{:discord.gateway/disconnected
                         :discord.gateway/heartbeat-ack-timeout
                         :discord.gateway/reconnect-requested
                         :discord.gateway/invalid-session
                         :discord.gateway/heartbeat-failed
                         :discord.gateway/heartbeat-cancelled})
         (contains? events :discord.gateway/reconnect-attempt))))

(describe "Discord gateway"

  (before
    (log/set-output! :memory)
    (log/clear-entries!))

  (it "sends IDENTIFY after receiving HELLO"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (should= 2 (:op (first @sent)))
      (should= "test-token" (get-in (first @sent) [:d :token]))
      (should= 37377 (get-in (first @sent) [:d :intents]))))

  (it "cancels the prior heartbeat task when receiving a second HELLO"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          sch        (:scheduler clock)
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   sch
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (let [first-task-id (:id (first (scheduler/list-tasks sch)))]
        (should= 1 (count (scheduler/list-tasks sch)))
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        (should= 1 (count (scheduler/list-tasks sch)))
        (should-not= first-task-id (:id (first (scheduler/list-tasks sch))))
        (test-clock/advance! clock 45000)
        (should= 1 (count (filter #(= 1 (:op %)) @sent))))))

  (it "keeps a single heartbeat task after reconnect"
    (let [sent*      (atom [])
          callbacks* (atom [])
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                      :scheduler   sch
                                      :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status-code 1006 :reason ""})
      (test-clock/advance! clock 1000)
      ((:on-message (second @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (should= 1 (count (scheduler/list-tasks sch)))
      (test-clock/advance! clock 45000)
      (should= 1 (count (filter #(= 1 (:op %)) @sent*)))))

  (it "sends HEARTBEAT when virtual time advances past the interval"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   (:scheduler clock)
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (test-clock/advance! clock 45000)
      (should= 1 (:op (second @sent)))
      (should= nil (get-in (second @sent) [:d]))))

  (it "marks the client connected after READY"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 42 :d {:session_id "abc"}}))
      (should (sut/connected? client))
      (should= 42 (sut/current-sequence client))))

  (it "heartbeats with the latest sequence after dispatch events"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   (:scheduler clock)
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc"}}))
      (test-clock/advance! clock 45000)
      (should= 1 (:op (nth @sent 1)))
      (should= 7 (get-in (nth @sent 1) [:d]))))

  (it "logs malformed frames as errors"
    (let [sent       (atom [])
          callbacks* (atom nil)
          _client    (sut/connect! {:token       "test-token"
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) "not-json")
      (should= :discord.gateway/invalid-frame (:event (last (log/get-entries))))))

  (it "reconnects and re-identifies after a normal close"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status 1000 :reason "bye"})
      (should (sut/running? client))
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should= :discord.gateway/identify (:event (last (log/get-entries))))))

  (it "reconnects and re-identifies after abnormal close code 1006"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status-code 1006 :reason ""})
      (should (sut/running? client))
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))
      (should= :discord.gateway/identify (:event (last (log/get-entries))))))

  (it "reconnects and sends RESUME for a resumable close code"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status 4000 :reason "bye"})
      (should= 2 (count @callbacks*))
      (should= 6 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))
      (should= "abc" (get-in (last @sent*) [:d :session_id]))
      (should= 7 (get-in (last @sent*) [:d :seq]))))

  (it "reconnects via scheduler and sends RESUME with token"
    (let [sent*      (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          connect!   (fn [_url callbacks]
                       (reset! callbacks* callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send-payload!    (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   (:scheduler clock)
                                    :connect-ws! connect!})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "fake-session" :user {:id "bot-default"}}}))
      ((:on-close @callbacks*) {:status 4000 :reason "test-close"})
      (test-clock/advance! clock 1000)
      (should= 6 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))))

  (it "reconnects and sends IDENTIFY for a non-resumable close code"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status 4009 :reason "session timeout"})
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))))

  (it "cancels heartbeat on disconnect while reconnect is pending"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (if (nil? @callbacks*)
                         (do (reset! callbacks* callbacks)
                             {:callback-driven? true
                        :close!           (fn [] nil)
                              :send!  (fn [payload] (swap! sent conj payload))})
                         (throw (ex-info "network down" {}))))
          client     (sut/connect! {:token                "test-token"
                                    :scheduler            sch
                                    :reconnect-delay-ms   1000
                                    :connect-ws!          connect!})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
      (should= 1 (count (scheduler/list-tasks sch)))
      ((:on-close @callbacks*) {:reason "closed"})
      (should= 1 (count (scheduler/list-tasks sch)))
      (should= :discord.gateway/reconnect (:id (first (scheduler/list-tasks sch))))
      (test-clock/advance! clock 45000)
      (should= 0 (count (filter #(= 1 (:op %)) @sent)))))

  (it "cancels the heartbeat when a beat fails into a dead socket"
    (let [sent       (atom [])
          fail?      (atom false)
          callbacks* (atom nil)
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (if (nil? @callbacks*)
                         (do (reset! callbacks* callbacks)
                             {:callback-driven? true
                        :close!           (fn [] nil)
                              :send!  (fn [payload]
                                        (if @fail?
                                          (throw (java.io.IOException. "Output closed"))
                                          (swap! sent conj payload)))})
                         (throw (ex-info "network down" {}))))
          client     (sut/connect! {:token              "test-token"
                                    :scheduler          sch
                                    :reconnect-delay-ms 1000
                                    :connect-ws!        connect!})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
      (should= 1 (count (scheduler/list-tasks sch)))
      ;; the socket goes dead; the next heartbeat send fails
      (reset! fail? true)
      (test-clock/advance! clock 45000)
      ;; the failed beat is treated as a disconnect: heartbeat cancelled, only
      ;; the reconnect task remains — no more hammering the dead socket.
      (should= [:discord.gateway/reconnect] (mapv :id (scheduler/list-tasks sch)))
      (let [beats-before (count (filter #(= 1 (:op %)) @sent))]
        (test-clock/advance! clock 45000)
        (test-clock/advance! clock 45000)
        (should= beats-before (count (filter #(= 1 (:op %)) @sent))))))

  (it "retries reconnect with backoff until connect succeeds"
    (let [attempts*  (atom 0)
          sent*      (atom [])
          callbacks* (atom [])
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (swap! attempts* inc)
                       (if (and (> @attempts* 1) (<= @attempts* 3))
                         (throw (ex-info "network down" {}))
                         (do (swap! callbacks* conj callbacks)
                             {:callback-driven? true
                        :close!           (fn [] nil)
                              :send!  (fn [payload] (swap! sent* conj payload))})))
          client     (sut/connect! {:token                  "test-token"
                                    :scheduler              sch
                                    :reconnect-delay-ms     10
                                    :reconnect-max-delay-ms 40
                                    :connect-ws!            connect!})]
      (should= 1 @attempts*)
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
      ((:on-close (first @callbacks*)) {:reason "closed"})
      (test-clock/advance! clock 10)
      (should= 2 @attempts*)
      (test-clock/advance! clock 20)
      (should= 3 @attempts*)
      (test-clock/advance! clock 40)
      (should= 4 @attempts*)
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should (sut/running? client))
      (should= :discord.gateway/reconnect-attempt
               (:event (last (filter #(= :discord.gateway/reconnect-attempt (:event %)) (log/get-entries)))))))

  (it "logs and reconnects on Discord opcode 7 (Reconnect)"
    (let [sent*      (atom [])
          callbacks* (atom [])
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   sch
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot"}}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 7}))
      (test-clock/advance! clock 1000)
      (should (close-and-reconnect-logged?))
      (should (contains? (log-events) :discord.gateway/reconnect-requested))
      (should (sut/running? client))))

  (it "reconnects after opcode 7 without a duplicate auth when the new HELLO arrives"
    (let [sent*      (atom [])
          callbacks* (atom [])
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   sch
                                    :connect-ws! connect!})
          auth?      #(contains? #{2 6} (:op %))]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot"}}}))
      (let [auth-before (count (filter auth? @sent*))]
        ;; opcode 7 → reconnect → the eager RESUME on the new socket, then Discord's
        ;; HELLO on the reconnected socket. The old bug re-sent IDENTIFY on that HELLO.
        ((:on-message (first @callbacks*)) (json/generate-string {:op 7}))
        (test-clock/advance! clock 1000)
        (should= 2 (count @callbacks*))
        ((:on-message (second @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ;; exactly one auth sent for the reconnect, and it is the RESUME, not a duplicate IDENTIFY
        (should= 1 (- (count (filter auth? @sent*)) auth-before))
        (should= 6 (:op (last (filter auth? @sent*))))
        (should (sut/running? client))
        ;; heartbeats resume on the reconnected socket
        (let [beats-before (count (filter #(= 1 (:op %)) @sent*))]
          (test-clock/advance! clock 45000)
          (should (> (count (filter #(= 1 (:op %)) @sent*)) beats-before))))))

  (it "logs and reconnects with RESUME on opcode 9 when session is resumable"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          _client    (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot"}}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 9 :d true}))
      (should (close-and-reconnect-logged?))
      (should (contains? (log-events) :discord.gateway/invalid-session))
      (should= 6 (:op (last @sent*)))))

  (it "logs and reconnects with IDENTIFY on opcode 9 when session is not resumable"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          _client    (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot"}}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 9 :d false}))
      (should (close-and-reconnect-logged?))
      (should (contains? (log-events) :discord.gateway/invalid-session))
      (should= 2 (:op (last @sent*)))))

  (it "detects a missed heartbeat ack and never goes silent"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          sch        (:scheduler clock)
          connect!   (fn [_url callbacks]
                       (if (nil? @callbacks*)
                         (do (reset! callbacks* callbacks)
                             {:callback-driven? true
                        :close!           (fn [] nil)
                              :send!  (fn [payload] (swap! sent conj payload))})
                         (do (reset! callbacks* callbacks)
                             {:callback-driven? true
                        :close!           (fn [] nil)
                              :send!  (fn [payload] (swap! sent conj payload))})))
          client     (sut/connect! {:token              "test-token"
                                    :scheduler          sch
                                    :reconnect-delay-ms 1000
                                    :connect-ws!        connect!})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot"}}}))
      (test-clock/advance! clock 45000)
      (should= 1 (count (filter #(= 1 (:op %)) @sent)))
      (test-clock/advance! clock 45000)
      (should (contains? (log-events) :discord.gateway/heartbeat-ack-timeout))
      (should (contains? (log-events) :discord.gateway/heartbeat-cancelled))
      (test-clock/advance! clock 1000)
      (should (contains? (log-events) :discord.gateway/reconnect-attempt))
      (should (sut/running? client))))

  (it "emits periodic liveness logs from the heartbeat task"
    (let [sent       (atom [])
          callbacks* (atom nil)
          clock      (test-clock/make)
          client     (sut/connect! {:token       "test-token"
                                    :scheduler   (:scheduler clock)
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc"}}))
      ((:on-message @callbacks*) (json/generate-string {:op 11}))
      (test-clock/advance! clock 45000)
      (should (contains? (log-events) :discord.gateway/liveness))))

  (it "logs fatal close codes without reconnecting"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          _client    (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status 4004 :reason "bad token"})
      (should= 1 (count @callbacks*))
      (should= :discord.gateway/fatal-close (:event (last (log/get-entries))))))

  (it "routes resumable close when payload uses :status-code key (ws-close-payload path)"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "sess" :user {:id "bot"}}}))
      ((:on-close (first @callbacks*)) {:status-code 4000 :reason "stale"})
      (should= 2 (count @callbacks*))
      (should= 6 (:op (last @sent*)))))

  (it "routes fatal close when payload uses :status-code key (ws-close-payload path)"
    (let [callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil) :send! (fn [_] nil)})
          _client    (sut/connect! {:token       "test-token"
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status-code 4004 :reason "bad token"})
      (should= 1 (count @callbacks*))
      (should= :discord.gateway/fatal-close (:event (last (log/get-entries))))))

  (it "logs structured gateway errors from callback-driven transports"
    (let [callbacks* (atom nil)
          connect!   (fn [_url callbacks]
                       (reset! callbacks* callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!  (fn [_] nil)})
          error      (ex-info "connection reset" {:socket :discord})]
      (log/capture-logs
        (sut/connect! {:token       "test-token"
                       :connect-ws! connect!})
        ((:on-error @callbacks*) error)
        (should= [{:level         :error
                   :event         :discord.gateway/error
                   :ex-class      "ExceptionInfo"
                   :error-message "connection reset"
                   :payload       {:message "connection reset"
                                   :class   "clojure.lang.ExceptionInfo"
                                   :data    {:socket :discord}}}]
                 (->> @log/captured-logs
                      (filter #(= :discord.gateway/error (:event %)))
                      (mapv #(select-keys % [:level :event :ex-class :error-message :payload])))))))

  (it "treats polling transport error maps as structured gateway errors"
    (let [messages*  (atom [{:error (ex-info "boom" {:source :socket})} nil])
          transport  {:close! (fn [] nil)
                      :send!  (fn [_] nil)}
          connect!   (fn [_url _callbacks] transport)]
      (with-redefs [sut/transport-receive! (fn [_]
                                             (let [message (first @messages*)]
                                               (swap! messages* rest)
                                               message))
                    ws/ws-close-payload    (fn [_] {:status-code 4000 :reason "resume"})]
        (log/capture-logs
          (let [client (sut/connect! {:token       "test-token"
                                      :connect-ws! connect!})]
            (loop [n 0]
              (when (and (< n 1000) (nil? (:disconnect @(:state client))))
                (Thread/sleep 1)
                (recur (inc n))))
            (should= {:status-code 4000 :reason "resume"}
                     (:disconnect @(:state client)))
            (let [entries (->> @log/captured-logs
                               (filter #(contains? #{:discord.gateway/error :discord.gateway/disconnected} (:event %)))
                               (take 2)
                               (mapv #(select-keys % [:event :payload])))]
              (should= #{:discord.gateway/error :discord.gateway/disconnected}
                       (set (map :event entries)))
              (should (some #(= {:event :discord.gateway/error
                                 :payload {:message "boom"
                                           :class   "clojure.lang.ExceptionInfo"
                                           :data    {:source :socket}}}
                                %)
                            entries))
              (should (some #(= {:event :discord.gateway/disconnected
                                 :payload {:reason "transport-error" :status 1006}}
                                %)
                            entries)))
            (sut/stop! client))))))

  (describe "message intake"

    (it "accepts MESSAGE_CREATE from an allowed user and guild"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :allow-from-users ["123456"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "123456"} :content "hello"}}))
        (should= [{:channel_id "999001" :guild_id "789012" :author {:id "123456"} :content "hello"}] (sut/accepted-messages client))))

    (it "accepts guild post from any author when the guild is allowlisted"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token             "test-token"
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "any-user"} :content "hello"}}))
        (should= 1 (count (sut/accepted-messages client)))))

    (it "accepts DM when the author is allowlisted"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :allow-from-users ["274692"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555001" :author {:id "274692"} :content "dm hi"}}))
        (should= 1 (count (sut/accepted-messages client)))))

    (it "ignores MESSAGE_CREATE from a guild not on the allow list"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :allow-from-users ["123456"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "888888" :author {:id "123456"} :content "hello"}}))
        (should= [] (sut/accepted-messages client))))

    (it "ignores the bot's own MESSAGE_CREATE events"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :allow-from-users ["555"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "555"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "555"} :content "echo"}}))
        (should= [] (sut/accepted-messages client))))

    (it "logs :guild rejection when guild post is from a non-allowlisted guild"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token             "test-token"
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "888888" :author {:id "123"} :content "hi"}}))
        (let [entry (some #(when (= :discord.gateway/message-rejected (:event %)) %)
                        (log/get-entries))]
          (should= :guild (:reason entry)))))

    (it "logs :user rejection when DM is from a non-allowlisted user"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token            "test-token"
                                      :allow-from-users ["274692"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555001" :author {:id "999999"} :content "spam"}}))
        (let [entry (some #(when (= :discord.gateway/message-rejected (:event %)) %)
                        (log/get-entries))]
          (should= :user (:reason entry)))))

    (it "logs :self rejection when bot receives its own message"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token             "test-token"
                                      :allow-from-guilds ["789012"]
                                      :allow-from-users  ["555"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "555"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "555"} :content "echo"}}))
        (let [entry (some #(when (= :discord.gateway/message-rejected (:event %)) %)
                        (log/get-entries))]
          (should= :self (:reason entry)))))

    (it "update-allow-from! applies new filter without reconnecting"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token             "test-token"
                                      :allow-from-users  ["123"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        (sut/update-allow-from! client {:allow-from-users ["123" "456"]})
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555" :author {:id "456"} :content "hi"}}))
        (should= 1 (count (sut/accepted-messages client)))))

    (it "recovers from opcode-7 reconnect wave + reader Output closed after READY (reschedules heartbeats, reaches READY, accepts messages)"
      (let [sent*      (atom [])
            callbacks* (atom [])
            clock      (test-clock/make)
            sch        (:scheduler clock)
            connect!   (fn [_url callbacks]
                         (swap! callbacks* conj callbacks)
                         {:callback-driven? true
                          :close!           (fn [] nil)
                          :send!            (fn [payload] (swap! sent* conj payload))})
            client     (sut/connect! {:token            "test-token"
                                      :scheduler        sch
                                      :allow-from-users ["999"]
                                      :connect-ws!      connect!})]
        ;; initial connect to READY
        ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
        (should (sut/connected? client))
        (should= 1 (count (scheduler/list-tasks sch))) ;; heartbeat task

        ;; opcode 7 wave (triggers on-close + schedule reconnect)
        ((:on-message (first @callbacks*)) (json/generate-string {:op 7}))
        (test-clock/advance! clock 1000)
        (should= 2 (count @callbacks*))

        ;; simulate reader "Output closed" (CompletionException style) on the reconnected transport
        ((:on-close (second @callbacks*)) {:reason "reader-loop-failed" :status 1006})
        (test-clock/advance! clock 1000)
        (should= 3 (count @callbacks*))

        ;; simulate HELLO + READY on the recovered connection (the reconnect after reader failure)
        ((:on-message (last @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message (last @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 42 :d {:session_id "def" :user {:id "bot-default"}}}))
        (should (sut/connected? client))

        ;; heartbeat / liveness task rescheduled
        (should= 1 (count (scheduler/list-tasks sch)))

        ;; advance virtual time to fire a heartbeat after recovery
        (test-clock/advance! clock 45000)
        (let [heartbeats (filter #(= 1 (:op %)) @sent*)]
          (should (>= (count heartbeats) 1)))

        ;; after recovery, a simulated MESSAGE_CREATE is accepted (proves reader + heartbeats alive)
        ((:on-message (last @callbacks*)) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 43 :d {:channel_id "123" :author {:id "999"} :content "test"}}))
        (should= 1 (count (sut/accepted-messages client)))

        ;; no "task already scheduled" exceptions lost
        (should-not (some (fn [e] (and (= :discord.gateway/error (:event e))
                                       (re-find #"task already scheduled" (str (:payload e)))))
                          (log/get-entries)))))))
