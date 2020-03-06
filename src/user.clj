(ns user
  (:require [idrovora.cli :as cli]
            [mount.core :as mount]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(defn start
  []
  (cli/start))

(defn stop
  []
  (mount/stop))


