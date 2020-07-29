(ns idrovora.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [cronjure.core :as cron]
            [cronjure.definitions :as crondef]
            [idrovora.http :as http]
            [idrovora.workspace :as ws]
            [mount.core :as mount])
  (:import com.cronutils.model.Cron
           java.time.Duration))

(defn parse-cron
  [s]
  (cron/parse crondef/quartz s))

(def defaults
  {:idrovora.workspace/job-dir (io/file "workspace" "jobs")
   :idrovora.workspace/xpl-dir (io/file "workspace" "xpl")
   :idrovora.workspace/cleanup-schedule (parse-cron "0 1 0 * * ?")
   :idrovora.workspace/job-max-age (Duration/parse "PT168H")
   :idrovora.http/http-port 3000
   :idrovora.http/http-context-path ""})

(defn cli-arg
  ([id desc]
   (cli-arg id desc nil))
  ([id desc short-opt]
   (cli-arg id desc short-opt identity))
  ([id desc short-opt parse]
   (let [k (name id)
         env (str/join "-" ["idrovora" k])
         env (-> env (str/replace #"-" "_") (str/upper-case))
         long-opt (str "--" k " $" env)
         default-val (or (some-> env System/getenv parse) (defaults id))
         default-desc (condp instance? default-val
                        Cron (.asString ^Cron default-val)
                        (str default-val))]
     [short-opt long-opt desc
      :id id
      :parse-fn parse
      :default default-val
      :default-desc default-desc])))

(def cli-args
  [(cli-arg
    :idrovora.workspace/xpl-dir
    "source directory with XProc pipeline definitions"
    "-x" io/file)
   (cli-arg
    :idrovora.workspace/job-dir
    "spool directory for pipeline jobs"
    "-j" io/file)
   (cli-arg
    :idrovora.http/http-port
    "HTTP port for embedded server"
    "-p" #(Integer/parseInt %))
   (cli-arg
    :idrovora.http/http-context-path
    "HTTP context path (empty aka. '' by default)")
   (cli-arg
    :idrovora.workspace/cleanup-schedule
    "Schedule for periodic cleanup of old jobs (cron expression)"
    "-c" parse-cron)
   (cli-arg
    :idrovora.workspace/job-max-age
    "Maximum age of jobs; older jobs are removed periodically"
    "-a" #(Duration/parse %))
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
   (start {}))
  ([args]
   (log/info "Starting Idrovora")
   (->> args
        (merge defaults)
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
