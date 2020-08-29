(ns idrovora.cli
  (:require [clojure.java.io :as io]
            [clojure.stacktrace :refer [print-stack-trace]]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [cronjure.core :as cron]
            [cronjure.definitions :as crondef]
            [mount.core :as mount])
  (:import java.time.Duration))

(def parse-cron
  (partial cron/parse crondef/quartz))

(def cli-args
  [[nil "--http PORT"
    "HTTP port for embedded server"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   [nil "--http-context-path PATH"
    "HTTP context path ('' by default)"]
   [nil "--http-doc-root DIR"
    "HTTP document root for pipeline jobs"
    :parse-fn io/file]
   [nil "--http-cleanup-schedule CLEANUP_CRON_EXPR"
    "Schedule for periodic cleanup of old jobs (cron expression)"
    :parse-fn #(cron/parse crondef/quartz %)
    :default (cron/parse crondef/quartz "0 1 0 * * ?")
    :default-desc "0 1 0 * * ?"]
   [nil "--http-job-max-age JOB_MAX_AGE"
    "Maximum age of jobs; older jobs are removed periodically"
    :parse-fn #(Duration/parse %)
    :default (Duration/ofDays 7)]
   ["-h" "--help"]])

(comment
  (parse-opts ["--http" "2000"] cli-args))
(defn usage [options-summary]
  (->>
   ["Idrovora - A pump station for your XProc pipelines"
    "Copyright (C) 2020 Gregor Middell"
    ""
    "See <https://github.com/gremid/idrovora> for more information."
    ""
    "Usage: clojure -m idrovora.cli [OPTION...] [<DIR>...]"
    ""
    "Options:"
    options-summary
    ""
    "This program is free software: you can redistribute it and/or modify"
    "it under the terms of the GNU General Public License as published by"
    "the Free Software Foundation, either version 3 of the License, or"
    "(at your option) any later version."
    ""
    "This program is distributed in the hope that it will be useful,"
    "but WITHOUT ANY WARRANTY; without even the implied warranty of"
    "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the"
    "GNU General Public License for more details."
    ""
    "You should have received a copy of the GNU General Public License"
    "along with this program. If not, see <https://www.gnu.org/licenses/>."]
   (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit
  ([status]
   (exit status ""))
  ([status msg]
   (when (not-empty msg) (println msg))
   (System/exit status)))

(defn start
  ([]
   (start {} "."))
  ([options & dirs]
   (let [dirs (map io/file dirs)
         dirs (distinct (or (seq dirs) (list (io/file "."))))]
     (log/info "Starting Idrovora")
     (when (:http options) (require 'idrovora.http))
     (->>
      (mount/with-args (merge options {:dirs dirs}))
      (mount/start)))))

(defn stop
  []
  (mount/stop)
  (log/info "Stopped Idrovora"))

(defn -main
  [& args]
  (let [{:keys [options errors summary arguments]} (parse-opts args cli-args)]
    (when (:help options) (exit 0 (usage summary)))
    (when errors (exit 1 (error-msg errors)))
    (try
      (.. (Runtime/getRuntime)
          (addShutdownHook (Thread. (partial stop))))
      (apply start options arguments)
      (.. (Thread/currentThread) (join))
      (catch Throwable t
        (print-stack-trace t)
        (exit 2)))))
