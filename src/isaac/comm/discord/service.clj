(ns isaac.comm.discord.service
  (:require
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.logger :as log]
    [isaac.service.factory :as factory]
    [isaac.service.protocol :as protocol]
    [isaac.service.registry :as registry]))

(deftype DiscordRegistration [comm-impl]
  Object
  (equals [this other]
    (and (instance? DiscordRegistration other)
         (identical? comm-impl (.-comm-impl ^DiscordRegistration other))))
  (hashCode [_]
    (System/identityHashCode comm-impl)))

(defn- make-registration [comm-impl]
  (DiscordRegistration. comm-impl))

(defn- connect-registration! [reg]
  (let [comm-impl   (.-comm-impl reg)
        slice       @(.-cfg comm-impl)
        state-dir   (.-state-dir comm-impl)
        connect-ws! (.-connect-ws! comm-impl)]
    (when (and slice (:discord/token slice) state-dir (nil? (:client (discord/client comm-impl))))
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

(defn- update-allow-from! [reg old-slice new-slice]
  (when-let [comm-impl (.-comm-impl reg)]
    (let [old-token (:discord/token old-slice)
          new-token (:discord/token new-slice)
          current   (:client (discord/client comm-impl))]
      (cond
        (and (not old-token) new-token)
        (connect-registration! reg)

        (and old-token (not new-token))
        (disconnect-registration! reg)

        (and new-token current)
        (gateway/update-allow-from! current
                                    {:allow-from-users  (get-in new-slice [:discord/allow-from :users])
                                     :allow-from-guilds (get-in new-slice [:discord/allow-from :guilds])})))))

(deftype DiscordService [running?*]
  protocol/Service
  (start [_]
    (reset! running?* true)
    (doseq [reg (registry/registrations-for :discord)]
      (connect-registration! reg)))
  (stop [_]
    (doseq [reg (registry/registrations-for :discord)]
      (disconnect-registration! reg))
    (reset! running?* false)))

(defn- service-running? []
  (when-let [^DiscordService svc (registry/instance-for :discord)]
    @(.-running?* svc)))

(defn- on-register! [reg]
  (when (service-running?)
    (connect-registration! reg)))

(defn- on-update! [reg old-slice new-slice]
  (when (service-running?)
    (update-allow-from! reg old-slice new-slice)))

(defn- on-remove! [reg]
  (when (service-running?)
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
  (->DiscordService (atom false)))