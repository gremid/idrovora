(ns idrovora.workspace
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cronjure.core :as cron]
            [cronjure.definitions :as crondef]
            [idrovora.xproc :as xproc]
            [juxt.dirwatch :refer [close-watcher watch-dir]]
            [mount.core :as mount :refer [defstate]])
  (:import java.io.File
           java.nio.file.Path
           java.nio.file.LinkOption
           com.cronutils.model.Cron))

(def ^:private no-link-options (make-array LinkOption 0))

(defn ^Path file-path
  [^File f]
  (let [^Path p (.toPath f)]
    (if (.exists f) (.toRealPath p no-link-options) p)))

(defn ^String file-uri
  [^File f]
  (.. f (toURI) (toASCIIString)))

(defn file-ancestors
  [^File f]
  (if f
    (let [^File f (.getAbsoluteFile f)]
      (lazy-seq (cons f (file-ancestors (.getParentFile f)))))))

(defn file-descendants
  [^File f]
  (if (.isDirectory f)
    (let [children (sort #(compare %2 %1) (.listFiles f))]
      (lazy-cat (mapcat file-descendants children) children))))

(defn file-ancestor?
  [^File f ^File a]
  (.startsWith (file-path f) (file-path a)))

(defn jobs-changed?
  [{:keys [^File file action]}]
  (let [{:keys [::job-dir]} (mount/args)]
    (and (#{:create :modify} action) (file-ancestor? file job-dir))))

(defn job->status-change
  [{:keys [^File job ^File file]}]
  (let [[p1 p2] (map str (.relativize (file-path job) (file-path file)))]
    (when (and (= "status" p1) (some? p2))
      (keyword p2))))

(defn job->pipeline
  [^File job]
  (let [^File xpl-dir (::xpl-dir (mount/args))
        id (.. job (getParentFile) (getName))
        pipeline (io/file xpl-dir (str id ".xpl"))]
    (if (.isFile pipeline) pipeline)))

(defn event->job
  [{:keys [^File file action] :as evt}]
  (let [^File job-dir (::job-dir (mount/args))
        job-dir (.getAbsoluteFile job-dir)
        [_ ^File job]
        (->> (file-ancestors file)
             (take-while (complement #{job-dir}))
             (reverse))]
    (when-let [pipeline (if job (job->pipeline job))]
      (assoc evt :pipeline pipeline :job job))))

(defstate fs-events
  :start (let [^File job-dir (::job-dir (mount/args))
               ch (a/chan)]
           (when-not (.isDirectory job-dir) (.mkdirs job-dir))
           (log/infof "Start watching '%s'" job-dir)
           [ch (watch-dir (partial a/>!! ch) job-dir) job-dir])
  :stop (let [[ch watcher job-dir] fs-events]
          (log/infof "Stop watching '%s'" job-dir)
          (close-watcher watcher)
          (a/close! ch)))

(defn run-job!
  [{:keys [job pipeline]}]
  (let [xpl (file-uri pipeline)
        source (file-uri (io/file job "source"))
        result (file-uri (io/file job "result"))
        result-ready (io/file job "status" "result-ready")
        job-failed (io/file job "status" "job-failed")
        options {"source-dir" source "result-dir" result}]
    (try
      (log/debugf "Running job %s" (str job))
      (xproc/run-pipeline! xpl options)
      (spit result-ready "")
      (log/debugf "Completed job %s" (str job))
      (catch Throwable t
        (log/warnf t "Error running job %s" (str job))
        (spit job-failed "")))))

(defstate event-listener
  :start (a/go-loop []
           (let [[ch] fs-events]
             (when-let [evt (a/<! ch)]
               (log/trace (str [(-> evt :action) (-> evt :file str)]))
               (when (jobs-changed? evt)
                 (when-let [job (event->job evt)]
                   (when (= :source-ready (job->status-change job))
                     (run-job! job))))
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
