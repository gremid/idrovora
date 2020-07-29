(ns idrovora.job-test
  (:require  [clojure.test :refer :all]
             [clj-http.client :as http]
             [idrovora.cli :as cli]
             [idrovora.fs :as fs]
             [idrovora.workspace :as ws]
             [clojure.tools.logging :as log]))

(defn cli-fixture
  [f]
    (let [pipelines (fs/file "test" "xpl")
          jobs (fs/make-temp-dir! "idrovora-test-jobs-")]
      (try
        (cli/start {::ws/xpl-dir pipelines ::ws/job-dir jobs})
        (f)
        (finally
          (cli/stop)
          (fs/delete! jobs true)))))

(use-fixtures :once cli-fixture)


(deftest placeholder
  (is (log/spy :debug (http/post "http://localhost:3000/copy/"
                                 {:form-params {:document1 "<root/>"}
                                  :redirect-strategy :lax}))))

