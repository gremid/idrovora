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
            [reitit.core :as r]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.defaults :as defaults]
            [ring.util.io :as ring-io]
            [ring.util.request :as request]
            [ring.util.response :as response])
  (:import java.io.File
           java.net.URL
           java.time.Instant
           java.util.UUID))

(def ^:private configured-doc-root
  (delay (some-> (mount/args) :http-doc-root fs/make-dir!)))

(def doc-root
  (atom nil))

(defn wrap-job-coordinates
  [handler]
  (fn
    [{{:keys [pipeline job path]} :path-params :as request} respond raise]
    (let [request (assoc request ::pipeline pipeline ::job job ::path path)]
      (handler
       request
       (fn [response]
         (let [{:keys [::pipeline ::job]} (merge request response)]
           (respond
            (cond-> response
              pipeline (response/header "X-Idrovora-Pipeline" pipeline)
              job (response/header "X-Idrovora-Job" job)))))
       raise))))

(defn wrap-resource
  [handler]
  (fn
    [{:keys [::pipeline ::job ::path] :as request} respond raise]
    (let [doc-root @doc-root
          f (apply fs/file (remove nil? [doc-root pipeline job path]))]
      (if (fs/ancestor? doc-root f)
        (handler (assoc request ::file f) respond raise)
        (respond (response/not-found {}))))))

(defn absolute-url
  [request url]
  (str (URL. (URL. (request/request-url request)) url)))

(defn self-url
  [request]
  (request/request-url request))

(defn index-url
  [{:keys [::r/router] :as request}]
  (->>
   (r/match-by-name router ::index-request)
   (r/match->path)
   (absolute-url request)))

(defn pipeline-url
  [{:keys [::r/router] :as request} pipeline]
  (->>
   {:pipeline pipeline}
   (r/match-by-name router ::job-request)
   (r/match->path)
   (absolute-url request)))

(defn job-url
  [{:keys [::r/router] :as request} pipeline job]
  (->>
   {:pipeline pipeline :job job :path ""}
   (r/match-by-name router ::resource-request)
   (r/match->path)
   (absolute-url request)))

(defn link
  ([href] (link href :self))
  ([href rel] {rel {:href href}}))

(defn wrap-links
  [handler]
  (fn [{:keys [::pipeline ::job] :as request} respond raise]
    (handler
     request
     (fn [{:keys [body status] p ::pipeline j ::job
           :or {p pipeline j job} :as resp}]
       (respond
        (if-not (and (map? body) (< status 400))
          resp
          (->>
           (merge (link (self-url request))
                  (link (index-url request) :index)
                  (when p (link (pipeline-url request p) :pipeline))
                  (when j (link (job-url request p j) :job)))
           (assoc-in resp [:body :_links])))))
     raise)))

(defn pipeline-names
  []
  (->>
   (:dirs (mount/args))
   (mapcat fs/dir-contents) (filter fs/file?) (map fs/file-name)
   (filter #(str/ends-with? % ".xpl")) (map #(str/replace % #"\.xpl$" ""))))

(defn handle-index-request
  [request respond _]
  (->>
   (for [p (pipeline-names)] {:id p :_links (link (pipeline-url request p))})
   (assoc-in {} [:_embedded :pipelines])
   (response/response)
   (respond)))

(defn xpl-file
  [pipeline]
  (->>
   (:dirs (mount/args))
   (map #(fs/file % (str pipeline ".xpl")))
   (filter fs/file?)
   (first)))

(defn wrap-xpl
  [handler]
  (fn [{:keys [::pipeline] :as request} respond raise]
    (if-let [xpl (xpl-file pipeline)]
      (handler (assoc request ::xproc/xpl xpl) respond raise)
      (-> (response/not-found {:pipeline pipeline})
          (respond)))))

(defn resource
  [f]
  {:id (fs/file-name f)
   :modified (-> f fs/last-modified Instant/ofEpochMilli str)})

(defn job-dirs
  [pipeline]
  (->> (fs/dir-contents @doc-root pipeline) (filter fs/directory?)))


(defn handle-pipeline-request
  [{:keys [::pipeline] :as request} respond _]
  (let [jobs (->> (map resource (job-dirs pipeline))
                  (sort-by :modified #(compare %2 %1)))]
    (->>
     (for [{:keys [id] :as job} (take 100 jobs)]
       (assoc job :_links (link (absolute-url request (str id "/")))))
     (assoc-in {:id pipeline :total_jobs (count jobs)} [:_embedded :jobs])
     (response/response)
     (respond))))

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

(defn wrap-job
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
  [{:keys [::pipeline ::job ::xproc/job-dir] :as request} respond _]
  (let [{:keys [::xproc/result ::xproc/unsubscribe]} (xproc/submit-job request)]
    (a/go
      (try
        (let [{:keys [::xproc/error] :as result} (a/<! result)]
          (respond
           (if-not error
             (response/created (job-url request pipeline job) (resource job-dir))
             (->
              {:error (-> error print-stack-trace with-out-str)}
              (response/response)
              (response/status 502)))))
          (finally (unsubscribe))))))

(defn handle-resource-request
  [{^File f ::file :as request} respond _]
  (respond
   (cond
     ;; files are delivered as-is
     (fs/file? f)
     (response/response f)
     ;; a directory's representation can be negotiated
     (fs/directory? f)
     (cond
       ;; directories can be delivered as ZIP archives, if requested
       (some-> (m/get-response-format-and-charset request)
               :raw-format #{"application/zip"})
       (-> (partial fs/dir->zip-stream f)
           (ring-io/piped-input-stream)
           (response/response)
           (response/content-type "application/zip"))
       ;; otherwise return directory listing
       :else
       (->>
        (for [c (fs/dir-contents f)]
          (let [n (str (fs/file-name c) (if (fs/directory? c) "/" ""))]
            (assoc (resource c) :_links (link (absolute-url request n)))))
        (assoc-in {} [:_embedded :resources])
        (response/response)))
     ;; fallback
     :else (response/not-found {}))))

(defn handle-job-removal
  [{^File f ::file :keys [::pipeline ::job ::path] :as request} respond _]
  (respond
   (if (and pipeline job (empty? path) (fs/directory? f))
     (do (fs/delete! f) (response/redirect (pipeline-url request pipeline)))
     (response/not-found {}))))

(def handlers
  [""
   {:middleware [wrap-job-coordinates wrap-resource wrap-links]}
   ["/"
    {:name ::index-request
     :handler handle-index-request}]
   ["/:pipeline/"
    {:name ::job-request
     :handler handle-pipeline-request
     :middleware [wrap-xpl]
     :parameters {:path (s/keys :req-un [::pipeline])}
     :post {:handler handle-job-request
            :middleware [wrap-job]}}]
   ["/:pipeline/:job/*path"
    {:name ::resource-request
     :handler handle-resource-request
     :parameters {:path (s/keys :req-un [::pipeline ::job ::path])}
     :delete {:handler handle-job-removal}}]])

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
            (ns-resolve 'reitit.ring.middleware.dev 'print-request-diffs)}))
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
