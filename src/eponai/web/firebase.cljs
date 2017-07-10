(ns eponai.web.firebase
  (:require
    [taoensso.timbre :refer [debug]]
    [om.next :as om]
    [eponai.common.format.date :as date]
    [eponai.common.shared :as shared]))


(defn request-permission [messaging & [f-success f-error]]
  (-> (.requestPermission messaging)
      (.then (fn []
               (when f-success (f-success))
               (debug "Firebase - Token access granted ")))
      (.catch (fn [err]
                (when f-error (f-error err))
                (debug "Firebase - Token access denied " err)))))

(defn get-token [messaging f]
  (-> (.getToken messaging)
      (.then (fn [token]
               (if token
                 (do
                   (f token)
                   (debug "Firebase - Received token: " token))
                 (debug "Firebase - No token avaiable, generate one"))))))

(defn on-message [messaging reconciler]
  (.onMessage messaging (fn [payload]
                          (debug "FIREBASE - Received message with payload: " payload)
                          ;(let [timestamp (date/current-millis)
                          ;      notification {:notification/id      timestamp
                          ;                    :notification/payload {:payload/title (aget payload "data" "title]")
                          ;                                           :payload/body  (aget payload "data" "message]")
                          ;                                           :payload/subtitle (aget payload "data" "subtitle]")}}]
                          ;  (om/transact! reconciler [(list 'notification/receive notification)
                          ;                            {:query/notifications [:notification/id :notification/payload]}])
                          ;  (js/setTimeout (fn [] (om/transact! reconciler [(list 'notification/remove {:id timestamp})
                          ;                                                  {:query/notifications [:notification/id :notification/payload]}])) 5000)
                          ;  )
                          )))

(defn initialize [reconciler]
  (.initializeApp js/firebase #js{:apiKey            "AIzaSyBS99Iuv0WAacB4ain4zQMahksDrt6jmJY"
                                  :authDomain        "leafy-firmament-160421.firebaseapp.com"
                                  :databaseURL       "https://leafy-firmament-160421.firebaseio.com"
                                  :projectId         "leafy-firmament-160421"
                                  :storageBucket     "leafy-firmament-160421.appspot.com"
                                  :messagingSenderId "252203166563"})
  ;; TODO enable when we want to activate Web push notifications.
  ;; We might want to think about when to ask the user's permission first.
  (comment
    (if (.-serviceWorker js/navigator)
      (.addEventListener js/window
                         "load"
                         (fn []
                           (debug "Firebase register service worker")
                           (-> (.register (.-serviceWorker js/navigator) "/lib/firebase/firebase-messaging-sw.js")
                               (.then (fn [registration]
                                        (let [messaging (.messaging js/firebase)
                                              save-token #(om/transact! reconciler [(list 'firebase/register-token {:token %})])]
                                          (.useServiceWorker messaging registration)
                                          (request-permission messaging (fn [] (get-token messaging save-token)))
                                          (.onTokenRefresh messaging (fn [] (get-token messaging save-token)))
                                          (on-message messaging reconciler))))))))))

(defn snapshot->map [snapshot]
  {:key   (.-key snapshot)
   :ref   (.-ref snapshot)
   :value (js->clj (.val snapshot) :keywordize-keys true)})

(defprotocol IFirebase
  (-ref [this path])
  (-ref-notifications [this user-id])
  (-timestamp [this])
  (-add-connected-listener [this ref {:keys [on-connect on-disconnect]}])

  (-remove-on-disconnect [this ref])

  (-limit-to-last [this n ref])

  (-set [this ref v])
  (-push [this ref])
  (-remove [this ref])
  (-update [this ref v])

  (-off [this ref])
  (-on-value-changed [this f ref])
  (-on-child-added [this f ref])
  (-on-child-removed [this f ref ]))

(defonce fb-initialized? (atom false))

(defmethod shared/shared-component [:shared/firebase :env/prod]
  [reconciler _ _]
  ;(initialize reconciler)
  ;(when-not @fb-initialized?
  ;
  ;  (reset! fb-initialized? true))
  (reify IFirebase
    (-remove-on-disconnect [this ref]
      (-> (.onDisconnect ref)
          (.remove)))
    (-timestamp [this]
      js/firebase.database.ServerValue.TIMESTAMP)
    (-add-connected-listener [this ref {:keys [on-connect on-disconnect]}]
      (let [am-online (-ref this ".info/connected")]
        (.on am-online "value" (fn [snapshot]
                                 (when (.val snapshot)
                                   (-> (.onDisconnect ref)
                                       on-disconnect)
                                   (on-connect ref))))))

    ;; Refs
    (-ref [this path]
      (-> (.database js/firebase)
          (.ref (str "v1/" path))))

    (-ref-notifications [this user-id]
      (-> (-ref this (str "notifications/" user-id))
          (.limitToLast 100)))

    (-limit-to-last [this n ref]
      (.limitToLast ref n))

    (-remove [this ref]
      (debug "FIREBASE removed: " ref)
      (.remove ref))

    (-push [this ref]
      (.push ref))
    (-set [this ref v]
      (debug "Setting value: " v)
      (.set ref v))
    (-update [this ref v]
      (.update ref v))

    (-off [this ref]
      (.off ref))

    ;; Listeners
    (-on-value-changed [this f ref]
      (.on ref "value" (fn [snapshot] (f (snapshot->map snapshot)))))
    (-on-child-added [this f ref]
      (.on ref "child_added" (fn [snapshot] (f (snapshot->map snapshot)))))
    (-on-child-removed [this f ref]
      (.on ref "child_removed" (fn [snapshot] (f (snapshot->map snapshot)))))))

(defmethod shared/shared-component [:shared/firebase :env/dev]
  [reconciler _ _]
  (reify IFirebase
    ;; Refs
    (-ref-notifications [this user-id])

    (-limit-to-last [this n ref])

    (-remove [this ref])
    (-update [this ref v])
    (-off [this ref])

    ;; Listeners
    (-on-child-added [this f ref])
    (-on-child-removed [this f ref])))