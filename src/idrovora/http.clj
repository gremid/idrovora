(ns idrovora.http
  (:require [clojure.tools.logging :as log]
            [idrovora.workspace :as ws]
            [mount.core :as mount :refer [defstate]]
            [muuntaja.core :as m]
            [reitit.coercion.spec :refer [coercion]]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.defaults :as defaults]))

(defn log-exceptions
  [handler ^Throwable e request]
  (when-not (some-> e ex-data :type #{::ring/response})
    (log/warn e (.getMessage e)))
  (handler e request))

(def exception-middleware
  (exception/create-exception-middleware
   (-> exception/default-handlers
       (assoc ::exception/wrap log-exceptions))))

(def middleware
  [{:name ::defaults
    :wrap #(defaults/wrap-defaults
            % (-> defaults/secure-site-defaults
                  (assoc-in [:proxy] true)
                  (assoc-in [:session] false)
                  (assoc-in [:cookies] false)
                  (assoc-in [:security :ssl-redirect] false)))}
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   exception-middleware
   muuntaja/format-request-middleware
   coercion/coerce-response-middleware
   coercion/coerce-request-middleware])

(defn create-handler
  [context-path]
  (ring/ring-handler
   (ring/router
    [context-path
     {:coercion coercion
      :muuntaja m/instance
      :middleware middleware}
     ws/handlers])
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))))

(defstate server
  :start
  (let [{:keys [::http-port ::http-context-path]} (mount/args)]
    (log/infof "Starting HTTP server at %s/tcp (context-path '%s')"
               http-port http-context-path)
    (require 'ring.adapter.jetty)
    ((ns-resolve 'ring.adapter.jetty 'run-jetty)
     (create-handler http-context-path)
     {:port http-port :join? false :async? true}))
  :stop
  (do
    (log/infof "Stopping HTTP server")
    (.. server (stop))))
