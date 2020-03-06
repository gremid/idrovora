(ns idrovora.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [idrovora.workspace :as ws]
            [mount.core :as mount])
  (:import java.io.File))

(def default-args
  {:idrovora.workspace/dir (io/file "workspace")
   :idrovora.workspace/cleanup-schedule "0 1 0 * * ?"
   :idrovora.workspace/job-max-age "PT168H"})

(defn start
  ([]
   (start {}))
  ([args]
   (->> args
        (merge default-args)
        (mount/with-args)
        (mount/start))))

(defn -main
  [& args]
  (let [{:keys [arguments]} (parse-opts args [])
        ws-dir (some-> arguments first io/file)]
    (->> (merge (when ws-dir {:idrovora.workspace/dir ws-dir}))
         (start)))
    (.. (Thread/currentThread) (join)))
