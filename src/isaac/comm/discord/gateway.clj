(ns isaac.comm.discord.gateway
  (:require
    [cheshire.core :as json]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.util.ws-client :as ws]))

(def gateway-url "wss://gateway.discord.gg/?v=10&encoding=json")
(def intents 37377)

(def ^:private resumable-close-codes #{4000 4001 4002 4003 4008})
(def ^:private reidentify-close-codes #{1000 1001 1006 4007 4009})
(def ^:private reconnect-task-id :discord.gateway/reconnect)
(def ^:private default-reconnect-delay-ms 1000)
(def ^:private default-reconnect-max-delay-ms 30000)

(defn- normalize-id-set [values]
  (->> (cond
         (nil? values)        []
         (sequential? values) values
         :else                [values])
       (map str)
       set))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- default-connect-ws! [url _handlers]
  (ws/connect! url))

(defn- transport-send! [transport payload]
  (cond
    (:send-payload! transport) ((:send-payload! transport) payload)
    (:send! transport)         ((:send! transport) payload)
    (:send-text! transport)    ((:send-text! transport) (json/generate-string payload))
    :else                      (ws/ws-send! transport (json/generate-string payload))))

(defn- transport-close! [transport]
  (cond
    (:close! transport) ((:close! transport))
    :else               (ws/ws-close! transport)))

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

(declare start-reader-loop! stop! on-close!)

(defn- send-identify! [client]
  (let [payload (identify-payload (:token client))]
    (transport-send! (:transport @(:state client)) payload)
    (swap! (:state client) assoc :status :identified)
    (log/info :discord.gateway/identify :intents intents)))

(defn- send-resume! [client]
  (let [payload (resume-payload client)]
    (transport-send! (:transport @(:state client)) payload)
    (swap! (:state client) assoc :status :resuming)
    (log/info :discord.gateway/resume :seq (get-in payload [:d :seq]) :session-id (get-in payload [:d :session_id]))))

(defn- heartbeat-ack-missing? [state]
  (true? (:heartbeat-ack-pending? state)))

(defn- log-liveness! [client]
  (let [state      @(:state client)
        interval   (:heartbeat-interval-ms state)
        ack-at     (:last-heartbeat-ack-at-ms state)
        now        (now-ms)
        ack-age-ms (when ack-at (- now ack-at))]
    (if (and ack-age-ms interval (> ack-age-ms (* 2 interval)))
      (log/warn :discord.gateway/liveness-stale
                :last-ack-ms-ago ack-age-ms
                :heartbeat-interval-ms interval)
      (log/info :discord.gateway/liveness
                :last-ack-ms-ago (or ack-age-ms "never")
                :status (:status state)))))

(defn- send-heartbeat! [client]
  (when (heartbeat-ack-missing? @(:state client))
    (log/warn :discord.gateway/heartbeat-ack-timeout
              :sent-sequence (:last-heartbeat-sent-sequence @(:state client)))
    (on-close! client {:reason "heartbeat-ack-timeout" :status 1006}))
  (when (and (:running? @(:state client))
             (not (heartbeat-ack-missing? @(:state client))))
    (let [seq     (:sequence @(:state client))
          payload (heartbeat-payload seq)]
      (transport-send! (:transport @(:state client)) payload)
      (swap! (:state client) assoc
             :last-heartbeat-sent-sequence seq
             :heartbeat-ack-pending? true)
      (log/debug :discord.gateway/heartbeat :sequence (:d payload)))))

(defn- cancel-heartbeat! [client & [{:keys [reason]}]]
  (when (:heartbeat-task-id @(:state client))
    (log/warn :discord.gateway/heartbeat-cancelled :reason (or reason "unspecified")))
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
           :last-heartbeat-acked-sequence nil
           :last-heartbeat-ack-at-ms nil
           :heartbeat-ack-pending? false)
    (log/info :discord.gateway/hello :heartbeat-interval-ms interval-ms)
    (schedule-heartbeats! client interval-ms)
    (send-identify! client)))

(defn- handle-dispatch! [client message]
  (when-let [sequence (:s message)]
    (swap! (:state client) assoc :sequence sequence))
  (case (:t message)
    "READY"
    (do
      (swap! (:state client) assoc
             :status     :ready
             :session-id (get-in message [:d :session_id])
             :bot-id     (some-> (get-in message [:d :user :id]) str))
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

(defn- handle-reconnect-op! [client]
  (log/warn :discord.gateway/reconnect-requested :opcode 7)
  (on-close! client {:reason "opcode-7-reconnect" :status 4000 :mode :resume}))

(defn- handle-invalid-session-op! [client resumable?]
  (log/warn :discord.gateway/invalid-session :opcode 9 :resumable resumable?)
  (on-close! client {:reason (str "opcode-9-invalid-session-" resumable?)
                    :status (if resumable? 4000 4009)
                    :mode   (if resumable? :resume :identify)}))

(defn- handle-frame! [client message]
  (when-let [sequence (:s message)]
    (swap! (:state client) assoc :sequence sequence))
  (case (:op message)
    10 (handle-hello! client (:d message))
    11 (do
         (swap! (:state client)
                (fn [s]
                  (-> s
                      (assoc :last-heartbeat-acked-sequence (:last-heartbeat-sent-sequence s)
                             :last-heartbeat-ack-at-ms (now-ms)
                             :heartbeat-ack-pending? false))))
         (log/debug :discord.gateway/heartbeat-ack))
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

(defn- do-reconnect! [client mode]
  (let [transport (or ((:connect-ws! client) (:url client) (:handlers client))
                      (throw (ex-info "connect failed" {:mode mode})))]
    (swap! (:state client) assoc :status :connected :transport transport)
    (start-reader-loop! client transport)
    (case mode
      :resume (send-resume! client)
      :identify (send-identify! client))))

(defn- reconnect-handler [client mode]
  (fn [_ctx]
    (when (:running? @(:state client))
      (log/info :discord.gateway/reconnect-attempt :mode mode)
      (do-reconnect! client mode))))

(defn- schedule-reconnect! [client mode]
  (when-let [sch (or (:scheduler client) (nexus/get :scheduler))]
    (let [base-delay (max 1 (or (:reconnect-delay-ms client) default-reconnect-delay-ms))
          max-delay  (max 1 (or (:reconnect-max-delay-ms client) default-reconnect-max-delay-ms))]
      (scheduler/cancel! sch reconnect-task-id)
      (scheduler/schedule!
        sch
        {:id             reconnect-task-id
         :trigger        {:kind :delay :ms base-delay}
         :handler        (reconnect-handler client mode)
         :on-error       :retry
         :backoff-ms     base-delay
         :max-backoff-ms max-delay
         :retry-attempts Long/MAX_VALUE}))))

(defn- attempt-reconnect! [client mode]
  (if-let [sch (or (:scheduler client) (nexus/get :scheduler))]
    (schedule-reconnect! client mode)
    (do
      (log/info :discord.gateway/reconnect-attempt :mode mode)
      (do-reconnect! client mode))))

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

(defn- on-close! [client payload]
  (let [status (close-status payload)
        reason (:reason payload)]
    (cancel-heartbeat! client {:reason (or reason "connection-closed")})
    (when-let [transport (:transport @(:state client))]
      (transport-close! transport))
    (swap! (:state client) assoc :status :disconnected :disconnect payload :transport nil)
    (cond
      (fatal-close? status)
      (do
        (swap! (:state client) assoc :running? false)
        (when-let [sch (or (:scheduler client) (nexus/get :scheduler))]
          (scheduler/cancel! sch reconnect-task-id))
        (log/error :discord.gateway/fatal-close :payload payload :status status :reason reason))

      :else
      (let [mode (or (:mode payload) (reconnect-mode-for-close status))]
        (log/warn :discord.gateway/disconnected :payload payload :status status :reason reason :mode mode)
        (attempt-reconnect! client mode)))))

(defn- start-reader-loop! [client transport]
  (when-not (:callback-driven? transport)
    (future
      (try
        (loop []
          (if (:running? @(:state client))
            (let [message (transport-receive! transport)]
              (cond
                (= ws/timeout message)
                (recur)

                (nil? message)
                (on-close! client (or (ws/ws-close-payload transport) {:reason "reader-nil-message"}))

                (and (map? message) (= :close (:type message)))
                (on-close! client message)

                :else
                (do
                  (cond
                    (and (map? message) (:error message))
                    (log/ex :discord.gateway/error (:error message)
                            :payload (error-payload (:error message)))

                    (map? message)
                    (log/error :discord.gateway/transport-error :error (str message))

                    :else
                    (receive-text! client message))
                  (recur))))
            nil))
        (catch Exception e
          (log/ex :discord.gateway/reader-loop-failed e)
          (on-close! client {:reason "reader-loop-failed" :status 1006}))))))

(defn connect!
  [{:keys [token url connect-ws! scheduler allow-from-users allow-from-guilds on-accepted-message!
           reconnect-delay-ms reconnect-max-delay-ms]
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
  (when-let [sch (or (:scheduler client) (nexus/get :scheduler))]
    (scheduler/cancel! sch reconnect-task-id))
  (when-let [transport (:transport @(:state client))]
    (transport-close! transport))
  nil)