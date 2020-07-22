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
           java.io.File))

(defn ^File xpl-dir
  []
  (-> (mount/args) ::xpl-dir fs/file))

(defn ^File job-dir
  []
  (-> (mount/args) ::job-dir fs/file))

(defn fs-event->job
  [{:keys [file action] :as evt}]
  (let [^File job-dir (job-dir)]
    (if (fs/ancestor? job-dir file)
      (let [[pipeline job p1 p2] (map str (fs/relativize-path job-dir file))]
        (if (and pipeline job p2 (= "status" p1))
          (let [^File xpl (fs/file (xpl-dir) (str pipeline ".xpl"))]
            (if (.isFile xpl)
              {:pipeline pipeline
               :job job
               :xpl xpl
               :status (-> p2 str keyword)})))))))

(defstate fs-events
  :start (let [^File job-dir (fs/make-dir! (job-dir))
               ch (a/chan)
               on-event (fn [evt] (a/>!! ch (merge evt (fs-event->job evt))))
               watcher (watch-dir on-event job-dir)]
           (log/infof "Start watching '%s'" job-dir)
           [ch watcher])
  :stop (let [[ch watcher] fs-events]
          (log/infof "Stop watching '%s'" (job-dir))
          (close-watcher watcher)
          (a/close! ch)))

(def running-jobs
  (atom {}))

(defn run-job!
  [{:keys [pipeline job xpl]}]
  (let [job-dir (fs/file (job-dir) pipeline job)
        status (fs/file job-dir "status")
        result-ready (fs/file status "result-ready")
        job-failed (fs/file status "job-failed")]
    (try
      (log/debugf "Running %s" [pipeline job])
      (xproc/run-pipeline!
       (fs/uri xpl)
       {"source-dir" (fs/uri job-dir "source")
        "result-dir" (fs/uri job-dir "result")})
      (fs/delete! job-failed true)
      (spit result-ready "")
      (log/debugf "Completed %s" [pipeline job])
      (catch Throwable t
        (log/warnf t "Error running %s" [pipeline job])
        (fs/delete! result-ready true)
        (spit job-failed "")))))

(defstate event-listener
  :start (a/go-loop []
           (let [[ch] fs-events]
             (when-let [evt (a/<! ch)]
               (condp = (evt :status)
                 :source-ready (run-job! evt)
                 (log/trace evt))
               (recur)))))

(defstate cleanup-scheduler
  :start
  (let [{:keys [::job-dir ::cleanup-schedule ::job-max-age]} (mount/args)
        next (partial cron/time-to-next-execution cleanup-schedule :millis)
        ch (a/chan)]
    (log/infof "Scheduling cleanup of old jobs (%s)"
               (.asString ^Cron cleanup-schedule))
    (a/go-loop []
      (when-let [_ (a/alt! (a/timeout (next)) :scheduled ch ([v] v))]
        (log/infof "Cleaning up %s" job-dir)
        (recur)))
    ch)
  :stop
  (a/close! cleanup-scheduler))

(defn handle-resource-request
  [{{{:keys [pipeline job path] :as resource} :path} :parameters :as req}
   respond _]
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
   ["/:pipeline/:job/*path"
    {:handler handle-resource-request
     :parameters {:path (s/keys :req-un [::pipeline ::job ::path])}
     :muuntaja fs/muuntaja}]])
