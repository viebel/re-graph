(ns re-graph.internals
  (:require [re-frame.core :as re-frame]
            [re-frame.interceptor :refer [->interceptor get-coeffect assoc-coeffect update-coeffect enqueue]]
            [re-frame.std-interceptors :as rfi]
            [re-frame.interop :refer [empty-queue]]
            [re-graph.logging :as log]
            #?@(:cljs [[cljs-http.client :as http]
                       [cljs-http.core :as http-core]]
                :clj  [[clj-http.client :as http]])
            #?(:cljs [clojure.core.async :as a]
               :clj  [clojure.core.async :refer [go] :as a])
            #?(:clj [gniazdo.core :as ws])
            #?(:clj [cheshire.core :as json]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  #?(:clj (:import [java.util UUID])))

(def default-instance-name ::default)

(def destroyed-instance ::destroyed-instance)

(defn- cons-interceptor [ctx interceptor]
  (update ctx :queue #(into (into empty-queue [interceptor]) %)))

(defn- encode [obj]
  #?(:cljs (js/JSON.stringify (clj->js obj))
     :clj (json/encode obj)))

(defn- message->data [m]
  #?(:cljs (-> (aget m "data")
               (js/JSON.parse)
               (js->clj :keywordize-keys true))
     :clj (json/decode m keyword)))

(defn generate-query-id []
  #?(:cljs (.substr (.toString (Math/random) 36) 2 8)
     :clj (str (UUID/randomUUID))))

(defn- ensure-query-id [event-name trimmed-event]
  (if (contains? #{:re-graph.core/query :re-graph.core/mutate} event-name)
    (if (= 3 (count trimmed-event)) ;; query, variables, callback-event
      (vec (cons (generate-query-id) trimmed-event))
      trimmed-event)
    trimmed-event))

(def re-graph-instance
  (->interceptor
   :id ::instance
   :before (fn [ctx]
             (let [re-graph  (:re-graph (get-coeffect ctx :db))
                   event (get-coeffect ctx :event)
                   provided-instance-name (first event)
                   instance-name (if (contains? re-graph provided-instance-name) provided-instance-name default-instance-name)
                   instance (get re-graph instance-name)
                   event-name (first (get-coeffect ctx ::rfi/untrimmed-event))
                   trimmed-event (->> (if (= provided-instance-name instance-name)
                                        (subvec event 1)
                                        event)
                                      (ensure-query-id event-name))]

               (cond
                 (= instance ::destroyed-instance)
                 ctx

                 instance
                 (-> ctx
                     (assoc-coeffect :instance instance)
                     (assoc-coeffect :instance-name instance-name)
                     (assoc-coeffect :dispatchable-event (into [event-name instance-name] trimmed-event))
                     (cons-interceptor (rfi/path :re-graph instance-name))
                     (assoc-coeffect :event trimmed-event))

                 :default
                 (do (log/error "No default instance of re-graph found but no valid instance name was provided. Valid instance names are:" (keys re-graph)
                                "but was provided with" provided-instance-name
                                "handling event" event-name)
                     ctx))))))

(def interceptors
  [re-frame/trim-v re-graph-instance])

(defn- valid-graphql-errors?
  "Validates that response has a valid GraphQL errors map"
  [response]
  (and (map? response)
       (vector? (:errors response))
       (seq (:errors response))
       (every? map? (:errors response))))

(defn- insert-http-status
  "Inserts the HTTP status into the response for 3 conditions:
   1. Response contains a valid GraphQL errors map: update the map with HTTP status
   2. Response is a map but does not contain a valid errors map: merge in default errors
   3. Response is anything else: return default errors map"
  [response status]
  (let [f (fn [errors] (mapv (fn [error] (update-in error [:extensions :status] #(or % status))) errors))
        default-errors {:errors [{:message "The HTTP call failed."
                                  :extensions {:status status}}]}]
    (cond
      (valid-graphql-errors? response) (update response :errors f)
      (map? response) (merge response default-errors)
      :else default-errors)))

(re-frame/reg-event-fx
 ::http-complete
 interceptors
 (fn [{:keys [db]} [query-id payload]]
   (let [callback-event (get-in db [:http-requests query-id :callback])]
     {:db (-> db
              (update :subscriptions dissoc query-id)
              (update :http-requests dissoc query-id))
      :dispatch (conj callback-event payload)})))

(re-frame/reg-fx
 ::call-abort
 (fn [abort-fn]
   (abort-fn)))

(re-frame/reg-event-db
 ::register-abort
 interceptors
 (fn [db [query-id abort-fn]]
   (assoc-in db [:http-requests query-id :abort] abort-fn)))

(re-frame/reg-fx
 ::send-http
 (fn [[instance-name query-id http-url {:keys [request payload]}]]
   #?(:cljs (let [response-chan (http/post http-url (assoc request :json-params payload))]
              (re-frame/dispatch [::register-abort instance-name query-id #(http-core/abort! response-chan)])

              (go (let [{:keys [status body error-code]} (a/<! response-chan)]
                    (re-frame/dispatch [::http-complete
                                        instance-name
                                        query-id
                                        (if (= :no-error error-code)
                                          body
                                          (insert-http-status body status))]))))

      :clj (let [future (http/post http-url
                                   (-> request
                                       (update :headers merge {"Content-Type" "application/json"
                                                               "Accept" "application/json"})
                                       (merge {:body (encode payload)
                                               :as :json
                                               :coerce :always
                                               :async? true
                                               :throw-exceptions false
                                               :throw-entire-message? true}))
                                   (fn [{:keys [status body]}]
                                     (re-frame/dispatch [::http-complete
                                                         instance-name
                                                         query-id
                                                         (if (http/unexceptional-status? status)
                                                           body
                                                           (insert-http-status body status))]))
                                   (fn [exception]
                                     (let [{:keys [status body]} (ex-data exception)]
                                       (re-frame/dispatch [::http-complete instance-name query-id (insert-http-status body status)]))))]
             (re-frame/dispatch [::register-abort instance-name query-id #(.cancel future)])))))

(re-frame/reg-fx
 ::send-ws
 (fn [[websocket payload]]
   #?(:cljs (.send websocket (encode payload))
      :clj (ws/send-msg websocket (encode payload)))))

(re-frame/reg-fx
 ::call-callback
 (fn [[callback-fn payload]]
   (callback-fn payload)))

(re-frame/reg-event-fx
 ::callback
 (fn [_ [_ callback-fn payload]]
   {::call-callback [callback-fn payload]}))

(re-frame/reg-event-fx
 ::on-ws-data
 interceptors
 (fn [{:keys [db] :as cofx} [subscription-id payload :as event]]
   (if-let [callback-event (get-in db [:subscriptions (name subscription-id) :callback])]
     {:dispatch (conj callback-event payload)}
     (log/warn "No callback-event found for subscription" subscription-id))))

(re-frame/reg-event-db
 ::on-ws-complete
 interceptors
 (fn [db [subscription-id]]
   (update-in db [:subscriptions] dissoc (name subscription-id))))

(re-frame/reg-event-fx
 ::connection-init
 interceptors
  (fn [{:keys [db]} _]
    (let [ws (get-in db [:websocket :connection])
          payload (get-in db [:websocket :connection-init-payload])]
      (when payload
        {::send-ws [ws {:type "connection_init"
                        :payload payload}]}))))

(re-frame/reg-event-fx
 ::on-ws-open
 interceptors
 (fn [{:keys [db instance-name]} [ws]]
   (merge
    {:db (update db :websocket
                    assoc
                    :connection ws
                    :ready? true
                    :queue [])}

    (let [resume? (get-in db [:websocket :resume-subscriptions?])
          subscriptions (when resume? (->> db :subscriptions vals (map :event)))
          queue (get-in db [:websocket :queue])
          to-send (concat [[::connection-init instance-name]] subscriptions queue)]
      {:dispatch-n to-send}))))

(defn- deactivate-subscriptions [subscriptions]
  (reduce-kv (fn [subs sub-id sub]
               (assoc subs sub-id (assoc sub :active? false)))
             {}
             subscriptions))

(re-frame/reg-event-fx
 ::on-ws-close
 interceptors
 (fn [{:keys [db instance-name]} _]
   (merge
    {:db (let [new-db (-> db
                          (assoc-in [:websocket :ready?] false)
                          (update :subscriptions deactivate-subscriptions))]
           new-db)}
    (when-let [reconnect-timeout (get-in db [:websocket :reconnect-timeout])]
      {:dispatch-later [{:ms reconnect-timeout
                         :dispatch [::reconnect-ws instance-name]}]}))))

(defn- on-ws-message [instance-name]
  (fn [m]
    (let [{:keys [type id payload] :as data} (message->data m)]
      (condp = type
        "data"
        (re-frame/dispatch [::on-ws-data instance-name id payload])

        "complete"
        (re-frame/dispatch [::on-ws-complete instance-name id])

        "error"
        (re-frame/dispatch [::on-ws-data instance-name id {:errors payload}])

        (log/debug "Ignoring graphql-ws event " instance-name " - " type)))))

(defn- on-open
  ([instance-name]
   (fn [ws]
     ((on-open instance-name ws))))
  ([instance-name ws]
   (fn []
     (re-frame/dispatch [::on-ws-open instance-name ws]))))

(defn- on-close [instance-name]
  (fn [& args]
    (re-frame/dispatch [::on-ws-close instance-name])))

(defn- on-error [instance-name]
  (fn [e]
    (log/warn "GraphQL websocket error" instance-name e)))

(re-frame/reg-event-fx
 ::reconnect-ws
 interceptors
 (fn [{:keys [db instance-name]} _]
   (when-not (get-in db [:websocket :ready?])
     {::connect-ws [instance-name (get-in db [:websocket :url]) (get-in db [:websocket :sub-protocol])]})))

(re-frame/reg-fx
 ::connect-ws
 (fn [[instance-name ws-url sub-protocol]]
   #?(:cljs (let [ws (js/WebSocket. ws-url sub-protocol)]
              (aset ws "onmessage" (on-ws-message instance-name))
              (aset ws "onopen" (on-open instance-name ws))
              (aset ws "onclose" (on-close instance-name))
              (aset ws "onerror" (on-error instance-name)))
      :clj (let [ws (ws/connect ws-url
                                :on-receive (on-ws-message instance-name)
                                :on-close (on-close instance-name)
                                :on-error (on-error instance-name)
                                :subprotocols [sub-protocol])]
             ((on-open instance-name ws))))))

(re-frame/reg-fx
 ::disconnect-ws
 (fn [[ws]]
   #?(:cljs (.close ws)
      :clj (ws/close ws))))

(defn default-ws-url []
  #?(:cljs
     (when (and (exists? js/window) (exists? (.-location js/window)))
       (let [host-and-port (.-host js/window.location)
             ssl? (re-find #"^https" (.-origin js/window.location))]
         (str (if ssl? "wss" "ws") "://" host-and-port "/graphql-ws")))
     :clj nil))

#?(:clj
   (defn sync-wrapper
     "Wraps the given function to allow the GraphQL result to be returned
      synchronously. Will return a GraphQL error response if no response is
      received before the timeout (default 3000ms) expires. Will throw if the
      call returns an exception."
     [f & args]
     (let [timeout  (when (int? (last args)) (last args))
           timeout' (or timeout 3000)
           p        (promise)
           callback (fn [result] (deliver p result))
           args'    (conj (vec (if timeout (butlast args) args))
                          callback)]
       (apply f args')

       ;; explicit timeout to avoid unreliable aborts from underlying implementations
       (let [result (deref p timeout' ::timeout)]
         (if (= ::timeout result)
           {:errors [{:message "re-graph did not receive response from server"
                      :timeout timeout'
                      :args args}]}
           result)))))
