(ns idrovora.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [cronjure.core :as cron]
            [cronjure.definitions :as crondef]
            [idrovora.http :as http]
            [idrovora.workspace :as ws]
            [mount.core :as mount]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import java.io.File
           java.time.Duration
           com.cronutils.model.Cron))

(def parse-cron
  (partial cron/parse crondef/quartz))

(def defaults
  {:idrovora.workspace/job-dir (io/file "workspace" "jobs")
   :idrovora.workspace/xpl-dir (io/file "workspace" "xpl")
   :idrovora.workspace/cleanup-schedule (parse-cron "0 1 0 * * ?")
   :idrovora.workspace/job-max-age (Duration/parse "PT168H")
   :idrovora.http/http-port 3000})

(defn cli-arg-default
  ([id]
   (let [def-val (cli-arg-default id {})]
     (condp instance? def-val
       Cron (.asString ^Cron def-val)
       (str def-val))))
  ([id _]
   (let [env (str/join "-" ["idrovora" (name id)])
         env (-> env (str/replace #"-" "_") (str/upper-case))]
     (or (System/getenv env) (defaults id)))))

(def cli-args
  [["-x" "--xpl-dir $IDROVORA_XPL_DIR"
    "source directory with XProc pipeline definitions"
    :id :idrovora.workspace/xpl-dir
    :default-fn (partial cli-arg-default :idrovora.workspace/xpl-dir)
    :default-desc (cli-arg-default :idrovora.workspace/xpl-dir)
    :parse-fn io/file]
   ["-j" "--job-dir $IDROVORA_JOB_DIR"
    "spool directory for pipeline jobs"
    :id :idrovora.workspace/job-dir
    :default-fn (partial cli-arg-default :idrovora.workspace/job-dir)
    :default-desc (cli-arg-default :idrovora.workspace/job-dir)
    :parse-fn io/file]
   ["-p" "--port $IDROVORA_HTTP_PORT"
    "HTTP port for embedded server"
    :id :idrovora.http/http-port
    :default-fn (partial cli-arg-default :idrovora.http/http-port)
    :default-desc (cli-arg-default :idrovora.http/http-port)
    :parse-fn #(Integer/parseInt %)]
   ["-c" "--cleanup $IDROVORA_CLEANUP_SCHEDULE"
    "Schedule for periodic cleanup of old jobs (cron expression)"
    :id :idrovora.workspace/cleanup-schedule
    :default-fn (partial cli-arg-default :idrovora.workspace/cleanup-schedule)
    :default-desc (cli-arg-default :idrovora.workspace/cleanup-schedule)
    :parse-fn parse-cron]
   ["-a" "--job-max-age $IDROVORA_JOB_MAX_AGE"
    "Maximum age of jobs; older jobs are removed periodically"
    :id :idrovora.workspace/job-max-age
    :default-fn (partial cli-arg-default :idrovora.workspace/job-max-age)
    :default-desc (cli-arg-default :idrovora.workspace/job-max-age)
    :parse-fn #(Duration/parse %)]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Idrovora - A pump station for your XProc pipelines"
        "Copyright (C) 2020 Gregor Middell"
        ""
        "See <https://github.com/gremid/idrovora> for more information."
        ""
        "Usage: java -jar idrovora.jar [OPTION]..."
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
   (start defaults))
  ([args]
   (log/info "Starting Idrovora")
   (->> args
        (mount/with-args)
        (mount/start))))

(defn stop
  []
  (log/info "Stopping Idrovora")
  (mount/stop))

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-args)]
    (cond
      (:help options)
      (exit 0 (usage summary))
      errors
      (exit 1 (error-msg errors))
      :else
      (try
        (.. (Runtime/getRuntime)
            (addShutdownHook (Thread. (partial stop))))
        (start options)
        (.. (Thread/currentThread) (join))
        (catch Throwable t
          (.printStackTrace t)
          (exit 2))))))
