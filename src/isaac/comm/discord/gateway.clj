(ns isaac.comm.discord.gateway
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.util.ws-client :as ws]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.scheduler.runtime :as scheduler]))

(def gateway-url "wss://gateway.discord.gg/?v=10&encoding=json")
(def intents 37377)

(def ^:private resumable-close-codes #{4000 4001 4002 4003 4008})
(def ^:private reidentify-close-codes #{1000 1001 1006 4007 4009})
(def ^:private reconnect-task-id :discord.gateway/reconnect)
(def ^:private default-reconnect-delay-ms 1000)
(def ^:private default-reconnect-max-delay-ms 30000)
(def ^:private max-reconnect-attempts 8)
(def ^:private normal-close-code 1000)
;; Discord invalidates the session on a 1000/1001 close; any other code keeps
;; it resumable (docs: gateway "Initiating a Disconnect").
(def ^:private resume-close-code 4000)
(def ^:private invalid-session-min-wait-ms 1000)
(def ^:private invalid-session-max-wait-ms 5000)
(def ^:private resume-dial-attempts 2)

(defn- normalize-id-set [values]
  (->> (cond
         (nil? values)        []
         (sequential? values) values
         :else                [values])
       (map str)
       set))

(defn- client-scheduler [client]
  (or (:scheduler client) (nexus/get :scheduler)))

(defn- now-ms [client]
  (if-let [clock (:clock (client-scheduler client))]
    (.toEpochMilli ^java.time.Instant (clock))
    (System/currentTimeMillis)))

(defn- default-connect-ws! [url _handlers]
  (ws/connect! url))

(defn- transport-send! [transport payload]
  (cond
    (:send-payload! transport) ((:send-payload! transport) payload)
    (:send! transport)         ((:send! transport) payload)
    (:send-text! transport)    ((:send-text! transport) (json/generate-string payload))
    :else                      (ws/ws-send! transport (json/generate-string payload))))

;; isaac-agent's RealWs only closes with 1000. Resolve the field at load so
;; an agent upgrade that renames it fails loudly, not on an op 7 in production.
(def ^:private real-ws-websocket-field
  (when (instance? Class isaac.agent.util.ws_client.RealWs)
    (.getField ^Class isaac.agent.util.ws_client.RealWs "websocket")))

(defn real-ws-close-code-supported? []
  (some? real-ws-websocket-field))

(defn- close-real-ws-with-code! [transport code]
  (let [^java.net.http.WebSocket websocket (.get ^java.lang.reflect.Field real-ws-websocket-field transport)]
    (.sendClose websocket (int code) "reconnect")
    nil))

(defn- transport-close!
  ([transport] (transport-close! transport normal-close-code))
  ([transport code]
   (cond
     (:close-with-code! transport) ((:close-with-code! transport) code)
     (:close! transport)           ((:close! transport))
     (and (not= normal-close-code code)
          (instance? isaac.agent.util.ws_client.RealWs transport))
     (close-real-ws-with-code! transport code)
     :else                         (ws/ws-close! transport))))

(defn- transport-receive! [transport]
  (when-not (:callback-driven? transport)
    (ws/ws-receive! transport 100)))

(defn- identify-payload [token]
  {:op 2
   :d  {:token      token
        :intents    intents
        :properties {:os      (System/getProperty "os.name")
                     :browser "isaac"
                     :device  "isaac"}}})

(defn- heartbeat-payload [sequence]
  {:op 1 :d sequence})

(defn- resume-payload [client]
  {:op 6
   :d  {:token      (:token client)
        :session_id (:session-id @(:state client))
        :seq        (:sequence @(:state client))}})

(declare start-reader-loop! stop! on-close! attempt-reconnect!
         reconnect-mode-for-close close-status ensure-recovery!)

(defn- mark-auth-sent!
  "Records the auth status unless a frame handled during the send (op 7/9)
   already closed this connection."
  [client status]
  (swap! (:state client) (fn [s] (if (:transport s) (assoc s :status status :auth-sent? true) s))))

(defn- send-identify! [client]
  (let [payload (identify-payload (:token client))]
    (transport-send! (:transport @(:state client)) payload)
    (mark-auth-sent! client :identified)
    (log/info :discord.gateway/identify :intents intents)))

(defn- send-resume! [client]
  (let [payload (resume-payload client)]
    (transport-send! (:transport @(:state client)) payload)
    (mark-auth-sent! client :resuming)
    (log/info :discord.gateway/resume :seq (get-in payload [:d :seq]) :session-id (get-in payload [:d :session_id]))))

(defn- heartbeat-ack-missing? [state]
  (true? (:heartbeat-ack-pending? state)))

(defn- reconnect-pending? [state]
  (boolean (or (:reconnect-task-id state) (:reconnect-in-flight? state))))

(defn- ensure-recovery!
  "When the client is still marked running but not READY and no reconnect is
   pending, schedule a reconnect. Catches cases where on-close! failed to
   register a recovery task (reader-loop-failed storms, scheduler races)."
  [client]
  (let [state @(:state client)]
    (when (and (:running? state)
               (= :disconnected (:status state))
               (some? (:disconnect state))
               (not (reconnect-pending? state))
               (not (:reconnect-exhausted? state)))
      (log/warn :discord.gateway/stale-not-recovering
                :status (:status state)
                :reason (get-in state [:disconnect :reason])
                :forcing-reconnect true)
      (attempt-reconnect! client (or (get-in state [:disconnect :mode])
                                     (reconnect-mode-for-close (close-status (:disconnect state)))
                                     :identify)))))

(defn- heartbeat-rtt-ms [state]
  (let [sent-at (:last-heartbeat-sent-at-ms state)
        ack-at  (:last-heartbeat-ack-at-ms state)]
    (when (and sent-at ack-at (>= ack-at sent-at))
      (- ack-at sent-at))))

(defn- log-liveness! [client]
  (let [state      @(:state client)
        interval   (:heartbeat-interval-ms state)
        ack-at     (:last-heartbeat-ack-at-ms state)
        now        (now-ms client)
        ack-age-ms (when ack-at (- now ack-at))
        rtt-ms     (heartbeat-rtt-ms state)
        sequence   (:last-heartbeat-acked-sequence state)]
    (ensure-recovery! client)
    (if (and ack-age-ms interval (> ack-age-ms (* 2 interval)))
      (log/warn :discord.gateway/liveness-stale
                :last-ack-ms-ago ack-age-ms
                :heartbeat-interval-ms interval)
      (log/info :discord.gateway/liveness
                (cond-> {:last-ack-ms-ago (or ack-age-ms "never")
                         :status          (:status state)}
                  sequence (assoc :sequence sequence)
                  (some? rtt-ms) (assoc :rtt-ms rtt-ms))))))

(defn- send-heartbeat! [client]
  (when (heartbeat-ack-missing? @(:state client))
    (log/warn :discord.gateway/heartbeat-ack-timeout
              :sent-sequence (:last-heartbeat-sent-sequence @(:state client)))
    ;; Docs (Sending Heartbeats): a zombied connection is closed with a code
    ;; other than 1000/1001, then the app resumes.
    (on-close! client {:reason "heartbeat-ack-timeout" :status 1006
                       :mode   (if (:session-id @(:state client)) :resume :identify)}))
  (when (and (:running? @(:state client))
             (not (heartbeat-ack-missing? @(:state client))))
    (let [seq     (:sequence @(:state client))
          payload (heartbeat-payload seq)]
      (transport-send! (:transport @(:state client)) payload)
      (swap! (:state client) assoc
             :last-heartbeat-sent-sequence seq
             :last-heartbeat-sent-at-ms (now-ms client)
             :heartbeat-ack-pending? true))))

(defn- cancel-heartbeat! [client & [{:keys [reason]}]]
  (when (:heartbeat-task-id @(:state client))
    (log/debug :discord.gateway/heartbeat-cancelled :reason (or reason "unspecified")))
  (when-let [task-id (:heartbeat-task-id @(:state client))]
    (when-let [task-sch (:heartbeat-scheduler @(:state client))]
      (scheduler/cancel! task-sch task-id))
    (swap! (:state client) dissoc :heartbeat-task-id :heartbeat-scheduler)))

(defn- schedule-heartbeats! [client interval-ms]
  (when-let [sch (or (:scheduler client) (nexus/get :scheduler))]
    (cancel-heartbeat! client {:reason "reschedule"})
    (let [id (scheduler/every! sch interval-ms
                               (fn [_]
                                 (when (:running? @(:state client))
                                   (log-liveness! client)
                                   (try
                                     (send-heartbeat! client)
                                     (catch Exception e
                                       (log/ex :discord.gateway/heartbeat-failed e)
                                       (on-close! client {:reason "heartbeat-send-failed"}))))))]
      (swap! (:state client) assoc :heartbeat-task-id id :heartbeat-scheduler sch))))

(defn- reconnect-mode-for-close [status]
  (if (contains? resumable-close-codes status)
    :resume
    :identify))

(defn- handle-hello! [client data]
  (let [interval-ms (:heartbeat_interval data)]
    (swap! (:state client) assoc
           :status :hello-received
           :heartbeat-interval-ms interval-ms
           :last-heartbeat-sent-sequence nil
           :last-heartbeat-sent-at-ms nil
           :last-heartbeat-acked-sequence nil
           :last-heartbeat-ack-at-ms nil
           :heartbeat-ack-pending? false)
    (log/info :discord.gateway/hello :heartbeat-interval-ms interval-ms)
    (schedule-heartbeats! client interval-ms)
    ;; On a fresh connect, HELLO drives the single IDENTIFY. On a reconnect the
    ;; auth (IDENTIFY or RESUME) was already sent eagerly in do-reconnect!, so the
    ;; reconnected socket's HELLO must NOT auth again — Discord rejects the second
    ;; ("Already authenticated"), killing heartbeats until a full restart (isaac-ceeq).
    (when-not (:auth-sent? @(:state client))
      (send-identify! client))))

(defn- handle-dispatch! [client message]
  (when-let [sequence (:s message)]
    (swap! (:state client) assoc :sequence sequence))
  (case (:t message)
    "READY"
    (do
      (swap! (:state client) assoc
             :status                :ready
             :session-id            (get-in message [:d :session_id])
             :resume-gateway-url    (get-in message [:d :resume_gateway_url])
             :bot-id                (some-> (get-in message [:d :user :id]) str)
             :reconnect-attempts    0
             :reconnect-exhausted?  false)
      (log/info :discord.gateway/ready :session-id (get-in message [:d :session_id])))

    "MESSAGE_CREATE"
    (let [payload    (:d message)
          author-id  (some-> (get-in payload [:author :id]) str)
          guild-id   (some-> (:guild_id payload) str)
          channel-id (:channel_id payload)
          bot-id     (:bot-id @(:state client))
          accept!    (fn []
                       (swap! (:state client) update :accepted conj payload)
                       (log/debug :discord.gateway/message-accepted
                                  :authorId author-id
                                  :channelId channel-id
                                  :guildId guild-id
                                  :content (subs (str (:content payload)) 0 (min 80 (count (str (:content payload))))))
                       (when-let [on-accepted-message! (:on-accepted-message! client)]
                         (try
                           (on-accepted-message! payload)
                           (catch Exception e
                             (log/ex :discord.gateway/accepted-message-failed e
                                     :authorId author-id
                                     :channelId channel-id
                                     :guildId guild-id)))))
          reject!    (fn [reason]
                       (log/debug :discord.gateway/message-rejected
                                  :reason reason
                                  :authorId author-id
                                  :channelId channel-id
                                  :guildId guild-id))]
      (cond
        (= bot-id author-id)
        (reject! :self)

        guild-id
        (if (contains? @(:allow-from-guilds client) guild-id)
          (accept!)
          (reject! :guild))

        :else
        (if (contains? @(:allow-from-users client) author-id)
          (accept!)
          (reject! :user))))

    nil))

(defn- rand-fraction [client]
  ((or (:rand-fn client) rand)))

(defn- invalid-session-wait-ms
  "Uniform in [1000, 5000): Discord's documented 1-5 s wait before a fresh
   IDENTIFY after opcode 9 d=false."
  [client]
  (let [span (- invalid-session-max-wait-ms invalid-session-min-wait-ms)]
    (+ invalid-session-min-wait-ms (long (* (rand-fraction client) span)))))

(defn- forget-session! [client]
  (swap! (:state client) assoc :session-id nil :resume-gateway-url nil :sequence nil))

(defn- handle-reconnect-op! [client]
  (log/info :discord.gateway/reconnect-requested :opcode 7)
  (on-close! client {:reason "opcode-7-reconnect" :status 4000 :mode :resume :routine? true}))

(defn- handle-invalid-session-op! [client resumable?]
  (log/warn :discord.gateway/invalid-session :opcode 9 :resumable resumable?)
  (when-not resumable? (forget-session! client))
  (on-close! client (cond-> {:reason   (str "opcode-9-invalid-session-" resumable?)
                             :status   (if resumable? 4000 4009)
                             :mode     (if resumable? :resume :identify)
                             :routine? true}
                      (not resumable?) (assoc :delay-ms (invalid-session-wait-ms client)))))

(defn- handle-frame! [client message]
  (when-let [sequence (:s message)]
    (swap! (:state client) assoc :sequence sequence))
  (case (:op message)
    10 (handle-hello! client (:d message))
    11 (swap! (:state client)
              (fn [s]
                (-> s
                    (assoc :last-heartbeat-acked-sequence (:last-heartbeat-sent-sequence s)
                           :last-heartbeat-ack-at-ms (now-ms client)
                           :heartbeat-ack-pending? false))))
    7  (handle-reconnect-op! client)
    9  (handle-invalid-session-op! client (:d message))
    0  (handle-dispatch! client message)
    (when (some? (:op message))
      (log/warn :discord.gateway/unhandled-opcode :opcode (:op message)))))

(defn receive-text! [client text]
  (try
    (handle-frame! client (json/parse-string text true))
    (catch Exception e
      (log/ex :discord.gateway/invalid-frame e :payload text))))

(defn- url-query [url]
  (if-let [i (str/index-of url "?")] (subs url i) ""))

(defn- reconnect-url
  "RESUME dials READY's resume_gateway_url with the initial connection's
   query (v, encoding); IDENTIFY, or a resume with no stored URL, dials the
   default gateway."
  [client mode]
  (let [resume-base (:resume-gateway-url @(:state client))]
    (if (and (= :resume mode) resume-base)
      (str (str/replace resume-base #"/+$" "") "/" (url-query (:url client)))
      (:url client))))

(defn- do-reconnect! [client mode]
  (let [transport (or ((:connect-ws! client) (reconnect-url client mode) (:handlers client))
                      (throw (ex-info "connect failed" {:mode mode})))]
    (if-not (:running? @(:state client))
      ;; stopped while this attempt was connecting: don't adopt the socket
      (transport-close! transport normal-close-code)
      (do
        ;; adopt the socket and release the attempt claim together, so a close
        ;; (op 7/9) on the new socket can schedule the next reconnect
        (swap! (:state client) #(-> % (assoc :status :connected :transport transport)
                                    (dissoc :reconnect-task-id)))
        (start-reader-loop! client transport)
        (case mode
          :resume (send-resume! client)
          :identify (send-identify! client))))))

(defn- park! [client]
  (log/error :discord.gateway/reconnect-exhausted :attempts max-reconnect-attempts)
  (swap! (:state client) assoc :reconnect-exhausted? true)
  (swap! (:state client) dissoc :reconnect-task-id)
  nil)

(defn- record-attempt! [client]
  (:reconnect-attempts (swap! (:state client) update :reconnect-attempts (fnil inc 0))))

(defn- backoff-delay-ms
  "Equal-jitter exponential backoff: d = min(cap, base * 2^(attempt-1)),
   delay uniform in [d/2, d]. Never exceeds the cap."
  [client attempt]
  (let [base (max 1 (or (:reconnect-delay-ms client) default-reconnect-delay-ms))
        cap  (max 1 (or (:reconnect-max-delay-ms client) default-reconnect-max-delay-ms))
        d    (min cap (* base (bit-shift-left 1 (min 20 (max 0 (dec attempt))))))
        half (quot d 2)]
    (max 1 (+ (- d half) (long (* (rand-fraction client) half))))))

(declare schedule-attempt!)

(defn- reconnect-handler [client mode]
  (fn [_ctx]
    (when (and (:running? @(:state client))
               (not (:reconnect-exhausted? @(:state client))))
      (let [n (record-attempt! client)]
        (if (> n max-reconnect-attempts)
          (park! client)
          (do
            (log/info :discord.gateway/reconnect-attempt :mode mode :attempt n)
            (try
              (do-reconnect! client mode)
              (catch Exception e
                (cond
                  (not (:running? @(:state client)))
                  (swap! (:state client) dissoc :reconnect-task-id)

                  (>= n max-reconnect-attempts)
                  (park! client)

                  :else
                  (let [delay-ms  (backoff-delay-ms client (inc n))
                        ;; docs (Handling a Disconnect): if the reconnect fails,
                        ;; open a new connection on the initial URL and identify.
                        ;; One retry of the resume URL covers a blip.
                        next-mode (if (and (= :resume mode) (>= n resume-dial-attempts)) :identify mode)]
                    (log/warn :discord.gateway/reconnect-failed
                              :mode mode :next-mode next-mode :attempt n :next-delay-ms delay-ms
                              :error-class (.getName (class e)) :error (.getMessage e))
                    (schedule-attempt! client next-mode delay-ms)))))))))))

(defn- cancel-reconnect! [client]
  (when-let [sch (or (:scheduler client) (nexus/get :scheduler))]
    (when-let [task-id (:reconnect-task-id @(:state client))]
      (scheduler/cancel! sch task-id))
    (swap! (:state client) dissoc :reconnect-task-id)))

(defn- claim-reconnect!
  "CAS the reconnect claim. Returns true if this caller owns the next attempt."
  [client]
  (let [[old _] (swap-vals! (:state client)
                            (fn [s]
                              (if (or (reconnect-pending? s) (:reconnect-exhausted? s))
                                s
                                (assoc s :reconnect-task-id reconnect-task-id))))]
    (and (not (reconnect-pending? old))
         (not (:reconnect-exhausted? old)))))

(defn- schedule-attempt!
  "Schedules one reconnect attempt `delay-ms` from now (the scheduler clock),
   as its own one-shot task. The gateway owns the backoff: the scheduler's
   :retry policy anchors each retry to the failed run's scheduled time, so a
   slow connect failure made every remaining retry fire at once."
  [client mode delay-ms]
  (let [sch (client-scheduler client)
        id  (keyword "discord.gateway" (str "reconnect-" (random-uuid)))]
    (swap! (:state client) assoc :reconnect-task-id id)
    (try
      (scheduler/schedule! sch {:id      id
                                :trigger {:kind :delay :ms (max 1 delay-ms)}
                                :handler (reconnect-handler client mode)})
      (catch Exception e
        (swap! (:state client) dissoc :reconnect-task-id)
        (throw e)))))

(defn- schedule-reconnect! [client mode delay-ms]
  (when (client-scheduler client)
    (when (claim-reconnect! client)
      (schedule-attempt! client mode (or delay-ms (backoff-delay-ms client 1))))))

(defn- attempt-reconnect! [client mode & [delay-ms]]
  (if (client-scheduler client)
    (schedule-reconnect! client mode delay-ms)
    (when-not (or (reconnect-pending? @(:state client))
                  (:reconnect-exhausted? @(:state client)))
      (log/info :discord.gateway/reconnect-attempt :mode mode)
      (swap! (:state client) assoc :reconnect-in-flight? true)
      (try
        (do-reconnect! client mode)
        (finally
          (swap! (:state client) dissoc :reconnect-in-flight?))))))

(defn- close-status [payload]
  (or (:status payload) (:code payload) (:status-code payload)))

(defn- error-payload [error]
  {:message (.getMessage error)
   :class   (.getName (class error))
   :data    (try
              (ex-data error)
              (catch Exception _ nil))})

(defn- fatal-close? [status]
  (or (= 4004 status)
      (and (some? status) (>= status 4010))))

(defn- reconnect-after-close! [client payload status reason]
  (let [mode  (or (:mode payload) (reconnect-mode-for-close status))
        state @(:state client)]
    (log/log (if (:routine? payload) :info :warn)
             :discord.gateway/disconnected :payload payload :status status :reason reason :mode mode)
    (when-not (or (reconnect-pending? state) (:reconnect-exhausted? state))
      (try
        (attempt-reconnect! client mode (:delay-ms payload))
        (catch Exception e
          (log/ex :discord.gateway/reconnect-schedule-failed e :mode mode :reason reason)
          (ensure-recovery! client))))))

(defn- on-close! [client payload]
  (let [status (close-status payload)
        reason (:reason payload)]
    (try
      (cancel-heartbeat! client {:reason (or reason "connection-closed")})
      (when-let [transport (:transport @(:state client))]
        (try
          (transport-close! transport (if (= :resume (or (:mode payload) (reconnect-mode-for-close status)))
                                        resume-close-code
                                        normal-close-code))
          (catch Exception e
            ;; the peer usually closed first; a failed close of a dead socket is not actionable
            (log/debug :discord.gateway/transport-close-failed :reason reason
                       :error-class (.getName (class e))))))
      (swap! (:state client) assoc :status :disconnected :disconnect payload :transport nil)
      (if (fatal-close? status)
        (do
          (swap! (:state client) assoc :running? false)
          (cancel-reconnect! client)
          (log/error :discord.gateway/fatal-close :payload payload :status status :reason reason))
        (reconnect-after-close! client payload status reason))
      (catch Exception e
        (log/ex :discord.gateway/on-close-failed e :reason reason)
        (when-not (fatal-close? status)
          (ensure-recovery! client))))))

(defn- current-transport? [client transport]
  (let [state @(:state client)]
    (and (:running? state) (identical? transport (:transport state)))))

(defn- start-reader-loop!
  "Reads frames until its transport is no longer the client's current one.
   A reader whose socket was replaced (reconnect) or stopped exits quietly
   instead of running on-close! a second time."
  [client transport]
  (when-not (:callback-driven? transport)
    (future
      (try
        (loop []
          (if (current-transport? client transport)
            (let [message (transport-receive! transport)]
              (cond
                (= ws/timeout message)
                (recur)

                (not (current-transport? client transport))
                (log/debug :discord.gateway/reader-superseded)

                (nil? message)
                (on-close! client (or (ws/ws-close-payload transport) {:reason "reader-nil-message"}))

                (and (map? message) (= :close (:type message)))
                (on-close! client message)

                (and (map? message) (:error message))
                (do
                  (log/ex :discord.gateway/error (:error message)
                          :payload (error-payload (:error message)))
                  (on-close! client {:reason "transport-error" :status 1006}))

                (map? message)
                (do
                  (log/error :discord.gateway/transport-error :error (str message))
                  (recur))

                :else
                (do
                  (receive-text! client message)
                  (recur))))
            (log/debug :discord.gateway/reader-superseded)))
        (catch Exception e
          (log/ex :discord.gateway/reader-loop-failed e)
          (try
            (on-close! client {:reason "reader-loop-failed" :status 1006})
            (catch Exception close-e
              (log/ex :discord.gateway/reader-close-failed close-e)
              (ensure-recovery! client))))))))

(defn connect!
  [{:keys [token url connect-ws! scheduler allow-from-users allow-from-guilds on-accepted-message!
           reconnect-delay-ms reconnect-max-delay-ms rand-fn]
    :or   {url gateway-url connect-ws! default-connect-ws!}}]
  (let [state      (atom {:status     :disconnected
                          :accepted   []
                          :running?   true
                          :sequence   nil
                          :bot-id     nil
                          :session-id nil
                          :transport  nil})
        client*    (atom nil)
        handlers   {:on-message #(receive-text! @client* %)
                    :on-close   #(on-close! @client* %)
                    :on-error   #(log/ex :discord.gateway/error % :payload (error-payload %))}
        client     {:token                  token
                    :url                    url
                    :state                  state
                    :scheduler              scheduler
                    :reconnect-delay-ms     reconnect-delay-ms
                    :reconnect-max-delay-ms reconnect-max-delay-ms
                    :rand-fn                rand-fn
                    :allow-from-users       (atom (normalize-id-set allow-from-users))
                    :allow-from-guilds      (atom (normalize-id-set allow-from-guilds))
                    :on-accepted-message!   on-accepted-message!
                    :handlers               handlers
                    :connect-ws!            connect-ws!}
        _          (reset! client* client)
        transport  (connect-ws! url handlers)]
    (swap! state assoc :status :connected :transport transport)
    (log/info :discord.gateway/connected :url url)
    (start-reader-loop! client transport)
    client))

(defn update-allow-from! [client {:keys [allow-from-users allow-from-guilds]}]
  (reset! (:allow-from-users client) (normalize-id-set allow-from-users))
  (reset! (:allow-from-guilds client) (normalize-id-set allow-from-guilds)))

(defn check-liveness!
  "Service-level watchdog hook: nudge recovery when a running client is not READY."
  [client]
  (ensure-recovery! client))

(defn connected? [client]
  (= :ready (:status @(:state client))))

(defn running? [client]
  (true? (:running? @(:state client))))

(defn current-sequence [client]
  (:sequence @(:state client)))

(defn accepted-messages [client]
  (:accepted @(:state client)))

(defn stop! [client]
  (log/info :discord.gateway/stopping)
  (swap! (:state client) assoc :running? false :status :disconnected)
  (cancel-heartbeat! client {:reason "stop"})
  (cancel-reconnect! client)
  (when-let [transport (:transport @(:state client))]
    (swap! (:state client) assoc :transport nil)
    (try
      (transport-close! transport normal-close-code)
      (catch Exception e
        (log/debug :discord.gateway/transport-close-failed :reason "stop"
                   :error-class (.getName (class e))))))
  nil)