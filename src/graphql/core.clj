(ns graphql.core
  (:require
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.lacinia.schema :as schema]
    [io.pedestal.http :as http]
    [com.walmartlabs.lacinia.pedestal2 :as lp]
    [com.walmartlabs.lacinia.pedestal :refer [inject]]
    [graphql.pubsub :as pubsub]))

(defonce chatrooms
  (atom {1 {:title "Abc"
            :id 1}
         2 {:title "Xyz"
            :id 2}}))

(defn chatroom-by-id
  [context {:keys [id] :as args} _]
  (@chatrooms id))

(defn rename-chatroom!
  [_ {:keys [id title]} _]
  (swap! chatrooms assoc-in [id :title] title)
  (pubsub/dispatch! [:chatroom :title id])
  (@chatrooms id))

(defn chatroom-streamer
  [{connection-params :com.walmartlabs.lacinia/connection-params} {:keys [id]} source-stream]
  (println "connection-params" connection-params)
  (if (= "some-value" (:token connection-params)) ;; TODO real auth logic
    (pubsub/subscribe
      (fn [[_ _ id]]
        (source-stream (@chatrooms id))))
    (fn [])))

(def schema
  {:objects
   {:Chatroom
    {:fields {:title {:type 'String}
              :id {:type 'Int}}}}

   :queries
   {:chatroom_by_id
    {:type :Chatroom
     :args {:id {:type 'Int}}
     :resolve :query/chatroom-by-id}}

   :mutations
   {:rename_chatroom
    {:type :Chatroom
     :args {:id {:type 'Int}
            :title {:type 'String}}
     :resolve :mutation/rename-chatroom}}

   :subscriptions
   {:chatroom
    {:type :Chatroom
     :args {:id {:type 'Int}}
     :stream :stream/chatroom}}})

(def auth-interceptor
  {:name ::auth-interceptor
   :enter (fn [{{:keys [headers uri request-method] :as request} :request :as context}]
            (if (= "some-value" (headers "token")) ;; TODO real auth logic
              (assoc-in context [:request :auth-info]
                {:user-id 1234} ;; TODO whatever info you want to pass to the resolvers
                )
              (assoc context :response
                {:status 403
                 :headers {}
                 :body {:errors [{:message "Forbidden"}]}})))})

(defn subscription-auth
  [context request response]
  ;; assuming that some token is passed as a query parameter
  (let [token (some->> (.get (.getParameterMap request) "token")
                       first)]
    (if (= token "token") ;; TODO real auth
      (assoc-in context [:request :lacinia-app-context :request :auth-info]
        {:user-id 1234} ;; TODO set this to pass data to resolver
        )
      (do
        (.sendForbidden response "Forbidden")
        context))))

(def pedestal-service
  (let [compiled-schema (-> schema
                            (util/attach-resolvers
                              {:query/chatroom-by-id chatroom-by-id
                               :mutation/rename-chatroom rename-chatroom!})
                            (util/attach-streamers
                              {:stream/chatroom chatroom-streamer})
                            schema/compile)
        api-path "/api"
        ide-path "/ide"
        asset-path "/assets/graphiql"
        subscriptions-path "/ws"
        app-context {}
        api-interceptors (-> (lp/default-interceptors compiled-schema app-context)
                             (inject auth-interceptor :before :com.walmartlabs.lacinia.pedestal2/body-data))]
    (-> {:env :dev
         ::http/routes (into #{[api-path :post api-interceptors :route-name ::graphql-api]
                               [ide-path :get (lp/graphiql-ide-handler
                                                {:api-path api-path
                                                 :asset-path asset-path
                                                 :subscriptions-path subscriptions-path
                                                 ;; can have the IDE include certain headers
                                                 ;; probably don't want this in production
                                                 :ide-headers {"token" "some-value"
                                                               "lacinia-tracing" "true"}
                                                 ;; subs can be authenticated by an initial message
                                                 ;; here we force the IDE to pass this as initial message
                                                 ;; is received by stream resolver in (:com.walmartlabs.lacinia/connection-params context)
                                                 :ide-connection-params {"token" "some-valuex"}
                                                 })
                                :route-name ::graphiql-ide]}
                             (lp/graphiql-asset-routes asset-path))
         ::http/port 8934
         ::http/host "localhost"
         ::http/type :jetty
         ::http/join? false}
        lp/enable-graphiql
        (lp/enable-subscriptions compiled-schema {:subscriptions-path subscriptions-path
                                                  ;; if authenticating the websocket via some query-param (preferred):
                                                  #_#_:init-context subscription-auth})
        http/create-server)))

(defn -main
  [& _]
  (http/start pedestal-service))

#_(http/start pedestal-service)
#_(http/stop pedestal-service)

;; http://localhost:8934/ide

;; query { chatroom_by_id(id: 1) { id, title } }

;; mutation { rename_chatroom(id: 1, title: "test") { id, title}}

;; subscription { chatroom(id: 1) { title } }
