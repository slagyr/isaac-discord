(ns isaac.comm.discord.service
  (:require
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]
    [isaac.scheduler.runtime :as scheduler]))

(defonce ^:private component-running?* (atom false))
(defonce ^:private watchdog-stale-since (atom {}))
(defonce ^:private watchdog-task-id* (atom nil))
(def ^:private watchdog-task-id :discord.service/watchdog)
(def ^:private watchdog-interval-ms 60000)
(def ^:private watchdog-stale-threshold-ms (* 5 60 1000))

(declare server-running? reconcile-registration! run-watchdog-check!)

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

(defn- force-registration-reconnect! [reg comm-impl current]
  (log/warn :discord.watchdog/stale-connection
            :forcing-reconnect true)
  (gateway/stop! current)
  (reset! (.-conn comm-impl) nil)
  (when (server-running?)
    (reconcile-registration! reg)))

(defn- run-watchdog-check! []
  (when (server-running?)
    (doseq [reg (component-registry/registrations-for :discord)]
      (try
        (when-let [comm-impl (.-comm-impl reg)]
          (when-let [current (:client (discord/client comm-impl))]
            (let [key   (System/identityHashCode current)
                  now   (System/currentTimeMillis)
                  state @(:state current)]
              (gateway/check-liveness! current)
              (if (gateway/connected? current)
                (swap! watchdog-stale-since dissoc key)
                (let [since (or (get @watchdog-stale-since key)
                                (do (swap! watchdog-stale-since assoc key now)
                                    now))
                      age   (- now since)]
                  (log/info :discord.watchdog/check
                            :connected false
                            :status (:status state)
                            :stale-ms age)
                  (when (> age watchdog-stale-threshold-ms)
                    (swap! watchdog-stale-since dissoc key)
                    (force-registration-reconnect! reg comm-impl current)))))))
        (catch Exception e
          (log/ex :discord.watchdog/check-failed e))))))

(defn- ensure-liveness-watchdog! []
  (if-let [sch (nexus/get :scheduler)]
    (do
      (scheduler/cancel! sch watchdog-task-id)
      (scheduler/schedule! sch {:id      watchdog-task-id
                                :trigger {:kind :interval :ms watchdog-interval-ms}
                                :handler (fn [_] (run-watchdog-check!))})
      (reset! watchdog-task-id* watchdog-task-id)
      (log/info :discord.watchdog/started :interval-ms watchdog-interval-ms))
    (log/warn :discord.watchdog/no-scheduler
              :reason "nexus scheduler unavailable; stale gateway recovery disabled")))

(defn- stop-liveness-watchdog! []
  (when-let [sch (nexus/get :scheduler)]
    (scheduler/cancel! sch watchdog-task-id))
  (reset! watchdog-task-id* nil)
  (reset! watchdog-stale-since {}))

(deftype DiscordComponent [running?*]
  component/Component
  (start [_]
    (reset! running?* true)
    (doseq [reg (component-registry/registrations-for :discord)]
      (connect-registration! reg))
    (ensure-liveness-watchdog!))
  (stop [_]
    (stop-liveness-watchdog!)
    (doseq [reg (component-registry/registrations-for :discord)]
      (disconnect-registration! reg))
    (reset! running?* false)))

(defn- server-running? []
  ;; Gate hot-reload connect/disconnect on whether the *server* is actually
  ;; booted, not on whether the Discord *service instance* is running. Discord
  ;; uses lazy module activation: a NO-token boot never activates the discord
  ;; module, so no DiscordComponent is registered/started — yet a token added on a
  ;; running server must still connect. runner/running? is set only at the end of
  ;; a real runner/start! boot and cleared on stop!, so a bare CLI config reload
  ;; stays a no-op while a running server connects deterministically. Boot-time
  ;; connects still flow through DiscordComponent start. Track that component
  ;; explicitly because Foundation records runner state only after every
  ;; component has started.
  (or (runner/running?) @component-running?*))

(defn- on-register! [reg]
  (when (server-running?)
    (reconcile-registration! reg)))

(defn- on-update! [reg _old-slice _new-slice]
  (when (server-running?)
    (reconcile-registration! reg)))

(defn- on-remove! [reg]
  (when (server-running?)
    (disconnect-registration! reg)))

(defn- stop-other-registrations! [^DiscordRegistration keep]
  (doseq [reg (vec (component-registry/registrations-for :discord))]
    (when-not (identical? (.-comm-impl keep)
                          (.-comm-impl ^DiscordRegistration reg))
      (disconnect-registration! reg)
      (component-registry/deregister! :discord reg))))

(defn register-comm! [comm-impl]
  (let [reg (make-registration comm-impl)]
    (stop-other-registrations! reg)
    (component-registry/register! :discord reg)
    (on-register! reg)
    (when (server-running?)
      (ensure-liveness-watchdog!))
    reg))

(defn update-comm! [comm-impl old-slice new-slice]
  (let [reg (make-registration comm-impl)]
    (stop-other-registrations! reg)
    (component-registry/register! :discord reg)
    (on-update! reg old-slice new-slice)
    reg))

(defn unregister-comm! [comm-impl]
  (let [reg (make-registration comm-impl)]
    (on-remove! reg)
    (component-registry/deregister! :discord reg)))

(defmethod component-factory/create :discord [_ _ctx]
  (->DiscordComponent component-running?*))