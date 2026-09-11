(ns isaac.comm.discord.service-spec
  (:require
    [isaac.comm.discord :as discord]
    [isaac.comm.discord.gateway :as gateway]
    [isaac.comm.discord.service :as sut]
    [isaac.comm.discord.test-clock :as test-clock]
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]
    [isaac.scheduler.runtime :as scheduler]
    [speclj.core :refer :all]))

(defn- fake-connect! [sent callbacks*]
  (fn [_url callbacks]
    (reset! callbacks* callbacks)
    {:callback-driven? true
     :close!           (fn [] nil)
     :send!            (fn [payload] (swap! sent conj payload))}))

(describe "Discord component"

  (it "implements the Foundation component lifecycle"
    (let [instance (component-factory/create :discord {})]
      (should (component/component? instance))))

  (before
    (log/set-output! :memory)
    (log/clear-entries!)
    (reset! component-registry/*registry* (component-registry/fresh-registry)))

  (it "starts the watchdog when a comm registers on a running server"
    (let [clock (test-clock/make)
          sch   (:scheduler clock)]
      (with-redefs [runner/running? (constantly true)]
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
      (with-redefs [runner/running? (constantly true)
                    gateway/connected?   (constantly false)]
        (nexus/-with-nested-nexus {:scheduler sch :fs (fs/mem-fs)}
          (sut/register-comm! di)
          (test-clock/advance! clock 60000)
          (should (contains? (set (map :event (log/get-entries)))
                              :discord.watchdog/check))
          (scheduler/cancel! sch :discord.service/watchdog)))))

  (it "stops a prior comm's pending reconnect when a new comm registers"
    (let [clock      (test-clock/make)
          sch        (:scheduler clock)
          connects*  (atom 0)
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! connects* inc)
                       (swap! callbacks* conj callbacks)
                       {:callback-driven? true
                        :close!           (fn [] nil)
                        :send!            (fn [_payload] nil)})]
      (with-redefs [runner/running? (constantly true)]
        (nexus/-with-nested-nexus {:scheduler sch :fs (fs/mem-fs)}
          (let [di1 (discord/integration {:root "/tmp/discord-prior"
                                          :connect-ws! connect!})
                di2 (discord/integration {:root "/tmp/discord-next"
                                          :connect-ws! connect!})]
            (reset! (.-cfg di1) {:discord/token "tok"})
            (reset! (.-cfg di2) {:discord/token "tok"})
            (sut/register-comm! di1)
            (should= 1 @connects*)
            ((:on-close (last @callbacks*)) {:status 1006 :reason "flap"})
            (sut/register-comm! di2)
            (test-clock/advance! clock 1000)
            (should= 2 @connects*)
            (scheduler/cancel! sch :discord.service/watchdog))))))
  )