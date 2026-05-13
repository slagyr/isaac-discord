(ns isaac.server.discord-app-spec
  (:require
    [isaac.api :as api]
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as discord-gateway]
    [isaac.config.change-source :as change-source]
    [isaac.fs :as fs]
    [isaac.server.app :as sut]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(describe "Server app — Discord integration"

  (helper/with-captured-logs)

  (before-all (api/register-comm! "discord" discord/make))
  (after (sut/stop!))

  (it "connects Discord gateway on startup when token is present"
    (let [connected (atom nil)
          stopped   (atom nil)]
      (with-redefs [discord/connect!      (fn [opts] (reset! connected opts) {:client ::discord-client})
                    discord-gateway/stop! (fn [client] (reset! stopped client))]
        (sut/start! {:port               0
                     :state-dir          "/tmp/isaac"
                     :cfg                {:comms {:discord {:token "test-token"}}}
                     :start-http-server? false})
        (sut/stop!))
      (should= "/tmp/isaac" (:state-dir @connected))
      (should= ::discord-client @stopped)))

  (it "does not connect Discord gateway on startup when no token is configured"
    (let [connected (atom false)]
      (with-redefs [discord/connect! (fn [_] (reset! connected true) {:client nil})]
        (sut/start! {:port               0
                     :state-dir          "/tmp/isaac"
                     :cfg                {}
                     :start-http-server? false})
        (sut/stop!))
      (should= false @connected)))

  (it "connects Discord gateway when token is added via config hot-reload"
    (let [source    (change-source/memory-source "/tmp/isaac-discord")
          connected (atom nil)]
      (binding [fs/*fs* (fs/mem-fs)]
        (fs/mkdirs "/tmp/isaac-discord/.isaac/config")
        (fs/spit "/tmp/isaac-discord/.isaac/config/isaac.edn"
                 (pr-str {:comms {:discord {}}}))
        (with-redefs [discord/connect!      (fn [opts] (reset! connected opts) {:client ::discord-client})
                      discord-gateway/stop! (fn [_] nil)]
          (sut/start! {:cfg                  {:comms {:discord {}}}
                       :config-change-source source
                       :state-dir            "/tmp/isaac-discord/.isaac"
                       :port                 0
                       :start-http-server?   false})
          (fs/spit "/tmp/isaac-discord/.isaac/config/isaac.edn"
                   (pr-str {:comms {:discord {:token "new-token"}}}))
          (change-source/notify-path! source "/tmp/isaac-discord/.isaac/config/isaac.edn")
          (helper/await-condition #(some? @connected))
          (sut/stop!)))
      (should= "/tmp/isaac-discord/.isaac" (:state-dir @connected))))

  (it "disconnects Discord gateway when token is removed via config hot-reload"
    (let [source  (change-source/memory-source "/tmp/isaac-discord")
          stopped (atom nil)]
      (binding [fs/*fs* (fs/mem-fs)]
        (fs/mkdirs "/tmp/isaac-discord/.isaac/config")
        (fs/spit "/tmp/isaac-discord/.isaac/config/isaac.edn"
                 (pr-str {:comms {:discord {:token "old-token"}}}))
        (with-redefs [discord/connect!      (fn [_] {:client ::discord-client})
                      discord-gateway/stop! (fn [client] (reset! stopped client))]
          (sut/start! {:cfg                  {:comms {:discord {:token "old-token"}}}
                       :config-change-source source
                       :state-dir            "/tmp/isaac-discord/.isaac"
                       :port                 0
                       :start-http-server?   false})
          (fs/spit "/tmp/isaac-discord/.isaac/config/isaac.edn"
                   (pr-str {:comms {:discord {}}}))
          (change-source/notify-path! source "/tmp/isaac-discord/.isaac/config/isaac.edn")
          (helper/await-condition #(some? @stopped))
          (sut/stop!)))
      (should= ::discord-client @stopped)))

  (it "does not reconnect Discord gateway when token is unchanged on config hot-reload"
    (let [source        (change-source/memory-source "/tmp/isaac-discord")
          connect-count (atom 0)]
      (binding [fs/*fs* (fs/mem-fs)]
        (fs/mkdirs "/tmp/isaac-discord/.isaac/config/crew")
        (fs/spit "/tmp/isaac-discord/.isaac/config/isaac.edn"
                 (pr-str {:comms {:discord {:token "stable-token"}}}))
        (fs/spit "/tmp/isaac-discord/.isaac/config/crew/main.edn"
                 (pr-str {:soul "old"}))
        (with-redefs [discord/connect!      (fn [_] (swap! connect-count inc) {:client ::discord-client})
                      discord-gateway/stop! (fn [_] nil)]
          (sut/start! {:cfg                  {:comms {:discord {:token "stable-token"}}}
                       :config-change-source source
                       :state-dir            "/tmp/isaac-discord/.isaac"
                       :port                 0
                       :start-http-server?   false})
          (fs/spit "/tmp/isaac-discord/.isaac/config/crew/main.edn"
                   (pr-str {:soul "new"}))
          (change-source/notify-path! source "/tmp/isaac-discord/.isaac/config/crew/main.edn")
          (helper/await-condition #(= "new" (get-in (sut/current-config) [:crew "main" :soul])))
          (sut/stop!)))
      (should= 1 @connect-count))))
