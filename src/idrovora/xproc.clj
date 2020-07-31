(ns idrovora.xproc
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [idrovora.fs :as fs])
  (:import [com.xmlcalabash.core XProcConfiguration XProcRuntime]
           com.xmlcalabash.model.RuntimeValue
           com.xmlcalabash.runtime.XPipeline
           com.xmlcalabash.util.Input
           net.sf.saxon.s9api.QName))

(def config
  (delay (XProcConfiguration.)))

(defn run-pipeline!
  [xpl options]
  (let [^XProcRuntime runtime (XProcRuntime. @config)
        ^XPipeline pipeline (.load runtime (Input. xpl))]
    (doseq [[k v] options]
      (.. pipeline (passOption (QName. k) (RuntimeValue. v))))
    (.. pipeline (run))))

(defn run-job!
  [{:keys [::xpl ::job-dir] :as job}]
  (try
    (log/debugf "? [%s] @ [%s]" xpl job-dir)
    (run-pipeline! (fs/uri xpl) {"job-dir" (fs/uri job-dir)})
    (log/debugf ". [%s] @ [%s]" xpl job-dir)
    job
    (catch Throwable t
      (log/warnf t "! [%s] @ [%s]" xpl job-dir)
      (assoc job ::error t))))

(def running-jobs
  (atom {}))

(defn remove-running-jobs
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result {:keys [::job-dir] :as job}]
     (when
         (locking running-jobs
           (and (nil? (get @running-jobs job-dir))
                (swap! running-jobs assoc job-dir true)))
       (rf result job)))))

(defn remove-finished-jobs
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result {:keys [::job-dir] :as job}]
     (swap! running-jobs dissoc job-dir)
     (rf result job))))

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

(def job-results-pub
  (a/pub job-results ::job-dir))

(defn submit-job
  [{:keys [::job-dir] :as job}]
  (let [result (a/chan)]
    (a/sub job-results-pub job-dir result)
    (a/>!! job-requests job)
    (assoc job
           ::result result
           ::unsubscribe (partial a/unsub job-results-pub job-dir result))))
