(ns eponai.server.auth.credentials
  (:require [cemerick.friend :as friend]
            [datomic.api :as d]
            [eponai.common.database.pull :as p]
            [eponai.server.http :as h]
            [eponai.common.database.transact :refer [transact transact-map]]
            [eponai.server.datomic.format :as f]
            [eponai.server.api :as api]
            [taoensso.timbre :refer [debug error info]]))

; ---- exceptions

(defn ex-invalid-input [input]
  (ex-info (str "Invalid input." input)
           {:cause ::authentication-error
            :status ::h/unprocessable-entity
            :data {:input input}}))

(defn ex-user-not-activated [user]
  (ex-info "User not activated."
           {:cause ::authentication-error
            :activate-user user}))

(defn link-fb-user-to-account [conn {:keys [user/email] :as opts}]
  ;; There's already a user account for the email provided by the FB account,
  ;; so just link the FB user to the existing account.
  (if-let [user (p/lookup-entity (d/db conn) [:user/email email])]
    (let [txs [(f/fb-user user opts)]]
      ; We already have a user account for the FB email.
      ; If we had a user with that email, loggin in with FB means it's now verified.
      (transact conn (conj txs (f/email-verification user {:verification/status :verification.status/verified}))))

    ;; No user exists, create a new user account
    (if email
      ; If we are creating an account and received an email from the FB account, create a new user with verified email.
      (transact-map conn (f/user-account-map email (assoc opts :verification/status :verification.status/verified)))
      ; Else create a new account without an email, the user will provide one when creating an account.
      (transact-map conn (f/user-account-map nil opts)))))

(defn auth-map-for-db-user [user]
  {:identity (:db/id user)
   :username (:user/uuid user)
   :roles    #{::user}})

(defmulti auth-map
          (fn [_ input] (::friend/workflow (meta input))))

(defmethod auth-map :default
  [conn {:keys [uuid] :as params}]
  (if uuid
    (let [user (d/entity (d/db conn) (:db/id (api/verify-email conn uuid)))]
      (if (= (:user/status user)
             :user.status/active)
        (auth-map-for-db-user user)
        (throw (ex-user-not-activated user))))
    (throw (ex-invalid-input params))))

(defmethod auth-map :facebook
  [conn {:keys [access_token user_id fb-info-fn] :as params}]
  (if (and user_id access_token fb-info-fn)
    (if-let [fb-user (p/lookup-entity (d/db conn) [:fb-user/id user_id])]
      (let [db-user (d/entity (d/db conn) (-> fb-user
                                              :fb-user/user
                                              :db/id))]

        ;; Check that the user is activated, if not throw exception.
        (if (= (:user/status db-user)
               :user.status/active)
          (auth-map-for-db-user db-user)
          (throw (ex-user-not-activated db-user))))

      ; If we don't have a facebook user in the DB, check if there's an accout with a matching email.
      (let [{:keys [email picture]} (fb-info-fn user_id access_token)]
        (debug "Creating new fb-user: " user_id)
        ;; Linking the FB user to u user account. If a user accunt with the same email exists,
        ;; it will be linked. Otherwise, a new user is created.
        (let [db-after-link (:db-after (link-fb-user-to-account conn {:user/email    email
                                                                      :user/picture  (:url (:data picture))
                                                                      :fb-user/id    user_id
                                                                      :fb-user/token access_token}))
              fb-user (p/lookup-entity db-after-link [:fb-user/id user_id])
              user (d/entity db-after-link (-> fb-user
                                               :fb-user/user
                                               :db/id))]
          ;; Again, check that the linked user is activated, and if not we throw an excaption to do so.
          (if (= (:user/status user)
                 :user.status/active)
            (auth-map-for-db-user user)
            (throw (ex-user-not-activated user))))))
    (throw (ex-invalid-input params))))

(defmethod auth-map :activate-account
  [conn {:keys [user-uuid user-email] :as body}]
  (if (and user-uuid user-email)
    (let [user (api/activate-account conn user-uuid user-email)]
      (auth-map-for-db-user user))
    (throw (ex-invalid-input body))))

(defn credential-fn
  "Create a credential fn with a db to pull user credentials.

  Returned function will dispatc on the ::friend/workflow and return the
  appropriate authentication map for the workflow."
  [conn]
  (fn [input]
    (auth-map conn input)))
