(ns build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.tools.deps.alpha.util.dir :as deps-dir]
            [uberdeps.api :as uberdeps])
  (:import java.io.File))

(defn ^File file
  [& args]
  (let [^File f (apply io/file args)]
    (.getCanonicalFile f)))

(def ^File project-dir
  (file ".."))

(def ^File classes-dir
  (file project-dir "classes"))

(defn delete
  [^File f]
  (doseq [^File f (reverse (file-seq f))] (.delete f)))

(defn delete-classes!
  []
  (when (.isDirectory classes-dir) (delete classes-dir))
  (.mkdirs classes-dir))

(defn sh-run!
  [& args]
  (let [{:keys [exit out err] :as result} (apply sh args)
        successful? (= 0 exit)]
    (when-not successful?
      (println (str out "\n" err))
      (throw (ex-info (str args) result)))
    result))

(defn compile!
  []
  (sh-run! "clojure" "dist/compile.clj" :dir project-dir))

(defn uberjar!
  []
  (let [deps (-> (file project-dir "deps.edn") slurp edn/read-string)]
    (binding [uberdeps/level :error]
      (deps-dir/with-dir project-dir
        (uberdeps/package deps "idrovora.jar")))
    (sh-run! "jar" "ufm" "idrovora.jar" "idrovora-manifest.mf")))

(defn -main
  [& args]
  (try
    (delete-classes!)
    (compile!)
    (uberjar!)
    (delete-classes!)
    (finally (shutdown-agents))))
