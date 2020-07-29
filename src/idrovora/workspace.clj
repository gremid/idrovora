(ns idrovora.workspace
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cronjure.core :as cron]
            [idrovora.fs :as fs]
            [idrovora.xproc :as xproc]
            [juxt.dirwatch :refer [close-watcher watch-dir]]
            [mount.core :as mount :refer [defstate]]
            [ring.util.io :as ring-io]
            [ring.util.response :as response]
            [clojure.java.io :as io])
  (:import com.cronutils.model.Cron))

;; ## Canonical file and directory locations in the workspace.

(defn xpl-dir
  []
  (-> (mount/args) ::xpl-dir fs/file))

(defn pipeline-xpl
  [pipeline]
  (fs/file (xpl-dir) (str pipeline ".xpl")))

(defn pipeline-exists?
  [pipeline]
  (fs/file? (pipeline-xpl pipeline)))

(defn job-dir
  ([] (-> (mount/args) ::job-dir fs/file))
  ([pipeline job] (fs/file (job-dir) pipeline job)))

(defn job-status-txt
  [pipeline job status]
  (fs/file (job-dir pipeline job) "status" (str status ".txt")))

;; ## Job execution and status management

(defn run-job!
  [[pipeline job :as job-id]]
  (let [xpl (pipeline-xpl pipeline)
        job-dir (job-dir pipeline job)
        result-ready (job-status-txt pipeline job "result-ready")
        job-failed (job-status-txt pipeline job "job-failed")]
    (try
      (log/debugf "Running %s" job-id)
      (xproc/run-pipeline!
       (fs/uri xpl)
       {"source-dir" (fs/uri job-dir "source")
        "result-dir" (fs/uri job-dir "result")})
      (fs/delete! job-failed true)
      (spit result-ready "")
      (log/debugf "Completed %s" job-id)
      [pipeline job :completed]
      (catch Throwable t
        (fs/delete! result-ready true)
        (spit job-failed "")
        (log/warnf t "Error running %s" job-id)
        [pipeline job :failed t]))))

(def running-jobs
  (atom {}))

(defn remove-running-jobs
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result [pipeline job :as req]]
     (when
         (locking running-jobs
           (and (nil? (get-in @running-jobs [pipeline job]))
                (swap! running-jobs assoc-in [pipeline job] true)))
       (rf result req)))))

(defn remove-finished-jobs
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result [pipeline job :as req]]
     (swap! running-jobs update pipeline dissoc job)
     (rf result req))))

(def job-requests
  (a/chan))

(def job-results
  (let [ch (a/chan)]
    (a/pipeline-blocking
     (. (Runtime/getRuntime) (availableProcessors))
     ch
     (comp remove-running-jobs (map run-job!) remove-finished-jobs)
     job-requests
     true
     #(log/warn % "Exception during job execution"))
    ch))

;; ## Filesystem watcher (hot-folder mode)

(defn filter-fs-job-requests
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result {:keys [file action] :as evt}]
     (let [job-dir (job-dir)]
       (when (and (#{:create :modify} action) (fs/ancestor? job-dir file))
         (let [[pipeline job] (map str (fs/relativize-path job-dir file))]
           (when (and pipeline job (pipeline-exists? pipeline))
             (let [source-ready (job-status-txt pipeline job "source-ready")]
               (when (= source-ready file)
                 (rf result [pipeline job]))))))))))

(defstate fs-event-listener
  :start (let [fs-requests (a/chan
                            1 filter-fs-job-requests
                            #(log/warn % "Error during FS event handling"))
               job-dir (fs/make-dir! (job-dir))
               watcher (watch-dir (partial a/>!! fs-requests) job-dir)]
           (log/infof "Start watching '%s'" job-dir)
           (a/pipe fs-requests job-requests false)
           (fn []
             (log/infof "Stop watching '%s'" job-dir)
             (close-watcher watcher)
             (a/close! fs-requests)))
  :stop (fs-event-listener))

;; ## Cleaning up older jobs

(defstate cleanup-scheduler
  :start
  (let [{:keys [::cleanup-schedule ::job-max-age]} (mount/args)
        next (partial cron/time-to-next-execution cleanup-schedule :millis)
        ch (a/chan)]
    (log/infof "Scheduling cleanup of old jobs (%s)"
               (.asString ^Cron cleanup-schedule))
    (a/go-loop []
      (when-let [_ (a/alt! (a/timeout (next)) :scheduled ch ([v] v))]
        (log/infof "Cleaning up %s" (job-dir))
        (recur)))
    ch)
  :stop
  (a/close! cleanup-scheduler))

(defn wrap-pipeline-exists
  [handler]
  (fn [{{:keys [pipeline]} :path-params :as request} respond raise]
    (if (pipeline-exists? pipeline)
      (handler request respond raise)
      (-> (response/not-found {:pipeline pipeline})
          (respond)))))

(defn file-param?
  [[_ {:keys [tempfile]}]]
  tempfile)

(defn zip-file-param?
  [[_ {:keys [content-type filename] :or {filename ""}} :as param]]
  (and (file-param? param)
       (or (= "application/zip" content-type)
           (str/ends-with? filename ".zip"))))

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

(defn request->job
  [{{:keys [pipeline]} :path-params params :params :as req}]
  (let [job (str (java.util.UUID/randomUUID))
        job-dir (fs/make-dir! (job-dir pipeline job))
        source (fs/make-dir! job-dir "source")
        result (fs/make-dir! job-dir "result")
        status (fs/make-dir! job-dir "status")
        file-params (filter file-param? params)
        zip-file-params (filter zip-file-param? file-params)
        file-params (remove zip-file-param? file-params)
        string-params (filter string-param? params)]
    (doseq [p zip-file-params] (unzip-param source p))
    (doseq [p file-params] (copy-param source p))
    (doseq [p string-params] (spit-param source p))
    [pipeline job]))

(defn add-job-headers
  [resp [pipeline job]]
  (-> resp
      (response/header "X-Idrovora-Pipeline" pipeline)
      (response/header "X-Idrovora-Job" job)))

(defn job->redirect
  [[pipeline job]]
  (let [job-dir (job-dir pipeline job)
        result-dir (fs/file job-dir "result")
        result-files (fs/files result-dir)
        single-result? (= 1 (count result-files))]
    (if-not single-result?
      (response/redirect (str/join "/" ["" pipeline job]))
      (let [[result] result-files
            result (fs/relativize-path job-dir result)]
        (response/redirect (str/join "/" ["" pipeline job result]))))))

(def job-results-pub
  (a/pub job-results #(subvec % 0 2)))

(defn handle-job-request
  [{{:keys [pipeline]} :path-params :as req} respond _]
  (let [[pipeline job :as job-id] (request->job req)
        result-ch (a/chan)]
    (a/sub job-results-pub job-id result-ch)
    (a/go
      (try
        (->
         (a/alt!
           result-ch
           ([[_ _ result]]
            (case result
              :completed (job->redirect job-id)
              :failed (-> (response/response {:pipeline pipeline :job job})
                          (response/status 500))))
           (a/timeout 30000)
           (-> (response/response {:pipeline pipeline :job job})
               (response/status 504)))
         (add-job-headers job-id)
         (respond))
        (finally
          (a/unsub job-results-pub job-id result-ch))))
    (a/>!! job-requests job-id)))

(defn wrap-resource-exists
  [handler]
  (fn
    [{{:keys [pipeline job path]} :path-params :as request} respond raise]
    (let [job-dir (job-dir)
          f (fs/file job-dir pipeline job path)]
      (if (and (or (fs/directory? f) (fs/file? f)) (fs/ancestor? job-dir f))
        (handler request respond raise)
        (-> {:pipeline pipeline :job job :path path}
            (response/not-found)
            (respond))))))

(defn handle-resource-request
  [{{:keys [pipeline job path]} :path-params} respond _]
  (let [f (fs/file (job-dir) pipeline job path)]
    (->
     (cond
       ;; directories are always delivered as ZIP archives
       (fs/directory? f)
       (-> (partial fs/dir->zip-stream f)
           (ring-io/piped-input-stream)
           (response/response)
           (response/content-type "application/zip"))
       ;; files are delivered as-is
       (fs/file? f)
       (response/response f))
     (add-job-headers [pipeline job])
     (respond))))

(def handlers
  [""
   ["/:pipeline/"
    {:handler handle-job-request
     :middleware [wrap-pipeline-exists]
     :parameters {:path (s/keys :req-un [::pipeline])}}]
   ["/:pipeline/:job/*path"
    {:handler handle-resource-request
     :middleware [wrap-resource-exists]
     :parameters {:path (s/keys :req-un [::pipeline ::job ::path])}}]])
