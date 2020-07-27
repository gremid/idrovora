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
            [ring.util.response :as response])
  (:import com.cronutils.model.Cron
           java.io.File
           java.nio.file.NoSuchFileException))

;; ## Canonical file and directory locations in the workspace.

(defn ^File xpl-dir
  []
  (-> (mount/args) ::xpl-dir fs/file))

(defn ^File pipeline-xpl
  [pipeline]
  (fs/file (xpl-dir) (str pipeline ".xpl")))

(defn ^File job-dir
  ([] (-> (mount/args) ::job-dir fs/file))
  ([pipeline job] (fs/file (job-dir) pipeline job)))

(defn ^File job-status-txt
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

(defn log-exception
  [tr]
  (log/warn tr "Exception during job processing"))

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
     log-exception)
    ch))

;; ## Filesystem watcher (hot-folder mode)

(defn fs-event->job-request
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result {:keys [file action] :as evt}]
     (try
       (let [^File job-dir (job-dir)]
         (when (fs/ancestor? job-dir file)
           (let [[pipeline job] (map str (fs/relativize-path job-dir file))]
             (when (and pipeline job (fs/file? (pipeline-xpl pipeline)))
               (let [source-ready (job-status-txt pipeline job "source-ready")]
                 (when (= source-ready file)
                   (rf result [pipeline job])))))))
       ;; Deleted files cannot be augmented with job information
       (catch NoSuchFileException e)))))

(defstate fs-event-listener
  :start (let [^File job-dir (fs/make-dir! (job-dir))
               fs-requests (a/chan 1 fs-event->job-request log-exception)
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

(def job-results-pub
  (a/pub job-results #(subvec % 0 2)))

(defn handle-pipeline-request
  [{{{:keys [pipeline]} :path} :parameters :as req} respond _]
  (let [job-id ["test" "82d11012-cf02-4ec0-b3c6-f9fe004de7b0"]
        result-ch (a/chan)]
    (a/sub job-results-pub job-id result-ch)
    (a/go
      (try
        (a/alt!
          result-ch ([result] (respond (response/response {:result result})))
          (a/timeout 30000) (respond (response/response {:error "Timeout"})))
        (finally
          (a/unsub job-results-pub job-id result-ch))))
    (a/>!! job-requests job-id)))

(defn handle-resource-request
  [{{{:keys [pipeline job path]} :path} :parameters :as req} respond _]
  (let [^File job-dir (job-dir)
        ^File f (fs/file job-dir pipeline job path)
        p (str/join "/" [pipeline job path])]
    (->
     (cond
       ;; we only deliver job resources
       (not (fs/ancestor? job-dir f))
       (response/bad-request p)
       ;; directories are always delivered as ZIP archives
       (fs/directory? f)
       (-> (response/response f)
           (assoc :muuntaja/content-type "application/zip"))
       ;; files are delivered based on content negotiation
       (fs/file? f)
       (response/response f)
       ;; fallback: HTTP-404
       :else
       (-> (response/not-found p)
           (response/content-type "text/plain")))
     (respond))))

(def handlers
  [""
   ["/:pipeline/"
    {:handler handle-pipeline-request
     :parameters {:path (s/keys :req-un [::pipeline])}}]
   ["/:pipeline/:job/*path"
    {:handler handle-resource-request
     :parameters {:path (s/keys :req-un [::pipeline ::job ::path])}
     :muuntaja fs/muuntaja}]])
