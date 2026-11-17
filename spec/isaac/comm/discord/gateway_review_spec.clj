(ns isaac.comm.discord.gateway-review-spec
  "Reviewer attacks on the discord-resume patch (zane-lab, 2026-11-17).
   Each example asserts the behaviour the docs or a safe client require;
   a failure is a finding, not a broken test."
  (:require
    [cheshire.core :as json]
    [isaac.comm.discord.gateway :as sut]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.foundation.config.validation-lexicon]
    [isaac.foundation.logger :as log]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [speclj.core :refer :all]))

(def ^:private default-url "wss://gateway.discord.gg/?v=10&encoding=json")
(def ^:private resume-url "wss://gateway-us-east1-b.discord.gg")

(defn- fake-connect!
  "Records every dial. `behave` gets [n dial] and may return :fail (throw),
   a fn to run before returning the transport, or nil. `on-send` gets
   [dial payload] after each send."
  [clock & {:keys [behave on-send] :or {behave (constantly nil) on-send (fn [_ _])}}]
  (let [dials (atom [])]
    {:dials dials
     :connect!
     (fn [url callbacks]
       (let [n    (inc (count @dials))
             dial {:n n :url url :at @(:now* clock) :callbacks callbacks
                   :sent (atom []) :closed-with (atom nil)}
             b    (behave n dial)]
         (swap! dials conj dial)
         (when (= :fail b) (throw (java.net.ConnectException. "Connection refused")))
         (when (fn? b) (b))
         {:callback-driven? true
          :send!            (fn [payload] (swap! (:sent dial) conj payload) (on-send dial payload))
          :close-with-code! (fn [code] (reset! (:closed-with dial) code))}))}))

(defn- frame! [dial message]
  ((:on-message (:callbacks dial)) (json/generate-string message)))

(defn- ready! [dial]
  (frame! dial {:op 10 :d {:heartbeat_interval 45000}})
  (frame! dial {:op 0 :t "READY" :s 7 :d {:session_id "sess-1" :resume_gateway_url resume-url :user {:id "bot"}}}))

(defn- client-with [clock connect! & {:as opts}]
  (sut/connect! (merge {:token "test-token" :url default-url :scheduler (:scheduler clock)
                        :connect-ws! connect! :rand-fn (constantly 0.5)}
                       opts)))

(defn- reconnect-tasks [clock]
  (->> (scheduler/list-tasks (:scheduler clock))
       (filter #(re-find #"reconnect" (str (:id %))))))

(defn- ops [dial] (mapv :op @(:sent dial)))

(describe "discord-resume patch: reviewer attacks"

  (before (log/set-output! :memory) (log/clear-entries!))

  (context "stop"

    (it "R1 stop during a pending reconnect leaves no timer and dials nothing"
      (let [clock  (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock)
            client (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (should= 1 (count (reconnect-tasks clock)))
        (sut/stop! client)
        (should= 0 (count (reconnect-tasks clock)))
        (dotimes [_ 60] (test-clock/advance! clock 1000))
        (should= 1 (count @dials))))

    (it "R1b stop during the op 9 d=false 1-5 s wait dials nothing"
      (let [clock  (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock)
            client (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 9 :d false})
        (sut/stop! client)
        (dotimes [_ 10] (test-clock/advance! clock 1000))
        (should= 1 (count @dials))))

    (it "R2 stop while an attempt is failing leaves no reconnect timer behind"
      (let [clock   (test-clock/make)
            client* (atom nil)
            {:keys [dials connect!]} (fake-connect! clock
                                                    :behave (fn [n _] (when (= 2 n) (sut/stop! @client*) :fail)))
            client  (client-with clock connect!)]
        (reset! client* client)
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= 2 (count @dials))
        (should= [] (mapv :id (reconnect-tasks clock)))
        (dotimes [_ 60] (test-clock/advance! clock 1000))
        (should= 2 (count @dials))))

    (it "R3 stop while an attempt is connecting does not install the new socket or send RESUME"
      (let [clock   (test-clock/make)
            client* (atom nil)
            {:keys [dials connect!]} (fake-connect! clock
                                                    :behave (fn [n _] (when (= 2 n) #(sut/stop! @client*))))
            client  (client-with clock connect!)]
        (reset! client* client)
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= 2 (count @dials))
        (should= [] (ops (second @dials)))
        (should-be-nil (:transport @(:state client)))
        (should= 1000 @(:closed-with (second @dials))))))

  (context "frames that arrive while an attempt is in flight"

    (it "R4 op 9 d=false answering the RESUME before the attempt returns still re-identifies"
      (let [clock (test-clock/make)
            {:keys [dials connect!]}
            (fake-connect! clock :on-send (fn [dial payload]
                                            (when (and (= 2 (:n dial)) (= 6 (:op payload)))
                                              (frame! dial {:op 9 :d false}))))
            client (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (dotimes [_ 30] (test-clock/advance! clock 1000))
        (should= 3 (count @dials))
        (should= [2] (ops (nth @dials 2)))
        (should-not= :resuming (:status @(:state client)))))

    (it "R5 op 7 on the new socket before the attempt returns reconnects again, one live socket at a time"
      (let [clock (test-clock/make)
            {:keys [dials connect!]}
            (fake-connect! clock :on-send (fn [dial payload]
                                            (when (and (= 2 (:n dial)) (= 6 (:op payload)))
                                              (frame! dial {:op 7}))))
            _     (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (dotimes [_ 30] (test-clock/advance! clock 1000))
        (should= 3 (count @dials))
        (should= 1 (count (remove #(deref (:closed-with %)) @dials))))))

  (context "resume details"

    (it "R6 op 9 d=true resumes the same session on the resume URL, no IDENTIFY"
      (let [clock  (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock)
            client (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 0 :t "TYPING_START" :s 9 :d {}})
        (frame! (first @dials) {:op 9 :d true})
        (should= 4000 @(:closed-with (first @dials)))
        (test-clock/advance! clock 1000)
        (should= 2 (count @dials))
        (should= (str resume-url "/?v=10&encoding=json") (:url (second @dials)))
        (should= [{:op 6 :d {:token "test-token" :session_id "sess-1" :seq 9}}] @(:sent (second @dials)))
        (should= "sess-1" (:session-id @(:state client)))))

    (it "R7 the sequence carries across resumes, and the resume URL survives RESUMED"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock)
            _     (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 0 :t "TYPING_START" :s 12 :d {}})
        (frame! (first @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= 12 (get-in (first @(:sent (second @dials))) [:d :seq]))
        (frame! (second @dials) {:op 10 :d {:heartbeat_interval 45000}})
        (frame! (second @dials) {:op 0 :t "TYPING_START" :s 13 :d {}})
        (frame! (second @dials) {:op 0 :t "RESUMED" :s 14 :d nil})
        (frame! (second @dials) {:op 7})
        (test-clock/advance! clock 1000)
        (should= 3 (count @dials))
        (should= (str resume-url "/?v=10&encoding=json") (:url (nth @dials 2)))
        (should= {:op 6 :d {:token "test-token" :session_id "sess-1" :seq 14}} (first @(:sent (nth @dials 2))))))

    (it "R8 a missed heartbeat ACK closes with a non-1000 code and resumes (docs: Sending Heartbeats)"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock)
            _     (client-with clock connect!)]
        (ready! (first @dials))
        (test-clock/advance! clock 45000)
        (test-clock/advance! clock 45000)
        (should-not-be-nil @(:closed-with (first @dials)))
        (should-not= 1000 @(:closed-with (first @dials)))
        (test-clock/advance! clock 2000)
        (should= 6 (:op (first @(:sent (second @dials)))))))

    (it "R9 when the resume URL cannot be reached, a later attempt falls back to the default URL"
      (let [clock (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock :behave (fn [n _] (when (<= 2 n 3) :fail)))
            _     (client-with clock connect!)]
        (ready! (first @dials))
        (frame! (first @dials) {:op 7})
        (dotimes [_ 30] (test-clock/advance! clock 1000))
        (should= [(str resume-url "/?v=10&encoding=json") (str resume-url "/?v=10&encoding=json") default-url]
                 (mapv :url (take 3 (drop 1 @dials))))
        (should= [2] (ops (nth @dials 3))))))

  (context "close-code transport"

    (it "R11 isaac-agent's RealWs still has the websocket field the 4000 close needs"
      (should (sut/real-ws-close-code-supported?))))

  (context "attempt budget"

    (it "R10 parks after 8 failed attempts, and how long an outage that takes"
      (let [clock  (test-clock/make)
            {:keys [dials connect!]} (fake-connect! clock :behave (fn [n _] (when (> n 1) :fail)))
            client (client-with clock connect! :rand-fn (constantly 0.9999))]
        (ready! (first @dials))
        ((:on-close (:callbacks (first @dials))) {:status 1006 :reason "transport-error"})
        (dotimes [_ 300] (test-clock/advance! clock 1000))
        (should= 9 (count @dials))
        (should (:reconnect-exhausted? @(:state client)))
        (should= [] (reconnect-tasks clock))
        (let [budget-ms (- (.toEpochMilli (:at (last @dials))) (.toEpochMilli (:at (second @dials))))]
          (should (< budget-ms 120000)))
        (sut/check-liveness! client)
        (dotimes [_ 60] (test-clock/advance! clock 1000))
        (should= 9 (count @dials))))))
