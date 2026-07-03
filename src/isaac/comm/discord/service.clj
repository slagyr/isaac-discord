(ns isaac.comm.discord.service
  (:require
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.server.app :as server-app]
    [isaac.service.factory :as factory]
    [isaac.service.protocol :as protocol]
    [isaac.service.registry :as registry]))

(defonce ^:private watchdog-stale-since (atom {}))

(declare server-running? reconcile-registration!)

(deftype DiscordRegistration [comm-impl]
  Object
  (equals [this other]
    (and (instance? DiscordRegistration other)
         (identical? comm-impl (.-comm-impl ^DiscordRegistration other))))
  (hashCode [_]
    (System/identityHashCode comm-impl)))

(defn- make-registration [comm-impl]
  (DiscordRegistration. comm-impl))

(defn- discord-token [slice]
  (or (:discord/token slice)
      (get-in slice [:discord :token])))

(defn- connect-registration! [reg]
  (let [comm-impl   (.-comm-impl reg)
        slice       @(.-cfg comm-impl)
        state-dir   (.-state-dir comm-impl)
        connect-ws! (.-connect-ws! comm-impl)]
    (when (and slice (discord-token slice) state-dir (nil? (:client (discord/client comm-impl))))
      (log/info :discord.client/started)
      (let [result (discord/connect! {:cfg-overrides {:comms {:discord slice}}
                                      :comm-impl     comm-impl
                                      :state-dir     state-dir
                                      :connect-ws!   connect-ws!})]
        (reset! (.-conn comm-impl) {:client (:client result)})))))

(defn- disconnect-registration! [reg]
  (when-let [comm-impl (.-comm-impl reg)]
    (when-let [current (:client (discord/client comm-impl))]
      (gateway/stop! current)
      (reset! (.-conn comm-impl) nil)
      (log/info :discord.client/stopped))))

(defn- reconcile-registration! [reg]
  ;; Drive the registration to its DESIRED state from the comm's *current*
  ;; slice and *actual* client, rather than diffing old/new tokens. Hot-reload
  ;; can churn the comm instance: a reload may install a fresh DiscordIntegration
  ;; (conn = nil) into the nexus even when the token is unchanged, so an
  ;; old-vs-new token diff would wrongly conclude "already connected" and leave
  ;; the live (nexus) instance with no client. Reconciling on the actual client
  ;; presence makes connect/disconnect idempotent and instance-independent:
  ;; connect when a token is present but this instance has no client; disconnect
  ;; when the token is gone but a client remains; otherwise just refresh
  ;; allow-from on the live client (no reconnect, so no started/stopped log).
  (when-let [comm-impl (.-comm-impl reg)]
    (let [slice   @(.-cfg comm-impl)
          token   (discord-token slice)
          current (:client (discord/client comm-impl))]
      (cond
        (and token (nil? current))
        (connect-registration! reg)

        (and (not token) current)
        (disconnect-registration! reg)

        (and token current)
        (gateway/update-allow-from! current
                                    {:allow-from-users  (get-in slice [:discord/allow-from :users])
                                     :allow-from-guilds (get-in slice [:discord/allow-from :guilds])})))))

(defn- start-liveness-watchdog! [running?* task-id*]
  (when-let [sch (nexus/get :scheduler)]
    (let [id (scheduler/every! sch 60000
                               (fn [_]
                                 (when @running?*
                                   (doseq [reg (registry/registrations-for :discord)]
                                     (try
                                       (when-let [comm-impl (.-comm-impl reg)]
                                         (when-let [current (:client (discord/client comm-impl))]
                                           (let [key (System/identityHashCode current)
                                                 now (System/currentTimeMillis)]
                                             (if (gateway/connected? current)
                                               (swap! watchdog-stale-since dissoc key)
                                               (let [since (or (get @watchdog-stale-since key)
                                                               (do (swap! watchdog-stale-since assoc key now)
                                                                   now))]
                                                 (when (> (- now since) (* 5 60 1000))
                                                   (log/warn :discord.watchdog/stale-connection
                                                             :duration-ms (- now since)
                                                             :forcing-reconnect true)
                                                   (gateway/stop! current)
                                                   (reset! (.-conn comm-impl) nil)
                                                   (swap! watchdog-stale-since dissoc key)
                                                   (when (server-running?)
                                                     (reconcile-registration! reg))))))))
                                       (catch Exception e
                                         (log/ex :discord.watchdog/check-failed e)))))))]
      (reset! task-id* id))))

(deftype DiscordService [running?* watchdog-task-id*]
  protocol/Service
  (start [_]
    (reset! running?* true)
    (doseq [reg (registry/registrations-for :discord)]
      (connect-registration! reg))
    (start-liveness-watchdog! running?* watchdog-task-id*))
  (stop [_]
    (when-let [id @watchdog-task-id*]
      (when-let [sch (nexus/get :scheduler)]
        (scheduler/cancel! sch id))
      (reset! watchdog-task-id* nil))
    (doseq [reg (registry/registrations-for :discord)]
      (disconnect-registration! reg))
    (reset! running?* false)))

(defn- server-running? []
  ;; Gate hot-reload connect/disconnect on whether the *server* is actually
  ;; booted, not on whether the Discord *service instance* is running. Discord
  ;; uses lazy module activation: a NO-token boot never activates the discord
  ;; module, so no DiscordService is registered/started — yet a token added on a
  ;; running server must still connect. server-app/running? is set only at the
  ;; end of a real app/start! boot and cleared on stop!, so a bare CLI config
  ;; reload (no server boot) stays a no-op while a running server connects
  ;; deterministically. Boot-time connects still flow through DiscordService
  ;; start (service-runtime/start-all!), which runs before running? flips true.
  (server-app/running?))

(defn- on-register! [reg]
  (when (server-running?)
    (reconcile-registration! reg)))

(defn- on-update! [reg _old-slice _new-slice]
  (when (server-running?)
    (reconcile-registration! reg)))

(defn- on-remove! [reg]
  (when (server-running?)
    (disconnect-registration! reg)))

(defn register-comm! [comm-impl]
  (let [reg (make-registration comm-impl)]
    (registry/register! :discord reg)
    (on-register! reg)
    reg))

(defn update-comm! [comm-impl old-slice new-slice]
  (let [reg (make-registration comm-impl)]
    (registry/register! :discord reg)
    (on-update! reg old-slice new-slice)
    reg))

(defn unregister-comm! [comm-impl]
  (let [reg (make-registration comm-impl)]
    (on-remove! reg)
    (registry/deregister! :discord reg)))

(defmethod factory/create :discord [_ _ctx]
  (->DiscordService (atom false) (atom nil)))