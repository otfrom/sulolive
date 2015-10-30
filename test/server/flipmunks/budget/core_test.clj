(ns flipmunks.budget.core-test
  (:require [clojure.test :refer :all]
            [datomic.api :only [q db] :as d]
            [flipmunks.budget.core :as b]
            [flipmunks.budget.datomic.pull :as p]
            [flipmunks.budget.datomic.transact :as t]
            [flipmunks.budget.validate :as v]))

(def schema (read-string (slurp "resources/private/datomic-schema.edn")))

(def test-data [{:transaction/name       "coffee"
                 :transaction/uuid       (str (d/squuid))
                 :transaction/created-at 12345
                 :transaction/date       "2015-10-10"
                 :transaction/amount     100
                 :transaction/currency   "SEK"
                 :transaction/tags       ["fika" "thailand"]}])

(def test-curs {:SEK "Swedish Krona"})

(def test-convs {:date "2015-10-10"
                 :rates {:SEK 8.333}})

(def user {:db/id      (d/tempid :db.part/user)
           :user/email "user@email.com"})

(def request {:session {:cemerick.friend/identity
                        {:authentications
                                  {1
                                   {:identity 1,
                                    :username (user :user/email),
                                    :roles    #{::b/user}}},
                         :current 1}}
              :body test-data})

(defn- new-db
  "Creates an empty database and returns the connection."
  []
  (let [uri "datomic:mem://test-db"]
    (d/delete-database uri)
    (d/create-database uri)
    (let [conn (d/connect uri)]
      (d/transact conn schema)
      (d/transact conn [user])
      conn)))

(defn- db-with-curs []
  (let [conn (new-db)]
    (b/post-currencies conn test-curs)
    conn))

(deftest test-post-user-data
  (let [db (b/post-user-data (db-with-curs)
                             request
                             test-convs)
        result (b/user-txs "user@email.com"
                                    (:db-after db)
                                    {})
        inv-result (b/user-txs "invalid@email.com"
                                       (:db-after db)
                                       {})
        no-result (b/user-txs "user@email.com"
                                       (:db-after db)
                                       {:d "1"})]
    (is (every? #(empty? (val %)) inv-result))
    (is (every? #(empty? (val %)) no-result))
    (is (= (count (:schema result)) 7))
    (is (= (count (:entities result))
           (+ (count test-data)                              ; Number of transaction entities
              (apply + (map count (map :transaction/tags test-data))) ; Number of tags entities
              (dec (count (filter #(= (:db/valueType %) :db.type/ref)
                                  (:schema result))))))))) ; number of other reference attributes (minus one tags included above)

(deftest test-post-invalid-user-data
  (let [invalid-user-req (assoc-in request
                                   [:session :cemerick.friend/identity :authentications 1 :username]
                                   "invalid@user.com")
        invalid-data-req (assoc request :body [{:invalid-data "invalid"}])
        post-fn #(b/post-user-data (db-with-curs)
                                   %
                                   test-convs)]
    (is (= (:cause (ex-data (post-fn invalid-user-req)))
           ::t/transaction-error))
    (is (= (:cause (ex-data (post-fn invalid-data-req)))
           ::v/validation-error))))

(deftest test-post-invalid-currencies 
  (testing "Posting invalid currency data."
    (let [db (b/post-currencies (new-db)
                                (assoc test-curs :invalid 2))]
      (is (= (:cause (ex-data db))
             ::t/transaction-error)))))