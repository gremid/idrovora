(ns idrovora.job-test
  (:require  [clojure.test :refer :all]
             [clj-http.client :as http]
             [idrovora.cli :as cli]
             [idrovora.fs :as fs]
             [clojure.tools.logging :as log]))

(defn cli-fixture
  [f]
  (try
    (cli/start {:http 3000} "test/xpl")
    (f)
    (finally
      (cli/stop))))

(use-fixtures :once cli-fixture)

(deftest placeholder
  (is (->>
       {:form-params {:input "<root/>"}}
       (http/post "http://localhost:3000/copy/")
       (log/spy :debug))))

