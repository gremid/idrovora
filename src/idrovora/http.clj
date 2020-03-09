(ns idrovora.http
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.spec :as spec]
            [muuntaja.core :as m]
            [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [clojure.tools.logging :as log]))

(def handler
  (ring/ring-handler
   (ring/router
   [""
    {:coercion reitit.coercion.spec/coercion
     :muuntaja m/instance
     :middleware [parameters/parameters-middleware
                  muuntaja/format-negotiate-middleware
                  muuntaja/format-response-middleware
                  exception/exception-middleware
                  muuntaja/format-request-middleware
                  coercion/coerce-response-middleware
                  coercion/coerce-request-middleware]}
    ["/status" {:get (fn [_] {:status 200 :body {:status "ok"}})}]])
   (ring/routes
    (ring/create-default-handler))))

(defstate server
  :start
  (let [{:keys [::http-port]} (mount/args)]
    (log/infof "Starting HTTP server at %s/tcp" http-port)
    (require 'ring.adapter.jetty)
    ((ns-resolve 'ring.adapter.jetty 'run-jetty)
     handler {:port http-port :join? false}))
  :stop
  (do
    (log/infof "Stopping HTTP server")
    (.. server (stop))))
