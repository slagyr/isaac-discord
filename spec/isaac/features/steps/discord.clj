(ns isaac.features.steps.discord
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.config.loader :as config]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.logger :as log]
    [isaac.server.app :as app]
    [isaac.spec-helper :as storage]))

(helper! isaac.features.steps.discord)

(defn- kv-cells->map [cells]
  (when (and (seq cells) (even? (count cells)))
    (into {} (map (fn [[k v]] [k v]) (partition 2 cells)))))

(defn- table-map [{:keys [headers rows]}]
  (or (let [header-map (kv-cells->map headers)
            row-map    (apply merge {} (keep kv-cells->map rows))]
        (when (or header-map (seq row-map))
          (merge header-map row-map)))
      (when (and (seq headers) (= 1 (count rows)))
        (zipmap headers (first rows)))
      {}))

(defn- parse-value [value]
  (cond
    (nil? value) nil
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (re-matches #"-?\d+" value) (parse-long value)
    :else value))

(defn- state-dir []
  (or (g/get :runtime-state-dir)
      (some-> (g/get :state-dir) (str "/.isaac"))))

(defn- home-dir []
  (or (g/get :state-dir)
      (some-> (g/get :runtime-state-dir) fs/parent)))

(defn- mem-fs []
  (or (g/get :mem-fs) fs/*fs*))

(defn- with-feature-fs [f]
  (binding [fs/*fs* (mem-fs)]
    (f)))

(defn- get-path [data path]
  (reduce (fn [current segment]
            (cond
              (nil? current) nil
              (map? current) (or (get current (keyword segment))
                                 (get current segment))
              :else nil))
          data
          (str/split path #"\.")))

(defn- config-value [cfg path]
  (get-path cfg path))

(defn- current-discord-config []
  (merge (or (get-in (g/get :server-config) [:comms :discord]) {})
         (or (g/get :discord-config) {})))

(defn- loaded-config []
  (when (state-dir)
    (with-feature-fs #(config/load-config {:home (home-dir)}))))

(defn- routing-enabled? []
  (let [cfg (loaded-config)]
    (and (state-dir)
         (seq (or (g/get :crew) (g/get :agents) (:crew cfg)))
         (seq (or (g/get :models) (:models cfg))))))

(defn- discord-cfg-overrides []
  (cond-> {:comms {:discord (current-discord-config)}}
    (seq (g/get :provider-configs))    (assoc :providers (g/get :provider-configs))
    (get (g/get :server-config) :sessions) (assoc :sessions (get (g/get :server-config) :sessions))))

(defn- absolute-path [path]
  (if (str/starts-with? path "/") path (str (state-dir) "/" path)))

(defn- assoc-path [data path value]
  (assoc-in data (str/split path #"\.") value))

(defn- edn-file-data [path]
  (let [path (absolute-path path)]
    (when (fs/exists? path)
      (edn/read-string (fs/slurp path)))))

(defn- parse-json-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn- record-request! [method url opts]
  (let [request {:body    (some-> (:body opts) parse-json-body)
                 :headers (:headers opts)
                 :method  method
                 :url     url}]
    (g/assoc! :outbound-http-request request)
    (g/update! :outbound-http-requests #(conj (or % []) request))))

(defn- stubbed-response [url]
  (when-let [stub (get (g/get :url-stubs) url)]
    {:body    (:body stub "")
     :headers (:headers stub {})
     :status  (:status stub 200)}))

(defn- with-http-post-stub [f]
  (with-redefs [http/post (fn [url opts]
                            (record-request! "POST" url opts)
                            (or (stubbed-response url)
                                {:status 200 :headers {} :body "{}"}))]
    (f)))

(defn- make-connect-ws! [sent callbacks*]
  (fn [_url callbacks]
    (reset! callbacks* callbacks)
    {:callback-driven? true
     :close!           (fn [] nil)
     :send-payload!    (fn [payload] (swap! sent conj payload))}))

(defn- fake-connect! []
  (or (g/get :discord-connect-ws!)
      (let [sent       (or (g/get :discord-sent) (atom []))
            callbacks* (or (g/get :discord-callbacks) (atom nil))
            connect-fn (make-connect-ws! sent callbacks*)]
        (g/assoc! :discord-sent sent)
        (g/assoc! :discord-callbacks callbacks*)
        (g/assoc! :discord-connect-ws! connect-fn)
        connect-fn)))

(defn- active-integration []
  (or (g/get :discord-integration) (app/discord-integration)))

(defn- route-state [payload]
  (let [di           (active-integration)
        di-cfg       (discord/discord-cfg di)
        channel-id   (str (get payload :channel_id))
        session-name (when di-cfg (discord/channel-session-name di-cfg channel-id))
        count        (when (and session-name (state-dir))
                       (count (or (with-feature-fs #(storage/get-transcript (state-dir) session-name)) [])))]
    {:count count :session session-name}))

(defn- route-missing? [{:keys [count session]} before]
  (or (nil? session)
      (and count (<= count 1))
      (and (:count before) (= (:count before) count))))

(defn- active-client []
  (or (g/get :discord-client) (app/discord-client)))

(defn- queue-head []
  (first (gateway/accepted-messages (active-client))))

(defn- sent-op [op]
  (some #(when (= op (:op %)) %) @(g/get :discord-sent)))

(defn discord-faked []
  (let [sent       (atom [])
        callbacks* (atom nil)
        connect-fn (make-connect-ws! sent callbacks*)]
    (g/assoc! :discord-sent sent)
    (g/assoc! :discord-callbacks callbacks*)
    (g/assoc! :discord-connect-ws! connect-fn)))

(defn discord-configured [table]
  (g/assoc! :discord-config (into {} (map (fn [[k v]] [k (parse-value v)]) (table-map table)))))

(defn discord-connects []
  (let [cfg (current-discord-config)]
    (if (state-dir)
      (do
        (g/assoc! :runtime-state-dir (state-dir))
        (let [result (with-feature-fs
                     #(discord/connect! {:cfg-overrides   (discord-cfg-overrides)
                                         :clock-mode      :virtual
                                         :route-messages? (routing-enabled?)
                                         :state-dir       (state-dir)
                                         :connect-ws!     (fake-connect!)}))]
          (g/assoc! :discord-client (:client result))
          (when-let [di (:integration result)]
            (g/assoc! :discord-integration di))))
      (let [client (gateway/connect! {:token             (config-value cfg "token")
                                      :allow-from-users  (config-value cfg "allow-from.users")
                                      :allow-from-guilds (config-value cfg "allow-from.guilds")
                                      :clock-mode        :virtual
                                      :connect-ws!       (fake-connect!)})]
        (g/assoc! :discord-client client)))))

(defn- ensure-connected! []
  (when-not (active-client)
    (discord-connects)))

(defn- send-hello! [table]
  (let [payload {:op 10 :d {:heartbeat_interval (parse-value (get (table-map table) "heartbeat_interval"))}}]
    ((:on-message @(g/get :discord-callbacks)) (json/generate-string payload))))

(defn- send-ready! [table]
  (let [payload {:op 0 :t "READY" :s 1 :d {:session_id (get (table-map table) "session_id")}}]
    ((:on-message @(g/get :discord-callbacks)) (json/generate-string payload))))

(defn discord-sends-hello [table]
  (send-hello! table))

(defn discord-sends-ready [table]
  (send-ready! table))

(defn discord-client-ready-as-bot [bot-id]
  (ensure-connected!)
  (send-hello! {:headers ["heartbeat_interval" "45000"] :rows []})
  ((:on-message @(g/get :discord-callbacks))
   (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "fake-session" :user {:id bot-id}}}))
  (log/clear-entries!))

(defn discord-sends-message-create [table]
  (let [payload (reduce (fn [acc [k v]]
                          (assoc-in acc (mapv keyword (clojure.string/split k #"\.")) (parse-value v)))
                        {}
                        (table-map table))
         before  (when (routing-enabled?) (with-feature-fs #(route-state payload)))]
    (when-let [integration (active-integration)]
      (reset! (.-cfg integration) (current-discord-config)))
    (config/set-snapshot! (loaded-config))
    (with-http-post-stub
      (fn []
        (with-feature-fs
          (fn []
            ((:on-message @(g/get :discord-callbacks))
             (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d payload}))))
        (when (and (routing-enabled?)
                   (route-missing? (with-feature-fs #(route-state payload)) before))
          (with-feature-fs
            (fn []
              (discord/process-message! (active-integration) (state-dir) payload))))))
    (g/assoc! :llm-request (grover/last-request))))


(defn test-clock-advances [n]
  (gateway/advance-time! (g/get :discord-client) n))

(defn discord-stays-silent-for [n]
  (gateway/advance-time! (g/get :discord-client) n))

(defn discord-closes-connection [n]
  ((:on-close @(g/get :discord-callbacks)) {:status n :reason "test-close"}))

(defn discord-closes-connection-with-reason [n reason]
  ((:on-close @(g/get :discord-callbacks)) {:status n :reason reason}))

(defn discord-sends-identify [table]
  (let [message  (sent-op 2)
        expected (into {} (map (fn [[k v]] [k (parse-value v)]) (table-map table)))]
    (g/should-not-be-nil message)
    (g/should= (get expected "token") (get-in message [:d :token]))
    (g/should= (get expected "intents") (get-in message [:d :intents]))))

(defn discord-sends-resume [table]
  (let [message  (sent-op 6)
        expected (into {} (map (fn [[k v]] [k (parse-value v)]) (table-map table)))]
    (g/should-not-be-nil message)
    (g/should= (get expected "token") (get-in message [:d :token]))
    (g/should= (get expected "session_id") (get-in message [:d :session_id]))
    (g/should= (get expected "seq") (get-in message [:d :seq]))))

(defn discord-sends-heartbeat []
  (g/should-not-be-nil (sent-op 1)))

(defn discord-client-connected []
  (let [client (active-client)]
    (g/should-not-be-nil client)
    (g/should (gateway/running? client))
    (log/clear-entries!)))

(defn discord-client-disconnected []
  (let [client (active-client)]
    (g/should (or (nil? client)
                  (not (gateway/running? client))))))

(defn discord-client-accepted-message [table]
  (let [message  (queue-head)
        expected (into {} (map (fn [[k v]] [k (parse-value v)]) (table-map table)))]
    (g/should-not-be-nil message)
    (doseq [[k v] expected]
      (g/should= v (get-path message k)))))

(defn discord-client-accepted-no-messages []
  (g/should= [] (gateway/accepted-messages (active-client))))

(defn edn-file-matches [path table]
  (let [data (with-feature-fs #(edn-file-data path))]
    (doseq [row (:rows table)]
      (let [row-map   (zipmap (:headers table) row)
            actual    (get-path data (get row-map "path"))
            expected  (parse-value (get row-map "value"))]
        (g/should= expected actual)))))

;; region ----- Routing -----

(defgiven "the Discord Gateway is faked in-memory" discord/discord-faked
  "Initializes :discord-sent (outbound payload capture) and
   :discord-callbacks (inbound handlers). Prerequisite for every other
   discord step — always include in Background.")

(defgiven "Discord is configured with:" discord/discord-configured)

(defwhen "the Discord client connects" discord/discord-connects
  "Connects via discord/connect! when state-dir is set (routing enabled),
   else via the lower-level gateway/connect! (no routing). Uses virtual
   clock mode — advance time with 'the test clock advances N ms'.")

(defwhen "Discord sends HELLO:" discord/discord-sends-hello
  "Synthesizes an inbound HELLO gateway payload (op 10) via the on-message
   callback. Table cell 'heartbeat_interval' sets the interval.")

(defwhen "Discord sends READY:" discord/discord-sends-ready
  "Synthesizes an inbound READY dispatch (op 0 t=READY) via the
   on-message callback. Table cell 'session_id' is echoed into the
   payload.")

(defgiven #"the Discord client is ready as bot \"([^\"]+)\"" discord/discord-client-ready-as-bot
  "Shortcut for the usual connect→HELLO→READY handshake. Sends HELLO
   with heartbeat_interval 45000 and a READY with a fixed session_id and
   the given bot user id. Use when the handshake isn't the focus.")

(defwhen "Discord sends MESSAGE_CREATE:" discord/discord-sends-message-create
  "Synthesizes an inbound MESSAGE_CREATE. Runs HTTP-post stubbing, fires
   the on-message callback, and — if routing is enabled and the message
   would create a new session — also invokes discord/process-message!
   directly. Captures :llm-request from grover.")

(defwhen "the test clock advances {n:int} milliseconds" discord/test-clock-advances
  "Advances the virtual clock on the discord client. Only works when
   the client was connected in :clock-mode :virtual (the default for
   the discord test steps).")

(defwhen "Discord stays silent for {n:int} milliseconds" discord/discord-stays-silent-for)

(defwhen "Discord closes the connection with code {n:int}" discord/discord-closes-connection)

(defwhen "Discord closes the connection with code {n:int} reason {reason:string}" discord/discord-closes-connection-with-reason)

(defthen "the Discord client sends IDENTIFY:" discord/discord-sends-identify)

(defthen "the Discord client sends RESUME:" discord/discord-sends-resume)

(defthen "the Discord client sends HEARTBEAT" discord/discord-sends-heartbeat)

(defthen "the Discord client is connected" discord/discord-client-connected)

(defthen "the Discord client is disconnected" discord/discord-client-disconnected)

(defthen "the Discord client accepted a message with:" discord/discord-client-accepted-message)

(defthen "the Discord client accepted no messages" discord/discord-client-accepted-no-messages)

(defthen "the EDN file \"{path}\" matches:" discord/edn-file-matches)

;; endregion ^^^^^ Routing ^^^^^
