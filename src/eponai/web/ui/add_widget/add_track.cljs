(ns eponai.web.ui.add-widget.add-track
  (:require
    [eponai.client.ui :refer [update-query-params!] :refer-macros [opts]]
    [eponai.common.report :as report]
    [eponai.web.ui.daterangepicker :refer [->DateRangePicker]]
    [eponai.web.ui.add-widget.chart-settings :refer [->ChartSettings]]
    [eponai.web.ui.add-widget.select-graph :refer [->SelectGraph]]
    [eponai.web.ui.utils.filter :as filter]
    [eponai.web.ui.widget :refer [->Widget]]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]
    [eponai.common.format.date :as date]
    [cljs-time.core :as time]))

(defn- change-graph-style [widget style]
  (let [default-group-by {:graph.style/bar    :transaction/tags
                          :graph.style/area   :transaction/date
                          :graph.style/line   :transaction/date
                          :graph.style/number :default
                          :graph.style/chord  :default}]
    (-> widget
        (assoc-in [:widget/graph :graph/style] style)
        (update-in [:widget/report :report/track :track/functions]
                   (fn [fns]
                     (map (fn [f]
                            (let [fn-id (if (= style :graph.style/chord) :track.function.id/tags :track.function.id/sum)]
                              (assoc f
                                :track.function/id fn-id
                                :track.function/group-by (get default-group-by style))))
                          fns))))))

(defui NewTrack
  Object
  (initLocalState [this]
    {:step                                  1
     :selected-transactions                 :all-transactions
     :computed/date-filter-on-change        #(let [{:keys [on-change widget]} (om/get-computed this)]
                                              (om/update-state! this assoc :date-filter %)
                                              (on-change (-> widget
                                                             (assoc :widget/filter (.get-filters this))
                                                             (update-in [:widget/graph :graph/filter] merge %)) {:update-data? true}))
     :computed/tag-filter-on-change         (memoize
                                              (fn [tag-filter-key]
                                                (fn [tags]
                                                  (let [{:keys [on-change widget]} (om/get-computed this)
                                                        {:keys [tag-filter]} (om/get-state this)
                                                        new-filters (if (seq tags)
                                                                      {tag-filter-key tags}
                                                                      (dissoc tag-filter tag-filter-key))]
                                                    (om/update-state! this assoc :tag-filter new-filters)
                                                    (on-change (assoc widget :widget/filter (.get-filters this)) {:update-data? true})))))
     :computed/amount-filter-on-change      #(let [{:keys [on-change widget]} (om/get-computed this)]
                                              (om/update-state! this assoc :amount-filter %)
                                              (on-change (assoc widget :widget/filter (.get-filters this)) {:update-data? true}))
     :computed/include-tag-filter-on-change (fn [tags]
                                              (let [{:keys [on-change widget]} (om/get-computed this)
                                                    {:keys [graph-filter]} (om/get-state this)
                                                    new-filters (if (seq tags)
                                                                  (merge graph-filter {:filter/include-tags tags})
                                                                  (dissoc graph-filter :filter/include-tags))]
                                                (om/update-state! this assoc :graph-filter new-filters)
                                                (on-change (assoc-in widget [:widget/graph :graph/filter] new-filters) {:update-data? false})))
     :computed/exclude-tag-filter-on-change (fn [tags]
                                              (let [{:keys [on-change widget]} (om/get-computed this)
                                                    {:keys [graph-filter]} (om/get-state this)
                                                    new-filters (if (seq tags)
                                                                  (merge graph-filter {:filter/exclude-tags tags})
                                                                  (dissoc graph-filter :filter/exclude-tags))]
                                                (om/update-state! this assoc :graph-filter new-filters)
                                                (on-change (assoc-in widget [:widget/graph :graph/filter] new-filters) {:update-data? false})))
     :computed/date-range-picker-on-change     (fn [{:keys [start-date end-date selected-range]}] (.update-date-filter this start-date end-date selected-range))})
  (set-filters [this props]
    (let [{:keys [widget]} (::om/computed props)]
      (om/update-state! this assoc
                        :tag-filter (select-keys (:widget/filter widget) [:filter/include-tags
                                                                          :filter/exclude-tags])
                        :graph-filter (select-keys (get-in widget [:widget/graph :graph/filter]) [:filter/include-tags
                                                                                                  :filter/exclude-tags])
                        :date-filter (select-keys (:widget/filter widget) [:filter/start-date
                                                                           :filter/end-date
                                                                           :filter/last-x-days])
                        :amount-filter (select-keys (:widget/filter widget) [:filter/min-amount
                                                                             :filter/max-amount]))))
  (get-filters [this]
    (let [{:keys [tag-filter date-filter amount-filter]} (om/get-state this)]
      (merge tag-filter date-filter amount-filter)))

  (update-date-filter [this start end selected-key]
    (let [{:keys [on-change widget]} (om/get-computed this)
          date-filters (cond
                         (= selected-key :last-30-days)
                         {:filter/last-x-days 30}
                         (= selected-key :last-7-days)
                         {:filter/last-x-days 7}

                         :else
                         {:filter/start-date (date/date-map start)
                          :filter/end-date (date/date-map end)})]
      (om/update-state! this assoc :date-filter date-filters)
      (on-change (-> widget
                     (assoc :widget/filter (.get-filters this))
                     (update-in [:widget/graph :graph/filter] merge date-filters)) {:update-data? true})))

  (update-selected-transactions [this selected]
    (let [{:keys [widget
                  on-change]} (om/get-computed this)]
      (when (= selected :all-transactions)
        (on-change (assoc widget :widget/filter {}) {:update-data? true}))
      (om/update-state! this (fn [st]
                               (cond-> (assoc st :selected-transactions selected)

                                       (= selected :all-transactions)
                                       (assoc :tag-filter {}))))))
  (date-range [this]
    (let [{:keys [date-filter]} (om/get-state this)]
      (cond (some? (:filter/last-x-days date-filter))
            {:start-date (time/minus (date/today) (time/days (:filter/last-x-days date-filter)))
             :end-date   (date/today)}
            :else
            {:start-date (when (:filter/start-date filter) (date/date-time (:filter/start-date date-filter)))
             :end-date   (when (:filter/end-date filter) (date/date-time (:filter/end-date date-filter)))})))

  (componentDidMount [this]
    (.set-filters this (om/props this)))

  (render [this]
    (let [{:keys [widget
                  transactions
                  tags
                  on-change]} (om/get-computed this)
           {:keys [step
                   tag-filter
                   date-filter
                   amount-filter
                   graph-filter
                   input-title
                   selected-transactions
                   computed/date-filter-on-change
                   computed/tag-filter-on-change
                   computed/amount-filter-on-change
                   computed/include-tag-filter-on-change
                   computed/exclude-tag-filter-on-change
                   computed/date-range-picker-on-change]} (om/get-state this)
          {:keys [widget/graph]} widget]
      (html
        [:div
         [:h4 "New Track Widget"]
         ;[:ul.breadcrumbs
         ; [:li
         ;  [:a.disabled
         ;   (if (:db/id widget)
         ;     "Edit Track"
         ;     "New Track")]]
         ; (if (= 1 step)
         ;   [:li
         ;    [:span "Style"]]
         ;   [:li
         ;    [:a
         ;     {:on-click #(om/update-state! this assoc :step 1)}
         ;     [:span "Style"]]])
         ; (if (= 2 step)
         ;   [:li
         ;    [:span "Settings"]]
         ;   [:li
         ;    [:a
         ;     {:on-click #(om/update-state! this assoc :step 2)}
         ;     [:span "Settings"]]])]
         ;[:div.row.columns.small-12.medium-8]
         [:div
          [:h5.small-caps "Filter Transactions"]
          [:div.row
           [:div.columns.small-12.medium-6
            [:select
             {:value     (name selected-transactions)
              :on-change #(.update-selected-transactions this (keyword (.-value (.-target %))))}
             [:option
              {:value (name :all-transactions)}
              "All transactions"]
             [:option
              {:value (name :include-tags)}
              "Transactions with tags"]
             [:option
              {:value (name :exclude-tags)}
              "Transactions without tags"]]]
           [:div.columns.small-12.medium-6
            (let [tag-filter-fn (fn [tag-filter-key]
                                  (filter/->TagFilter (om/computed {:tags (get tag-filter tag-filter-key)}
                                                                   {:tag-list  tags
                                                                    :on-change (tag-filter-on-change tag-filter-key)
                                                                    :input-only? true})))]
              (condp = selected-transactions
                :include-tags (tag-filter-fn :filter/include-tags)
                :exclude-tags (tag-filter-fn :filter/exclude-tags)
                :all-transactions nil))]]
          [:div.row
           [:div.columns.small-12.medium-6
            [:h6.small-caps "Date Range"]]
           [:div.columns.small-12.medium-6
            [:h6.small-caps "Amounts"]]]
          [:div.row
           [:div.columns.small-12.medium-6
            [:div
             (->DateRangePicker (om/computed (.date-range this)
                                             {:on-apply date-range-picker-on-change}))
             ;(filter/->DateFilter (om/computed {:filter date-filter}
             ;                                  {:on-change date-filter-on-change}))
             ]]
           [:div.columns.small-12.medium-6
            (filter/->AmountFilter (om/computed {:amount-filter amount-filter}
                                                {:on-change amount-filter-on-change}))]]]

         ;[:h5.small-caps "Preview"]

         [:div.row
          (opts {:style {:padding "1em 0"}})
          [:div.columns.small-12
           (->Widget (assoc widget :widget/data (report/generate-data (:widget/report widget) transactions {:data-filter (get-in widget [:widget/graph :graph/filter])})))]]

         [:div.row
          (let [style (:graph/style graph)]
            [:div.columns.small-12
             [:input
              {:type     "radio"
               :id       "bar-option"
               :checked  (= style :graph.style/bar)
               :on-click #(when (and on-change (not= style :graph.style/bar))
                           (on-change (change-graph-style widget :graph.style/bar)))}]
             [:label {:for "bar-option"}
              [:span.currency-code "Bars"]]
             ;[:input
             ; {:type     "radio"
             ;  :id       "number-option"
             ;  :checked  (= style :graph.style/number)
             ;  :on-click #(when (and on-change (not= style :graph.style/number))
             ;              (on-change (change-graph-style widget :graph.style/number)))}]
             ;[:label {:for "number-option"}
             ; [:span.currency-code "Number"]]
             ;[:input
             ; {:type     "radio"
             ;  :id       "area-option"
             ;  :checked  (= style :graph.style/area)
             ;  :on-click #(when (and on-change (not= style :graph.style/area))
             ;              (on-change (change-graph-style widget :graph.style/area)))}]
             ;[:label {:for "area-option"}
             ; [:span.currency-code "Area"]]
             [:input
              {:type     "radio"
               :id       "line-option"
               :checked  (= style :graph.style/line)
               :on-click #(when (and on-change (not= style :graph.style/line))
                           (on-change (change-graph-style widget :graph.style/line)))}]
             [:label {:for "line-option"}
              [:span.currency-code "Line"]]
             ;[:input
             ; {:type     "radio"
             ;  :id       "chord-option"
             ;  :checked  (= style :graph.style/chord)
             ;  :on-click #(when (and on-change (not= style :graph.style/chord))
             ;              (on-change (change-graph-style widget :graph.style/chord)))}]
             ;[:label {:for "chord-option"}
             ; [:span.currency-code "Chord"]]
             ])]
         [:div.row
          [:div.columns.small-12.medium-6
           [:div.row.column
            [:span.small-caps "Title"]]
           [:div.row.column

            [:input
             {:type      "text"
              :value     (or (get-in widget [:widget/report :report/title]) "")
              :on-change #(let [{:keys [on-change widget]} (om/get-computed this)]
                           (when on-change
                             (on-change (assoc-in widget [:widget/report :report/title] (.-value (.-target %))))))}]]]
          (when (= :graph.style/bar (:graph/style graph))
            [:div.columns.small-12.medium-6
             ;(when (= :graph.style/bar (:graph/style graph))
             ;  [:hr])
             ;(when (= :graph.style/bar (:graph/style graph)))
             [:div.row
              [:div.columns.small-12.medium-6
               ;[:div.row.small-up-1]
               ;[:div.column]
               [:span.currency-code "Show tags"]
               ;[:div.row.small-up-1]
               ;[:div.column]
               (filter/->TagFilter (om/computed {:tags (:filter/include-tags graph-filter)}
                                                {:on-change   include-tag-filter-on-change
                                                 :input-only? true}))]
              [:div.columns.small-12.medium-6
               ;[:div.row.small-up-1
               ; [:div.column]]
               [:span.currency-code "Hide tags"]
               ;[:div.row.small-up-1
               ; [:div.column]]
               (filter/->TagFilter (om/computed {:tags (:filter/exclude-tags graph-filter)}
                                                {:on-change   exclude-tag-filter-on-change
                                                 :input-only? true}))]]
             ;[:div.row
             ; [:div.columns.small-12.medium-6
             ;  [:span.currency-code "Show tags"]]
             ; [:div.columns.small-12.medium-6
             ;  [:span.currency-code "Hide tags"]]]

             ;(when (= :graph.style/bar (:graph/style graph)))
             ;[:div.row
             ; [:div.columns.small-12.medium-6
             ;  (filter/->TagFilter (om/computed {:tags (:filter/include-tags graph-filter)}
             ;                                   {:on-change   include-tag-filter-on-change
             ;                                    :input-only? true}))]
             ; [:div.columns.small-12.medium-6
             ;  (filter/->TagFilter (om/computed {:tags (:filter/exclude-tags graph-filter)}
             ;                                   {:on-change   exclude-tag-filter-on-change
             ;                                    :input-only? true}))]]
             ])]
         ]))))

(def ->NewTrack (om/factory NewTrack))
