(ns isaac.comm.discord.service-spec
  (:require
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.comm.discord.service :as sut]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.server.app :as server-app]
    [speclj.core :refer :all]))

(defn- fake-connect! [sent callbacks*]
  (fn [_url callbacks]
    (reset! callbacks* callbacks)
    {:callback-driven? true
     :close!           (fn [] nil)
     :send!            (fn [payload] (swap! sent conj payload))}))

(describe "Discord service watchdog"

  (before
    (log/set-output! :memory)
    (log/clear-entries!))

  (it "starts the watchdog when a comm registers on a running server"
    (let [clock (test-clock/make)
          sch   (:scheduler clock)]
      (with-redefs [server-app/running? (constantly true)]
        (nexus/-with-nested-nexus {:scheduler sch :fs (fs/mem-fs)}
          (let [sent       (atom [])
                callbacks* (atom nil)
                di         (discord/integration {:root "/tmp/discord-watchdog"
                                                   :connect-ws! (fake-connect! sent callbacks*)})]
            (sut/register-comm! di)
            (should (contains? (set (map :event (log/get-entries)))
                                :discord.watchdog/started))
            (scheduler/cancel! sch :discord.service/watchdog))))))

  (it "logs periodic watchdog checks while the gateway stays disconnected"
    (let [clock (test-clock/make)
          sch   (:scheduler clock)
          connect! (fn [_url _callbacks]
                     {:callback-driven? true
                      :close!           (fn [] nil)
                      :send!            (fn [_] nil)})
          di    (discord/integration {:root "/tmp/discord-watchdog-check"
                                      :connect-ws! connect!})]
      (reset! (.-cfg di) {:discord/token "tok"})
      (with-redefs [server-app/running? (constantly true)
                    gateway/connected?   (constantly false)]
        (nexus/-with-nested-nexus {:scheduler sch :fs (fs/mem-fs)}
          (sut/register-comm! di)
          (test-clock/advance! clock 60000)
          (should (contains? (set (map :event (log/get-entries)))
                              :discord.watchdog/check))
          (scheduler/cancel! sch :discord.service/watchdog))))))