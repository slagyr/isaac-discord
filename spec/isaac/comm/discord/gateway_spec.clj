(ns isaac.comm.discord.gateway-spec
  (:require
    [cheshire.core :as json]
    [isaac.comm.discord.gateway :as sut]
    [isaac.logger :as log]
    [isaac.util.ws-client :as ws]
    [speclj.core :refer :all]))

(defn- fake-connect! [sent callbacks*]
  (fn [_url callbacks]
    (reset! callbacks* callbacks)
    {:close! (fn [] nil)
     :send!  (fn [payload] (swap! sent conj payload))}))

(describe "Discord gateway"

  (before
    (log/set-output! :memory)
    (log/clear-entries!))

  (it "sends IDENTIFY after receiving HELLO"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (should= 2 (:op (first @sent)))
      (should= "test-token" (get-in (first @sent) [:d :token]))
      (should= 37377 (get-in (first @sent) [:d :intents]))))

  (it "sends HEARTBEAT when virtual time advances past the interval"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      (sut/advance-time! client 45000)
      (should= 1 (:op (second @sent)))
      (should= nil (get-in (second @sent) [:d]))))

  (it "marks the client connected after READY"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 42 :d {:session_id "abc"}}))
      (should (sut/connected? client))
      (should= 42 (sut/current-sequence client))))

  (it "heartbeats with the latest sequence after dispatch events"
    (let [sent       (atom [])
          callbacks* (atom nil)
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc"}}))
      (sut/advance-time! client 45000)
      (should= 1 (:op (nth @sent 1)))
      (should= 7 (get-in (nth @sent 1) [:d]))))

  (it "logs malformed frames as errors"
    (let [sent       (atom [])
          callbacks* (atom nil)
          _client    (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! (fake-connect! sent callbacks*)})]
      ((:on-message @callbacks*) "not-json")
      (should= :discord.gateway/invalid-frame (:event (last (log/get-entries))))))

  (it "reconnects and re-identifies after a normal close"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status 1000 :reason "bye"})
      (should (sut/running? client))
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should= :discord.gateway/identify (:event (last (log/get-entries))))))

  (it "reconnects and sends RESUME for a resumable close code"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status 4000 :reason "bye"})
      (should= 2 (count @callbacks*))
      (should= 6 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))
      (should= "abc" (get-in (last @sent*) [:d :session_id]))
      (should= 7 (get-in (last @sent*) [:d :seq]))))

  (it "reconnects and sends IDENTIFY for a non-resumable close code"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 7 :d {:session_id "abc" :user {:id "bot-default"}}}))
      ((:on-close (first @callbacks*)) {:status 4009 :reason "session timeout"})
      (should= 2 (count @callbacks*))
      (should= 2 (:op (last @sent*)))
      (should= "test-token" (get-in (last @sent*) [:d :token]))))

  (it "logs fatal close codes without reconnecting"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          _client    (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status 4004 :reason "bad token"})
      (should= 1 (count @callbacks*))
      (should= :discord.gateway/fatal-close (:event (last (log/get-entries))))))

  (it "routes resumable close when payload uses :status-code key (ws-close-payload path)"
    (let [sent*      (atom [])
          callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [payload] (swap! sent* conj payload))})
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-message (first @callbacks*)) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
      ((:on-message (first @callbacks*)) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "sess" :user {:id "bot"}}}))
      ((:on-close (first @callbacks*)) {:status-code 4000 :reason "stale"})
      (should= 2 (count @callbacks*))
      (should= 6 (:op (last @sent*)))))

  (it "routes fatal close when payload uses :status-code key (ws-close-payload path)"
    (let [callbacks* (atom [])
          connect!   (fn [_url callbacks]
                       (swap! callbacks* conj callbacks)
                       {:close! (fn [] nil) :send! (fn [_] nil)})
          _client    (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      ((:on-close (first @callbacks*)) {:status-code 4004 :reason "bad token"})
      (should= 1 (count @callbacks*))
      (should= :discord.gateway/fatal-close (:event (last (log/get-entries))))))

  (it "logs structured gateway errors from callback-driven transports"
    (let [callbacks* (atom nil)
          connect!   (fn [_url callbacks]
                       (reset! callbacks* callbacks)
                       {:close! (fn [] nil)
                        :send!  (fn [_] nil)})
          error      (ex-info "connection reset" {:socket :discord})]
      (log/capture-logs
        (sut/connect! {:token       "test-token"
                       :clock-mode  :virtual
                       :connect-ws! connect!})
        ((:on-error @callbacks*) error)
        (should= [{:level         :error
                   :event         :discord.gateway/error
                   :ex-class      "ExceptionInfo"
                   :error-message "connection reset"
                   :payload       {:message "connection reset"
                                   :class   "clojure.lang.ExceptionInfo"
                                   :data    {:socket :discord}}}]
                 (->> @log/captured-logs
                      (filter #(= :discord.gateway/error (:event %)))
                      (mapv #(select-keys % [:level :event :ex-class :error-message :payload])))))))

  (it "treats polling transport error maps as structured gateway errors"
    (let [messages*  (atom [{:error (ex-info "boom" {:source :socket})}])
          transport  {:close! (fn [] nil)
                      :send!  (fn [_] nil)}
          connect!   (fn [_url _callbacks] transport)
          client     (sut/connect! {:token       "test-token"
                                    :clock-mode  :virtual
                                    :connect-ws! connect!})]
      (with-redefs [sut/transport-receive! (fn [_]
                                             (let [message (first @messages*)]
                                               (swap! messages* rest)
                                               message))
                    ws/ws-close-payload    (fn [_] {:status-code 4000 :reason "resume"})]
        (log/capture-logs
          (#'sut/start-reader-loop! client transport)
          (loop [n 0]
            (when (and (< n 1000) (nil? (:disconnect @(:state client))))
              (Thread/sleep 1)
              (recur (inc n))))
          (should= {:status-code 4000 :reason "resume"}
                   (:disconnect @(:state client)))
          (let [entries (->> @log/captured-logs
                             (filter #(contains? #{:discord.gateway/error :discord.gateway/disconnected} (:event %)))
                             (take 2)
                             (mapv #(select-keys % [:event :payload])))]
            (should= #{:discord.gateway/error :discord.gateway/disconnected}
                     (set (map :event entries)))
            (should (some #(= {:event :discord.gateway/error
                               :payload {:message "boom"
                                         :class   "clojure.lang.ExceptionInfo"
                                         :data    {:source :socket}}}
                              %)
                          entries))
            (should (some #(= {:event :discord.gateway/disconnected
                               :payload {:status-code 4000 :reason "resume"}}
                              %)
                          entries)))))))

  (describe "message intake"

    (it "accepts MESSAGE_CREATE from an allowed user and guild"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :clock-mode       :virtual
                                      :allow-from-users ["123456"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "123456"} :content "hello"}}))
        (should= [{:channel_id "999001" :guild_id "789012" :author {:id "123456"} :content "hello"}] (sut/accepted-messages client))))

    (it "accepts guild post from any author when the guild is allowlisted"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token             "test-token"
                                      :clock-mode        :virtual
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "any-user"} :content "hello"}}))
        (should= 1 (count (sut/accepted-messages client)))))

    (it "accepts DM when the author is allowlisted"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :clock-mode       :virtual
                                      :allow-from-users ["274692"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555001" :author {:id "274692"} :content "dm hi"}}))
        (should= 1 (count (sut/accepted-messages client)))))

    (it "ignores MESSAGE_CREATE from a guild not on the allow list"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :clock-mode       :virtual
                                      :allow-from-users ["123456"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot-default"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "888888" :author {:id "123456"} :content "hello"}}))
        (should= [] (sut/accepted-messages client))))

    (it "ignores the bot's own MESSAGE_CREATE events"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token            "test-token"
                                      :clock-mode       :virtual
                                      :allow-from-users ["555"]
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "555"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "555"} :content "echo"}}))
        (should= [] (sut/accepted-messages client))))

    (it "logs :guild rejection when guild post is from a non-allowlisted guild"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token             "test-token"
                                      :clock-mode        :virtual
                                      :allow-from-guilds ["789012"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "888888" :author {:id "123"} :content "hi"}}))
        (let [entry (last (log/get-entries))]
          (should= :discord.gateway/message-rejected (:event entry))
          (should= :guild (:reason entry)))))

    (it "logs :user rejection when DM is from a non-allowlisted user"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token            "test-token"
                                      :clock-mode       :virtual
                                      :allow-from-users ["274692"]
                                      :connect-ws!      (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555001" :author {:id "999999"} :content "spam"}}))
        (let [entry (last (log/get-entries))]
          (should= :discord.gateway/message-rejected (:event entry))
          (should= :user (:reason entry)))))

    (it "logs :self rejection when bot receives its own message"
      (let [sent       (atom [])
            callbacks* (atom nil)
            _client    (sut/connect! {:token             "test-token"
                                      :clock-mode        :virtual
                                      :allow-from-guilds ["789012"]
                                      :allow-from-users  ["555"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "555"}}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "999001" :guild_id "789012" :author {:id "555"} :content "echo"}}))
        (let [entry (last (log/get-entries))]
          (should= :discord.gateway/message-rejected (:event entry))
          (should= :self (:reason entry)))))

    (it "update-allow-from! applies new filter without reconnecting"
      (let [sent       (atom [])
            callbacks* (atom nil)
            client     (sut/connect! {:token             "test-token"
                                      :clock-mode        :virtual
                                      :allow-from-users  ["123"]
                                      :connect-ws!       (fake-connect! sent callbacks*)})]
        ((:on-message @callbacks*) (json/generate-string {:op 10 :d {:heartbeat_interval 45000}}))
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "READY" :s 1 :d {:session_id "abc" :user {:id "bot"}}}))
        (sut/update-allow-from! client {:allow-from-users ["123" "456"]})
        ((:on-message @callbacks*) (json/generate-string {:op 0 :t "MESSAGE_CREATE" :s 2 :d {:channel_id "555" :author {:id "456"} :content "hi"}}))
        (should= 1 (count (sut/accepted-messages client)))))))
