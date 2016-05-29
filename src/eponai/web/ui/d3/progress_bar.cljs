(ns eponai.web.ui.d3.progress-bar
  (:require
    [cljsjs.d3]
    [eponai.client.ui :refer-macros [opts]]
    [eponai.web.ui.d3 :as d3]
    [goog.string :as gstring]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]
    [eponai.common.format :as f]))

(defn- end-angle [d limit]
  (let [v (if (map? d)
                 (:value d)
                  (.-value d))]
    (if (and limit v)
      (let [progress (/ v limit)]
        (* 2 js/Math.PI progress))
      0)))
(defui ProgressBar
  Object
  (create [this]
    (let [{:keys [id width height data]} (om/props this)
          svg (d3/build-svg (om/react-ref this (str "progress-bar-" id)) width height)
          {:keys [margin]} (om/get-state this)
          {inner-height :height
           inner-width :width} (d3/svg-dimensions svg {:margin margin})

          cycle (first data)

          js-data (clj->js [(assoc (last (:values cycle)) :endAngle 0)])
          path-width (/ (min inner-width inner-height) 7)
          graph (.. svg
                    (append "g")
                    (attr "transform" (str "translate(" (/ inner-width 2) "," (/ inner-height 2) ")")))
          meter (.. graph
                    (append "g")
                    (attr "class" "circle-progress"))
          txt (.. meter
                  (append "text")
                  (attr "class" "txt")
                  (attr "text-anchor" "middle")
                  ;(attr "dy" ".15em")
                  (attr "font-size" 14))
          arc (.. js/d3
                  -svg
                  arc
                  (innerRadius (- (/ (min inner-width inner-height) 2) path-width))
                  (outerRadius (/ (min inner-width inner-height) 2))
                  (startAngle 0))]

      (.. meter
          (append "path")
          (attr "class" "background")
          (datum #js {:endAngle (* 2 js/Math.PI)})
          (style "fill" "green")
          (attr "d" arc))
      (d3/update-on-resize this id)
      (om/update-state! this assoc :svg svg :graph graph :meter meter :arc arc :js-data js-data :txt txt)))

  (update [this]
    (let [{:keys [data]} (om/props this)
          {:keys [svg meter arc js-data margin graph start-angle txt]} (om/get-state this)
          {inner-width :width
           inner-height :height} (d3/svg-dimensions svg {:margin margin})
          path-width (/ (min inner-width inner-height) 7)
          limit (f/str->number (:limit (first data)))
          ]
      (when-not (or (js/isNaN inner-height) (js/isNaN inner-width))
        (let [progress (.. meter
                           (selectAll ".foreground")
                           (data js-data))
              date-txt (.. txt
                           (selectAll ".date-txt")
                           (data js-data))
              val-txt (.. txt
                          (selectAll ".val-txt")
                          (data js-data))]

          (.. graph
              (attr "transform" (str "translate(" (/ inner-width 2) "," (/ inner-height 2) ")")))

          (.. arc
              (innerRadius (- (/ (min inner-width inner-height) 2) path-width))
              (outerRadius (/ (min inner-width inner-height) 2)))

          (.. progress
              enter
              (append "path")
              (attr "class" "foreground")
              (style "fill" "orange"))
          
          (.. progress
              transition
              (duration 250)
              (attrTween "d"
                         (fn [d]
                           (let [interpolate (.. js/d3
                                                 (interpolate start-angle (end-angle d limit)))]
                             (fn [t]
                               (set! (.-endAngle d) (interpolate t))
                               (arc d))))))
          (.. meter
              (selectAll ".background")
              (attr "d" arc))
          (.. val-txt
              enter
              (append "tspan")
              (attr "class" "val-txt")
              (text "0"))

          (.. val-txt
              transition
              (duration 500)
              (text (fn [d]
                      (gstring/format "%.2f" (.-value d)))))

          (.. date-txt
              enter
              (append "tspan")
              (attr "class" "date-txt")
              (attr "dy" "1.5em")
              (attr "x" 0)
              (text ""))

          (.. date-txt
              transition
              (duration 500)
              (text (fn [d]
                       ((d3/time-formatter "%a %d %b")
                              (js/Date. (.-name d))))))))))

  (componentDidMount [this]
    (d3/create-chart this))

  (componentDidUpdate [this _ _]
    (d3/update-chart this))

  (componentWillReceiveProps [this next-props]
    (let [{:keys [data]} next-props
          {:keys [limit values]} (first data)
          v (last values)
          new-start-angle (end-angle v limit)]
      (om/update-state! this assoc :start-angle new-start-angle)
      (d3/update-chart-data this [v])))

  (initLocalState [_]
    {:start-angle 0})

  (render [this]
    (let [{:keys [id]} (om/props this)]
      (html
        [:div
         (opts {:ref (str "progress-bar-" id)
                :style {:height "100%"
                        :width "100%"}})]))))

(def ->ProgressBar (om/factory ProgressBar))
