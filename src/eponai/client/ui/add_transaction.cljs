(ns eponai.client.ui.add_transaction
  (:require [om.next :as om :refer-macros [defui]]
            [eponai.client.ui :refer [map-all] :refer-macros [style opts]]
            [eponai.client.ui.datepicker :refer [->Datepicker]]
            [eponai.client.ui.tag :as tag]
            [eponai.common.format :as format]
            [sablono.core :refer-macros [html]]
            [cljsjs.pikaday]
            [cljsjs.moment]
            [garden.core :refer [css]]
            [datascript.core :as d]))

(defn input-on-change [input-component k]
  (fn [e]
    (om/update-state! input-component assoc k (.-value (.-target e)))))

(defn delete-tag-fn [component name]
  (fn []
    (om/update-state! component update :input/tags
                      (fn [tags]
                        (into (sorted-set)
                              (remove #(= name %))
                              tags)))))

(defn on-add-tag-key-down [component input-tag]
  (fn [e]
    (when (and (= 13 (.-keyCode e))
               (seq (.. e -target -value)))
      (.preventDefault e)
      (om/update-state! component
                        #(-> %
                             (assoc :input/tag "")
                             (update :input/tags conj input-tag))))))

(defn header []
  "New Transaction")

(defui AddTransaction
  static om/IQuery
  (query [_]
    [{:query/all-currencies [:currency/code]}
     {:query/all-budgets [:budget/uuid
                          :budget/name]}])
  Object
  (initLocalState [this]
    (let [{:keys [query/all-currencies
                  query/all-budgets]} (om/props this)]
      {:input/date     (js/Date.)
       :input/tags     (sorted-set)
       :input/currency (-> all-currencies
                           first
                           :currency/code)
       :input/budget   (-> all-budgets
                           first
                           :budget/uuid)}))
  (render
    [this]
    (let [{:keys [query/all-currencies
                  query/all-budgets]} (om/props this)
          {:keys [input/date input/tags input/currency input/budget
                  input/tag input/amount input/title]}
          ;; merging state with props, so that we can test the states
          ;; with devcards
          (merge (om/props this)
                 (om/get-state this))]
      (println "budgets: " all-budgets)
      (html
        [:div
         [:label.form-control-static
          "Sheet:"]
         [:select.form-control
          {:on-change     (input-on-change this :input/budget)
           :type          "text"
           :default-value budget}
          (map-all all-budgets
                   (fn [budget]
                     [:option
                      (opts {:value (:budget/uuid budget)
                             :key   [(:budget/uuid budget)]})
                      (or (:budget/name budget) "Untitled")]))]

         [:label.form-control-static
          "Amount:"]
         ;; Input amount with currency
         [:div
          (opts {:style {:display         "flex"
                         :flex-direction  "row"
                         :justify-content "stretch"
                         :max-width       "100%"}})
          [:input.form-control
           (opts {:type        "number"
                  :placeholder "0.00"
                  :min         "0"
                  :value       amount
                  :style       {:width        "80%"
                                :margin-right "0.5em"}
                  :on-change   (input-on-change this :input/amount)})]

          [:select.form-control
           (opts {:on-change     (input-on-change this :input/currency)
                  :default-value currency
                  :style         {:width "20%"}})
           (map-all all-currencies
                    (fn [{:keys [currency/code]}]
                      [:option
                       (opts {:value (name code)
                              :key   [code]})
                       (name code)]))]]

         [:label.form-control-static
          "Title:"]

         [:input.form-control
          {:on-change (input-on-change this :input/title)
           :type      "text"
           :value     title}]

         [:label.form-control-static
          "Date:"]

         ; Input date with datepicker

         [:div
          (->Datepicker
            (opts {:key       [::date-picker]
                   :value     date
                   :on-change #(om/update-state!
                                this
                                assoc
                                :input/date
                                %)}))]

         [:label.form-control-static
          "Tags:"]

         [:div
          (opts {:style {:display         "flex"
                         :flex-direction  "column"
                         :justify-content "flex-start"}})
          [:input.form-control
           (opts {:on-change   (input-on-change this :input/tag)
                  :type        "text"
                  :value       tag
                  :on-key-down (on-add-tag-key-down this tag)})]

          [:div.form-control-static
           (map-all tags
                    (fn [tagname]
                      (tag/->Tag (tag/tag-props tagname
                                                (delete-tag-fn this tagname)))))]]

         [:button
          (opts {:class    "btn btn-default btn-lg"
                 :on-click #(om/transact!
                             this
                             `[(transaction/create
                                 ~(-> (om/get-state this)
                                      (update :input/date format/date->ymd-string)
                                      (assoc :input/uuid (d/squuid))
                                      (assoc :input/created-at (.getTime (js/Date.)))
                                      (dissoc :input/tag)))
                               :query/all-budgets
                               :query/one-budget])})
          "Save"]]))))

(def ->AddTransaction (om/factory AddTransaction))
