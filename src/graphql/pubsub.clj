(ns graphql.pubsub)

(defonce state (atom nil))

(defn dispatch!
  [[type attr id :as message]]
  (reset! state message))

(defn subscribe
  "Returns fn to close subscription.
  Callback will be called with [type attr id] message."
  [callback]
  (let [watch-key (gensym "sub")]
    (add-watch state watch-key
      (fn [_ _ _ [type attr id :as new-state]]
        (callback new-state)))
    (fn []
      (remove-watch state watch-key))))
