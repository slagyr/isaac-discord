(ns isaac.comm.discord.rest
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.logger :as log]))

(def api-base "https://discord.com/api/v10")

(def default-message-cap 2000)

(defn- split-at-cap [s cap]
  (mapv #(subs s % (min (count s) (+ % cap)))
        (range 0 (count s) cap)))

(defn- split-content [content cap]
  (let [lines (str/split (str content) #"\n" -1)]
    (loop [remaining lines
           current   nil
           chunks    []]
      (if-let [line (first remaining)]
        (let [candidate (if current (str current "\n" line) line)]
          (cond
            (<= (count candidate) cap)
            (recur (rest remaining) candidate chunks)

            current
            (recur remaining nil (conj chunks current))

            :else
            (let [parts (split-at-cap line cap)]
              (recur (rest remaining) nil (into chunks parts)))))
        (cond-> chunks current (conj current))))))

(defn- preview-body [body]
  (let [body (str body)]
    (subs body 0 (min 200 (count body)))))

(defn- post-single-message!
  [{:keys [channel-id content token]}]
  (let [url      (str api-base "/channels/" channel-id "/messages")
        payload  {:content content}
        response (http/post url {:body    (json/generate-string payload)
                                 :headers {"Authorization" (str "Bot " token)
                                           "Content-Type"  "application/json"}
                                 :throw   false})]
    (if (>= (:status response 0) 400)
      (do
        (log/error :discord.reply/http-error
                   :bodyPreview (preview-body (:body response))
                   :channelId channel-id
                   :status (:status response))
        response)
       response)))

(defn transient-response? [response]
  (let [status (:status response 0)]
    (or (= 429 status)
        (>= status 500))))

(defn post-typing!
  [{:keys [channel-id token]}]
  (http/post (str api-base "/channels/" channel-id "/typing")
             {:headers {"Authorization" (str "Bot " token)}
              :throw   false}))

(defn post-message!
  [{:keys [channel-id content message-cap token]}]
  (let [cap       (or message-cap default-message-cap)
        messages  (split-content content cap)]
    (reduce (fn [_ message]
              (post-single-message! {:channel-id channel-id :content message :token token}))
            nil
            messages)))

(defn try-send-or-enqueue!
  [{:keys [channel-id content state-dir target token message-cap] :as _opts}]
  (let [channel-id (or channel-id target)
        send-opts  {:channel-id  channel-id
                    :content     content
                    :message-cap message-cap
                    :token       token}]
    (try
      (let [response (post-message! send-opts)]
        (if (and state-dir (transient-response? response))
          {:delivery (queue/enqueue! {:comm    :discord
                                      :target  channel-id
                                      :content content})
           :queued?  true
           :status   (:status response)}
          response))
      (catch Exception e
        (if state-dir
          {:delivery (queue/enqueue! {:comm    :discord
                                      :target  channel-id
                                      :content content})
           :error    (.getMessage e)
           :queued?  true}
          (throw e))))))
