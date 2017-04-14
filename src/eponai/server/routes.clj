(ns eponai.server.routes
  (:require
    [eponai.server.api :as api]
    [eponai.server.auth :as auth]
    [clojure.string :as clj.string]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [eponai.server.middleware :as m]
    [ring.util.response :as r]
    [ring.util.request :as ring.request]
    [bidi.bidi :as bidi]
    [bidi.ring :as bidi.ring]
    [eponai.common.routes :as common.routes]
    [eponai.common.parser :as parser]
    [eponai.common.parser.util :as parser.util]
    [eponai.server.parser.response :as parser.resp]
    [taoensso.timbre :refer [debug error trace warn]]
    [eponai.server.ui :as server.ui]
    [om.next :as om]
    [eponai.server.external.stripe :as stripe]
    [eponai.server.websocket :as websocket]
    [eponai.server.ui.root :as root]
    [eponai.common.routes :as routes]
    [eponai.common.database :as db]))

(defn html [& path]
  (-> (clj.string/join "/" path)
      (r/resource-response {:root "public"})
      (r/content-type "text/html")))

(defn release? [request]
  (true? (::m/in-production? request)))

(declare handle-parser-request)

(defn request->props [request]
  {:empty-datascript-db            (::m/empty-datascript-db request)
   :state                          (::m/conn request)
   :system                         (::m/system request)
   :release?                       (release? request)
   :cljs-build-id                  (::m/cljs-build-id request)
   :route-params                   (merge (:route-params request)
                                          (:params request))
   :route                          (:handler request)
   :query-params                   (:params request)
   :auth                           (:identity request)})

;----------API Routes

(defn handle-parser-request
  [{:keys [body] ::m/keys [conn parser system] :as request}]
  (debug "Handling parser request with query:" (:query body))
  (let [read-basis-t-graph (some-> (::parser/read-basis-t body)
                                   (parser.util/graph-read-at-basis-t true)
                                   (atom))]
    (parser
      {::parser/read-basis-t-graph read-basis-t-graph
       ::parser/chat-update-basis-t (::parser/chat-update-basis-t body)
       :state                      conn
       :auth                       (:identity request)
       :params                     (:params request)
       :system                     system}
      (:query body))))

(defn trace-parser-response-handlers
  "Wrapper with logging for parser.response/response-handler."
  [env key params]
  (trace "handling parser response for key:" key "value:" params)
  (parser.resp/response-handler env key params))

(def handle-parser-response
  "Will call response-handler for each key value in the parsed result."
  (-> (parser.util/post-process-parse trace-parser-response-handlers [])))

(defn call-parser [{:keys [::m/conn] :as request}]
  (let [ret (handle-parser-request request)
        basis-t-graph (some-> ret (meta) (::parser/read-basis-t-graph) (deref))
        ret (->> ret
                 (handle-parser-response (assoc request :state conn))
                 (parser.resp/remove-mutation-tx-reports))]
    {:result ret
     :meta   {::parser/read-basis-t basis-t-graph}}))

(defn bidi-route-handler [route]
  ;; Currently all routes render the same way.
  ;; Enter route specific stuff here.
  (let [auth-roles (routes/auth-roles route)
        redirect-route (routes/redirect-route route)]
    (fn [request]
      (if (auth/has-auth? (db/db (::m/conn request)) auth-roles (:identity request) (:route-params request))
        (server.ui/render-site (request->props (assoc request :handler route)))
        (r/redirect (routes/path redirect-route))))))

(defroutes
  member-routes
  ;; Hooks in bidi routes with compojure.
  (GET "*" _ (bidi.ring/make-handler common.routes/routes bidi-route-handler)))

(defroutes
  site-routes
  (POST "/api/user" request
    (r/response (call-parser request))
    ;(auth/restrict
    ;  #(r/response (call-parser %))
    ;  (auth/jwt-restrict-opts))
    )
  (POST "/api/chat" request
    (r/response (call-parser request)))

  (GET "/aws" request (api/aws-s3-sign request))
  (POST "/api" request
    ;(r/response (call-parser request))
    (auth/restrict
      #(r/response (call-parser %))
      (auth/jwt-restrict-opts)))

  (route/resources "/")
  ;(POST "/stripe/main" request (r/response (stripe/webhook (::m/conn request) (:params request))))
  (GET "/auth" request (auth/authenticate request))

  (GET "/logout" request (auth/logout request))

  (GET "/devcards" request
    (when-not (::m/in-production? request)
      (server.ui/render-to-str root/Root {:route         :devcards
                                          :cljs-build-id "devcards"
                                          :release?      false})))

  (GET "/coming-soon" _ (bidi.ring/make-handler common.routes/routes bidi-route-handler))
  (GET "/sell/coming-soon" _ (bidi.ring/make-handler common.routes/routes bidi-route-handler))

  ;; Websockets
  (GET "/ws/chat" {::m/keys [system] :as request}
    (websocket/handle-get-request (:system/chat-websocket system)
                                  request))
  (POST "/ws/chat" {::m/keys [system] :as request}
    (websocket/handler-post-request (:system/chat-websocket system)
                                    request))

  (context "/" [:as request]
    (cond-> member-routes
            (or (::m/in-production? request))
            (auth/restrict (auth/member-restrict-opts)))
    ;(if (release? request)
    ;  (auth/restrict member-routes (auth/member-restrict-opts))
    ;  member-routes)
    )


  (route/not-found "Not found"))