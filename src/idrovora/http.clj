(ns idrovora.http
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [idrovora.fs :as fs]
            [idrovora.xproc :as xproc]
            [mount.core :as mount :refer [defstate]]
            [muuntaja.core :as m]
            [reitit.coercion.spec :as rcs]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.defaults :as defaults]
            [ring.util.io :as ring-io]
            [ring.util.response :as response])
  (:import java.util.UUID))

(def ^:private configured-doc-root
  (delay (some-> (mount/args) :http-doc-root fs/make-dir!)))

(def doc-root
  (atom nil))

(defn wrap-job-coordinates
  [handler]
  (fn
    [{{:keys [pipeline job]} :path-params :as request} respond raise]
    (handler
     (merge request
            (when pipeline {::pipeline pipeline})
            (when job {::job job}))
     (fn [{p ::pipeline j ::job :or {p pipeline j job} :as resp}]
       (respond
        (cond-> resp
          p (response/header "X-Idrovora-Pipeline" p)
          j (response/header "X-Idrovora-Job" j))))
     raise)))

(defn wrap-resource-exists
  [handler]
  (fn
    [request respond raise]
    (let [{:keys [::pipeline ::job] {:keys [path]} :path-params} request
          doc-root @doc-root
          f (fs/file doc-root pipeline job path)]
      (if (and (or (fs/directory? f) (fs/file? f)) (fs/ancestor? doc-root f))
        (handler request respond raise)
        (-> {:pipeline pipeline :job job :path path}
            (response/not-found)
            (respond))))))

(defn handle-resource-request
  [request respond _]
  (let [{:keys [::pipeline ::job] {:keys [path]} :path-params} request
        f (fs/file @doc-root pipeline job path)]
    (respond
     (cond
       ;; directories are always delivered as ZIP archives
       (fs/directory? f)
       (-> (partial fs/dir->zip-stream f)
           (ring-io/piped-input-stream)
           (response/response)
           (response/content-type "application/zip"))
       ;; files are delivered as-is
       (fs/file? f)
       (response/response f)))))

(defn xpl-file
  [pipeline]
  (->>
   (:dirs (mount/args))
   (map #(fs/file % (str pipeline ".xpl")))
   (filter fs/file?)
   (first)))

(defn wrap-assoc-xpl
  [handler]
  (fn [{:keys [::pipeline] :as request} respond raise]
    (if-let [xpl (xpl-file pipeline)]
      (handler (assoc request ::xproc/xpl xpl) respond raise)
      (-> (response/not-found {:pipeline pipeline})
          (respond)))))

(defn file-param?
  [[_ {:keys [tempfile]}]]
  tempfile)

(defn zip-file-param?
  [[_ {:keys [content-type filename] :or {filename ""}} :as param]]
  (and (file-param? param)
       (or (= "application/zip" content-type)
           (-> filename str/lower-case (str/ends-with? ".zip")))))

(defn string-param?
  [[_ v]]
  (string? v))

(defn param-key->filename
  [k]
  (let [filename (name k)
        has-ext? (str/last-index-of filename ".")]
    (if has-ext? filename (str filename ".xml"))))

(defn unzip-param
  [source-dir [k {:keys [tempfile]}]]
  (with-open [is (io/input-stream tempfile)]
    (fs/zip-stream->dir source-dir is)))

(defn copy-param
  [source-dir [k {:keys [tempfile]}]]
  (io/copy tempfile (fs/file source-dir (param-key->filename k))))

(defn spit-param
  [source-dir [k v]]
  (spit (fs/file source-dir (param-key->filename k)) v :encoding "UTF-8"))

(defn wrap-assoc-job
  [handler]
  (fn
    [{:keys [::pipeline :params] :as request} respond raise]
    (try
      (let [job (str (UUID/randomUUID))
            job-dir (fs/make-dir! @doc-root pipeline job)
            file-params (filter file-param? params)
            zip-file-params (filter zip-file-param? file-params)
            file-params (remove zip-file-param? file-params)
            string-params (filter string-param? params)]
        (doseq [p zip-file-params] (unzip-param job-dir p))
        (doseq [p file-params] (copy-param job-dir p))
        (doseq [p string-params] (spit-param job-dir p))
        (handler
         (assoc request ::job job ::xproc/job-dir job-dir)
         #(respond (assoc % ::job job))
         raise))
      (catch Throwable t
        (log/debugf t "Error parsing job request for pipeline %s" pipeline)
        (-> (response/bad-request {:pipeline pipeline})
            (respond))))))

(defn handle-job-request
  [{:keys [::pipeline ::job] :as request} respond _]
  (let [{:keys [::xproc/result ::xproc/unsubscribe]} (xproc/submit-job request)]
    (a/go
      (try
        (let [{:keys [::xproc/error] :as result} (a/<! result)]
          (respond
           (if-not error
             (->
              (str/join "/" ["" pipeline job ""])
              (response/created {:pipeline pipeline :job job}))
             (->
              {:error (-> error print-stack-trace with-out-str)}
              (assoc :pipeline pipeline :job job)
              (response/response)
              (response/status 502)))))
          (finally (unsubscribe))))))

(def handlers
  [""
   {:middleware [wrap-job-coordinates]}
   ["/:pipeline/"
    {:handler handle-job-request
     :middleware [wrap-assoc-xpl wrap-assoc-job]
     :parameters {:path (s/keys :req-un [::pipeline])}}]
   ["/:pipeline/:job/*path"
    {:handler handle-resource-request
     :middleware [wrap-resource-exists]
     :parameters {:path (s/keys :req-un [::pipeline ::job ::path])}}]])

(defn log-exceptions
  [handler ^Throwable e request]
  (when-not (some-> e ex-data :type #{::ring/response})
    (log/warn e (.getMessage e)))
  (handler e request))

(def exception-middleware
  (exception/create-exception-middleware
   (-> exception/default-handlers
       (assoc ::exception/wrap log-exceptions))))

(def handler-options
  {:coercion rcs/coercion
   :muuntaja m/instance
   :middleware [{:name ::defaults
                 :wrap #(defaults/wrap-defaults
                         % (-> defaults/secure-site-defaults
                               (assoc-in [:proxy] true)
                               (assoc-in [:session] false)
                               (assoc-in [:cookies] false)
                               (assoc-in [:security :ssl-redirect] false)
                               (assoc-in [:security :anti-forgery] false)))}
                muuntaja/format-negotiate-middleware
                muuntaja/format-request-middleware
                muuntaja/format-response-middleware
                exception-middleware
                coercion/coerce-exceptions-middleware
                coercion/coerce-request-middleware
                coercion/coerce-response-middleware]})

(defstate server
  :start
  (when-let [http-port (:http (mount/args))]
    (let [http-context-path (or (:http-context-path (mount/args)) "")
          http-doc-root (reset! doc-root
                                (or @configured-doc-root
                                    (fs/make-temp-dir! "idrovora-http-")))]
      (log/infof "Starting HTTP server at %s/tcp" http-port)
      (log/infof "Serving HTTP docs at '%s' from '%s'"
                 (str/replace (str "/" http-context-path) #"^/+" "/")
                 (str @doc-root))
      (require 'ring.adapter.jetty)
      ((ns-resolve 'ring.adapter.jetty 'run-jetty)
       (ring/ring-handler
        (ring/router
         [http-context-path handler-options handlers]
         #_(do
           (require 'reitit.ring.middleware.dev)
           {:reitit.middleware/transform
            reitit.ring.middleware.dev/print-request-diffs}))
        (ring/routes
         (ring/redirect-trailing-slash-handler)
         (ring/create-default-handler)))
       {:port http-port :join? false :async? true})))
  :stop
  (when server
    (.stop server)
    (log/infof "Stopped HTTP server")
    (when-not @configured-doc-root
      (fs/delete! @doc-root true)
      (log/debugf "Removed temporary HTTP docs %s" @doc-root))))
