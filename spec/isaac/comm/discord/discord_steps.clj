(ns isaac.comm.discord.discord-steps
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.comm.discord :as discord]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.protocol :as comm]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.foundation.fs-steps :as ffs]

    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.server.app :as server-app]
    [isaac.service.registry :as service-registry]
    [isaac.spec-helper :as helper]
    [isaac.llm.providers-steps :as providers-steps]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.spec-helper :as storage]
    [isaac.session.store.spi :as session-store]))

(helper! isaac.comm.discord.discord-steps)

(g/before-scenario
  (fn []
    ;; Full teardown between scenarios so state doesn't leak across examples
    ;; sharing the process: stop any running server, clear the nexus, and reset
    ;; the service registry. service-runtime/stop-all! only deregisters service
    ;; *instances*, not the accumulated comm *registrations*, so without this a
    ;; prior scenario's stale registrations bleed into the next one.
    (server-app/stop!)
    (nexus/reset!)
    (reset! service-registry/*registry* (service-registry/fresh-registry))
    (log/clear-entries!)))

;; Bridge the in-memory fake gateway into integrations started by the server.
;; factory/create passes :connect-ws! nil; inject from g when the Gateway is faked.
(alter-var-root #'discord/make
  (fn [original]
    (fn [host]
      (let [fake-ws (try (g/get :discord-connect-ws!) (catch Exception _ nil))
            host    (if (and fake-ws (not (:connect-ws! host)))
                      (assoc host :connect-ws! fake-ws)
                      host)]
        (original host)))))

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
  (let [value (if (string? value) (str/trim value) value)]
    (cond
      (nil? value) nil
      (= "true" (str/lower-case value)) true
      (= "false" (str/lower-case value)) false
      (and (string? value) (re-matches #"-?\d+" value)) (parse-long value)
      (and (string? value)
           (or (str/starts-with? value "[")
               (str/starts-with? value "{")
               (str/starts-with? value ":")
               (str/starts-with? value "\"")))
      (try (edn/read-string value) (catch Exception _ value))
      :else value)))

(defn- root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- state-dir []
  (or (g/get :runtime-state-dir)
      (root-dir)))

(defn- home-dir []
  (or (g/get :root)
      (some-> (g/get :runtime-root-dir) fs/parent)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
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

(defn- loaded-config []
  (when (state-dir)
    (with-feature-fs #(:config (loader/load-config-result {:root (state-dir)})))))

(defn- discord-slice-from-disk []
  (or (get-in (loaded-config) [:comms :discord])
      (when-let [dir (state-dir)]
        (with-feature-fs
          (fn []
            (try
              (let [path (str dir "/config/isaac.edn")
                    fs*  (mem-fs)]
                (when (fs/exists? fs* path)
                  (get-in (edn/read-string (fs/slurp fs* path)) [:comms :discord])))
              (catch Exception _ nil)))))))

(defn- current-discord-config []
  (merge (or (discord-slice-from-disk) {})
         (or (get-in (loaded-config) [:comms :discord]) {})
         (or (get-in (g/get :server-config) [:comms :discord]) {})
         (or (g/get :discord-config) {})))

(defn- routing-enabled? []
  (when-let [cfg (loaded-config)]
    (and (state-dir)
         (seq (:crew cfg))
         (seq (:models cfg)))))

(defn- discord-cfg-overrides []
  (let [discord (if (seq (current-discord-config))
                  (current-discord-config)
                  (or (discord-slice-from-disk) {}))]
    (cond-> {}
      (seq discord)                      (assoc :comms {:discord discord})
      (seq (g/get :provider-configs))    (assoc :providers (g/get :provider-configs))
      (get (g/get :server-config) :sessions) (assoc :sessions (get (g/get :server-config) :sessions)))))

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
  (or (g/get :discord-integration)
      (nexus/get-in [:comms :discord])
      (comm-registry/comm-for "discord")))

(defn- route-state [payload]
  (let [di           (active-integration)
        di-cfg       (discord/discord-cfg di)
        channel-id   (str (get payload :channel_id))
        session-name (when di-cfg (discord/channel-session-name di-cfg channel-id))
        count        (when (and session-name (session-store/registered-store))
                       (count (or (with-feature-fs
                                    #(session-store/get-transcript (session-store/registered-store)
                                                                   session-name))
                                  [])))]
    {:count count :session session-name}))

(defn- route-missing? [{:keys [count session]} before]
  (or (nil? session)
      (and count (<= count 1))
      (and (:count before) (= (:count before) count))))

(defn- active-client []
  (or (g/get :discord-client)
      (:client (some-> (active-integration) discord/client))))

(defn- queue-head []
  (when-let [client (active-client)]
    (first (gateway/accepted-messages client))))

(defn- sent-op [op]
  (some #(when (= op (:op %)) %) (reverse @(g/get :discord-sent))))

(defn- discord-module-coord []
  {:isaac.comm.discord {:local/root (System/getProperty "user.dir")}})

(defn- discord-module-index []
  (when-let [manifest (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string)]
    {:isaac.comm.discord {:coord {:local/root (System/getProperty "user.dir")}
                          :manifest manifest
                          :path nil}}))

(defn- merge-modules-into-isaac-edn! [root]
  (when root
    (with-feature-fs
      (fn []
        (let [path (str root "/config/isaac.edn")
              fs*  (mem-fs)]
          (when (fs/exists? fs* path)
            (let [data    (edn/read-string (fs/slurp fs* path))
                  updated (update data :modules merge (discord-module-coord))]
              (fs/spit fs* path (pr-str updated)))))))))

(defn- persist-discord-modules! []
  (when-let [root (state-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (mem-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (discord-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn- ensure-grover-defaults-on-disk! []
  (when-let [root (state-dir)]
    (with-feature-fs
      (fn []
        (let [cfg-root (str root "/config")
              fs*      (mem-fs)]
          (when-not (fs/exists? fs* (str cfg-root "/crew/main.edn"))
            (fs/mkdirs fs* cfg-root)
            (fs/mkdirs fs* (str cfg-root "/models"))
            (fs/mkdirs fs* (str cfg-root "/providers"))
            (fs/mkdirs fs* (str cfg-root "/crew"))
            (let [isaac-path (str cfg-root "/isaac.edn")
                  current    (if (fs/exists? fs* isaac-path)
                               (edn/read-string (fs/slurp fs* isaac-path))
                               {})]
              (fs/spit fs* isaac-path
                       (pr-str (merge {:defaults {:crew "main" :model "grover"}}
                                      current)))
              (fs/spit fs* (str cfg-root "/models/grover.edn")
                       (pr-str {:model "echo" :provider :grover :context-window 32768}))
              (fs/spit fs* (str cfg-root "/providers/grover.edn") (pr-str {}))
              (fs/spit fs* (str cfg-root "/crew/main.edn")
                       (pr-str {:model :grover :soul "You are Atticus."})))))))))

(ffs/register-post-write-hook!
  (fn [path]
    (when (and (state-dir) (str/ends-with? path "/config/isaac.edn"))
      (merge-modules-into-isaac-edn! (state-dir)))))

(alter-var-root #'server-app/start!
  (fn [original]
    (fn [opts]
      (let [fake-ws (try (g/get :discord-connect-ws!) (catch Exception _ nil))
            result  (original (cond-> opts
                               (and fake-ws (not (:connect-ws! opts)))
                               (assoc :connect-ws! fake-ws)))]
        (ensure-grover-defaults-on-disk!)
        (persist-discord-modules!)
        result))))

(defn- ensure-discord-module-declared! []
  ;; discover! resolves :modules from disk; inject-module-index (merged in
  ;; server-running) ensures the classpath manifest is visible even before
  ;; modules are written to isaac.edn — required for comm :extra-schema and
  ;; factory/create after the split-module migration.
  (when-not (get-method comm-factory/create :discord)
    (require 'isaac.comm.discord))
  (g/update! :server-config
             #(-> (or % {})
                  (update :modules
                          (fn [m] (merge (discord-module-coord) m)))
                  (update :inject-module-index
                          (fn [m] (merge (discord-module-index) m)))))
  (persist-discord-modules!))

(defn discord-module-registered []
  (ensure-discord-module-declared!))

(defn discord-isaac-server-started []
  (ensure-discord-module-declared!)
  ((requiring-resolve 'isaac.server.server-steps/server-running)))

(defn discord-faked []
  (let [sent       (atom [])
        callbacks* (atom nil)
        connect-fn (make-connect-ws! sent callbacks*)]
    (g/assoc! :discord-sent sent)
    (g/assoc! :discord-callbacks callbacks*)
    (g/assoc! :discord-connect-ws! connect-fn)
    (ensure-grover-defaults-on-disk!)
    (ensure-discord-module-declared!)))

(defn- assoc-dotted-config! [base k v]
  (let [segments (mapv keyword (str/split (str k) #"\."))]
    (assoc-in base segments (parse-value v))))

(defn discord-configured [table]
  (g/update! :discord-config
             (fn [base]
               (reduce (fn [acc [k v]] (assoc-dotted-config! acc k v))
                       (or base {})
                       (seq (table-map table))))))

(defn discord-connects []
  (let [cfg   (current-discord-config)
        clock (test-clock/make)]
    (g/assoc! :discord-clock clock)
    (if (state-dir)
      (do
        (g/assoc! :runtime-state-dir (state-dir))
        (let [result (with-feature-fs
                     #(discord/connect! {:cfg-overrides   (discord-cfg-overrides)
                                         :scheduler       (:scheduler clock)
                                         :route-messages? true
                                         :state-dir       (state-dir)
                                         :connect-ws!     (fake-connect!)}))]
          (g/assoc! :discord-client (:client result))
          (when-let [di (:integration result)]
            (when-let [cfg (loaded-config)]
              (reset! (.-cfg di) (merge (get-in cfg [:comms :discord] {})
                                        (current-discord-config))))
            (g/assoc! :discord-integration di))))
      (let [client (gateway/connect! {:token             (config-value cfg "discord/token")
                                      :allow-from-users  (config-value cfg "discord/allow-from.users")
                                      :allow-from-guilds (config-value cfg "discord/allow-from.guilds")
                                      :scheduler         (:scheduler clock)
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
  (g/dissoc! :discord-client :discord-integration)
  (discord-connects)
  (send-hello! {:headers ["heartbeat_interval" "45000"] :rows []})
  ((:on-message @(g/get :discord-callbacks))
   (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "fake-session" :user {:id bot-id}}}))
  (log/clear-entries!))

(defn current-session-completed-turn-with-text [text]
  (let [session-key (g/get :current-session-key)
        integration (active-integration)]
    (g/should-not-be-nil session-key)
    (g/should-not-be-nil integration)
    (with-http-post-stub
      #(comm/on-turn-end integration session-key {:content text}))))

(defn discord-sends-message-create [table]
  (let [payload (reduce (fn [acc [k v]]
                          (assoc-in acc (mapv keyword (clojure.string/split k #"\.")) (parse-value v)))
                        {}
                        (table-map table))
        before  (when (routing-enabled?) (with-feature-fs #(route-state payload)))]
    (when-let [cfg (loaded-config)]
      (config/dangerously-install-config! cfg "discord feature")
      (when-let [integration (active-integration)]
        (reset! (.-cfg integration)
                (merge (get-in cfg [:comms :discord] {}) (current-discord-config)))))
    (with-http-post-stub
      (fn []
        (with-feature-fs
          (fn []
            ((:on-message @(g/get :discord-callbacks))
             (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d payload}))
            (when (and (routing-enabled?)
                       (route-missing? (route-state payload) before))
              (discord/process-message! (active-integration) (state-dir) payload))))
        (session-steps/await-turn!)
        (g/assoc! :llm-request (grover/last-request))))))


(defn test-clock-advances [n]
  (test-clock/advance! (g/get :discord-clock) n))

(defn discord-stays-silent-for [n]
  (test-clock/advance! (g/get :discord-clock) n))

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
  (helper/await-condition
    #(let [client (active-client)]
       (and client (gateway/running? client)))
    10000)
  (let [client (active-client)]
    (g/should-not-be-nil client)
    (g/should (gateway/running? client))
    (log/clear-entries!)))

(defn discord-client-disconnected []
  (helper/await-condition
    #(let [client (active-client)]
       (or (nil? client) (not (gateway/running? client))))
    10000)
  (let [client (active-client)]
    (g/should (or (nil? client)
                  (not (gateway/running? client))))))

(defn discord-client-accepted-message [table]
  (helper/await-condition #(queue-head) 10000)
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

(defn discord-channels-numeric-key [table]
  (doseq [row (:rows table)]
    (let [row-map    (zipmap (:headers table) row)
          channel-id (parse-long (get row-map "channel_id"))
          session    (get row-map "session")]
      (g/update! :server-config
                 #(assoc-in % [:comms :discord :discord/channels channel-id] {:session session}))
      (when-let [root (root-dir)]
        (with-feature-fs
          (fn []
            (let [path     (str root "/config/isaac.edn")
                  fs*      (mem-fs)
                  current  (if (fs/exists? fs* path)
                             (edn/read-string (fs/slurp fs* path))
                             {})
                  channels (assoc (get-in current [:comms :discord :discord/channels] {})
                                  channel-id {:session session})
                  updated  (assoc-in current [:comms :discord :discord/channels] channels)]
              (fs/mkdirs fs* (fs/parent path))
              (fs/spit fs* path (pr-str updated)))))))))

(defn discord-outbound-comm-registered []
  (ensure-discord-module-declared!)
  (when-let [cfg (loaded-config)]
    (config/dangerously-install-config! cfg "discord comm_send feature"))
  (let [discord-cfg (merge (or (discord-slice-from-disk) {})
                           (current-discord-config))
        di          (discord/->DiscordIntegration (state-dir) nil (atom discord-cfg) (atom nil))]
    (g/assoc! :discord-integration di)))

(defn discord-outbound-http-request-to-url-matches [url table]
  (providers-steps/outbound-http-request-to-url-matches url table))

(defn discord-comm-send! [table]
  (let [record (reduce (fn [acc row]
                         (let [row-map (zipmap (:headers table) row)]
                           (assoc acc (keyword (get row-map "path"))
                                    (parse-value (get row-map "value")))))
                       {}
                       (:rows table))
        di     (or (active-integration)
                   (throw (ex-info "Discord comm integration not registered" {})))]
    (when-let [cfg (loaded-config)]
      (config/dangerously-install-config! cfg "discord feature"))
    (reset! (.-cfg di)
            (merge (or (discord-slice-from-disk) {})
                   (current-discord-config)))
    (with-http-post-stub
      #(comm/send! di record))))

;; region ----- Routing -----

(defgiven "Discord channels map has numeric key:" isaac.comm.discord.discord-steps/discord-channels-numeric-key
  "Patches :discord/channels with a bare numeric (Long) channel id key, as EDN
   would produce for an unquoted snowflake. Exercises tolerant channel routing.")

(defgiven "the discord module is registered" isaac.comm.discord.discord-steps/discord-module-registered
  "Declares :isaac.comm.discord in the module index so config-berth
   reconciliation can instantiate the comm slot without server boot.")

(defgiven "the discord Isaac server is started" isaac.comm.discord.discord-steps/discord-isaac-server-started
  "Starts the full HTTP server with discord module index injected — avoids
   ambiguity with agent-tier 'the Isaac server is started' steps.")

(defwhen "the discord Isaac server boots" isaac.comm.discord.discord-steps/discord-isaac-server-started
  "When-context alias for server start after a prior When step.")

(defgiven "the Discord Gateway is faked in-memory" isaac.comm.discord.discord-steps/discord-faked
  "Initializes :discord-sent (outbound payload capture) and
   :discord-callbacks (inbound handlers). Prerequisite for every other
   discord step — always include in Background.")

(defgiven "Discord is configured with:" isaac.comm.discord.discord-steps/discord-configured)

(defgiven "Discord outbound comm is registered" isaac.comm.discord.discord-steps/discord-outbound-comm-registered
  "Installs a Discord Comm integration from on-disk config for outbound
   send! scenarios without booting the full server or gateway.")

(defwhen "the Discord client connects" isaac.comm.discord.discord-steps/discord-connects
  "Connects via discord/connect! when state-dir is set (routing enabled),
   else via the lower-level gateway/connect! (no routing). Injects a
   virtual-clock scheduler (see isaac.comm.discord.test-clock) — advance
   time with 'the test clock advances N ms'.")

(defwhen "Discord sends HELLO:" isaac.comm.discord.discord-steps/discord-sends-hello
  "Synthesizes an inbound HELLO gateway payload (op 10) via the on-message
   callback. Table cell 'heartbeat_interval' sets the interval.")

(defwhen "Discord sends READY:" isaac.comm.discord.discord-steps/discord-sends-ready
  "Synthesizes an inbound READY dispatch (op 0 t=READY) via the
   on-message callback. Table cell 'session_id' is echoed into the
   payload.")

(defgiven #"the Discord client is ready as bot \"([^\"]+)\"" isaac.comm.discord.discord-steps/discord-client-ready-as-bot
  "Shortcut for the usual connect→HELLO→READY handshake. Sends HELLO
   with heartbeat_interval 45000 and a READY with a fixed session_id and
   the given bot user id. Use when the handshake isn't the focus.")

(defwhen "Discord comm send! is invoked with:" isaac.comm.discord.discord-steps/discord-comm-send!
  "Invokes Comm/send! on the active Discord integration with a delivery
   record table (path/value rows). HTTP POSTs are stubbed and recorded.")

(defwhen "Discord sends MESSAGE_CREATE:" isaac.comm.discord.discord-steps/discord-sends-message-create
  "Synthesizes an inbound MESSAGE_CREATE. Runs HTTP-post stubbing, fires
   the on-message callback, and — if routing is enabled and the message
   would create a new session — also invokes discord/process-message!
   directly. Captures :llm-request from grover.")

(defwhen #"the current session receives a completed turn with text \"([^\"]+)\""
  isaac.comm.discord.discord-steps/current-session-completed-turn-with-text
  "Invokes Comm/on-turn-end on the active Discord integration for the
   current session — for reply-path assertions without an inbound message.")

(defwhen "the test clock advances {n:int} milliseconds" isaac.comm.discord.discord-steps/test-clock-advances
  "Advances the virtual-clock scheduler attached to the discord client
   (stashed in g as :discord-clock by discord-connects), then runs a
   single scheduler tick to fire any due heartbeats.")

(defwhen "Discord stays silent for {n:int} milliseconds" isaac.comm.discord.discord-steps/discord-stays-silent-for)

(defwhen "Discord closes the connection with code {n:int}" isaac.comm.discord.discord-steps/discord-closes-connection)

(defwhen "Discord closes the connection with code {n:int} reason {reason:string}" isaac.comm.discord.discord-steps/discord-closes-connection-with-reason)

(defthen "the Discord client sends IDENTIFY:" isaac.comm.discord.discord-steps/discord-sends-identify)

(defthen "the Discord client sends RESUME:" isaac.comm.discord.discord-steps/discord-sends-resume)

(defthen "the Discord client sends HEARTBEAT" isaac.comm.discord.discord-steps/discord-sends-heartbeat)

(defthen "the Discord client is connected" isaac.comm.discord.discord-steps/discord-client-connected)

(defthen "the Discord client is disconnected" isaac.comm.discord.discord-steps/discord-client-disconnected)

(defthen "the Discord client accepted a message with:" isaac.comm.discord.discord-steps/discord-client-accepted-message)

(defthen "the Discord client accepted no messages" isaac.comm.discord.discord-steps/discord-client-accepted-no-messages)

(defthen "the EDN file \"{path}\" matches:" isaac.comm.discord.discord-steps/edn-file-matches)

(defthen "a Discord outbound HTTP request to {url:string} matches:"
  isaac.comm.discord.discord-steps/discord-outbound-http-request-to-url-matches
  "Matches a stubbed outbound HTTP request recorded by discord comm steps
   (same DSL as provider HTTP assertions).")

;; endregion ^^^^^ Routing ^^^^^
