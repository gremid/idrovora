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
           java.nio.file.Path))

(defn ^Path file-path
  [^File f]
  (.toPath f))

(defn ^String file-uri
  [^File f]
  (.. f (toURI) (toASCIIString)))

(defn ^File absolute-file
  [^File f]
  (.getAbsoluteFile f))

(defn file-ancestors
  [^File f]
  (if f (lazy-seq (cons f (file-ancestors (.getParentFile f))))))

(defn file-descendants
  [^File f]
  (if (.isDirectory f)
    (let [children (sort #(compare %2 %1) (.listFiles f))]
      (lazy-cat (mapcat file-descendants children) children))))

(defn file-ancestor?
  [^File f ^File a]
  (.startsWith (file-path f) (file-path a)))

(defn pipelines-changed?
  [dir {:keys [^File file]}]
  (file-ancestor? file (io/file dir "pipelines")))

(defn jobs-changed?
  [dir {:keys [^File file action]}]
  (and (#{:create :modify} action) (file-ancestor? file (io/file dir "jobs"))))

(defn job->status-change
  [{:keys [^File job ^File file]}]
  (let [[p1 p2] (map str (.relativize (file-path job) (file-path file)))]
    (when (and (= "status" p1) (some? p2))
      (keyword p2))))

(defn job->pipeline
  [dir ^File job]
  (let [id (.. job (getParentFile) (getName))
        ^File pipeline (io/file dir "pipelines" id)]
    (if (.isDirectory pipeline) pipeline)))

(defn event->job
  [dir {:keys [^File file action] :as evt}]
  (let [[_ ^File job]
        (->> (file-ancestors file)
             (take-while (complement #{(io/file dir "jobs")}))
             (reverse))]
    (when-let [pipeline (if job (job->pipeline dir job))]
      (assoc evt :pipeline pipeline :job job))))

(defstate fs-events
  :start (let [{:keys [::dir]} (mount/args)]
           (doseq [^File d [dir (io/file dir "pipelines") (io/file dir "jobs")]]
             (when-not (.isDirectory d) (.mkdirs d)))
           (let [^File dir (absolute-file dir)
                 ch (a/chan)]
             (log/infof "Start watching workspace '%s'" dir)
             [dir ch (watch-dir (partial a/>!! ch) dir)]))
  :stop (let [[dir ch watcher] fs-events]
          (log/infof "Stop watching workspace '%s'" dir)
          (close-watcher watcher)
          (a/close! ch)))

(defn run-job!
  [{:keys [job pipeline]}]
  (let [xpl (file-uri (io/file pipeline "main.xpl"))
        source (file-uri (io/file job "source"))
        result (file-uri (io/file job "result"))
        result-ready (io/file job "status" "result-ready")
        job-failed (io/file job "status" "job-failed")
        options {"source-dir" source "result-dir" result}]
    (try
      (xproc/run-pipeline! xpl options)
      (spit result-ready "")
      (catch Throwable t
        (log/warnf t "Error running %s" job)
        (spit job-failed "")))))

(defstate event-listener
  :start (a/go-loop []
           (let [[dir ch] fs-events]
             (when-let [evt (a/<! ch)]
               (log/trace (str [(-> evt :action) (-> evt :file str)]))
               (when (jobs-changed? dir evt)
                 (when-let [job (event->job dir evt)]
                   (when (= :source-ready (job->status-change job))
                     (run-job! job))))
               (when (pipelines-changed? dir evt)
                 (log/info "Pipelines changed"))
               (recur)))))

(defstate cleanup-scheduler
  :start
  (let [{:keys [::dir ::cleanup-schedule ::job-max-age]} (mount/args)
        schedule (cron/parse crondef/quartz cleanup-schedule)
        max-age (java.time.Duration/parse job-max-age)
        ch (a/chan)]
    (a/go-loop []
      (let [millis-to-next (cron/time-to-next-execution schedule :millis)]
        (when-let [_ (a/alt! (a/timeout millis-to-next) :scheduled ch ([v] v))]
          (log/infof "Cleaning up %s" dir)
          (recur))))
    ch)
  :stop
  (a/close! cleanup-scheduler))
