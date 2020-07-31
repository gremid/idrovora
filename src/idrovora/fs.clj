(ns idrovora.fs
  (:refer-clojure :exclude [ancestors descendants])
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.io File InputStream OutputStream]
           java.nio.charset.Charset
           [java.nio.file CopyOption Files LinkOption Path]
           [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

(extend-protocol io/Coercions
  Path
  (as-file [^Path p] (.toFile p))
  (as-url [^Path p] (.. p (toFile) (toURI) (toURL))))

(defn ^File file
  [& args]
  (let [^File f (apply io/file args)]
    (.getCanonicalFile f)))

(defn file?
  [& args]
  (.isFile (apply file args)))

(defn directory?
  [& args]
  (.isDirectory (apply file args)))

(defn files
  [& args]
  (->> (apply file args)
       (file-seq)
       (filter file?)
       (map file)))

(defn dir-contents
  [& args]
  (.listFiles ^File (apply file args)))

(defn file-name
  [^File f]
  (.getName f))

(defn ^String path
  [& args]
  (.getPath (apply file args)))

(defn ^String uri
  [& args]
  (.. (apply file args) (toURI) (toASCIIString)))

(def ^:private no-link-options (make-array LinkOption 0))

(defn ^Path file-path
  [& args]
  (let [^File f (apply file args)
        ^Path p (.toPath f)]
    (if (.exists f) (.toRealPath p no-link-options) p)))

(defn ^Path resolve-path
  [base ^String p]
  (.resolve (file-path base) p))

(defn ^Path relativize-path
  [base & args]
  (.relativize (file-path base) (apply file-path args)))

(defn ancestors
  [& args]
  (let [^File f (apply file args)
        ^File parent (.getParentFile f)]
    (concat [f] (when parent (ancestors parent)))))

(defn descendants
  [& args]
  (let [^File f (apply file args)]
    (if (.isDirectory f)
      (let [children (sort #(compare %2 %1) (.listFiles f))]
        (lazy-cat (mapcat descendants children) children)))))

(defn ancestor?
  [a f]
  (.startsWith (file-path f) (file-path a)))

(def ^:private copy-options
  (make-array CopyOption 0))

(defn copy
  [src dest]
  (let [^File dest (file dest)
        ^File dest-parent (.getParentFile dest)]
    (.mkdirs dest-parent)
    (Files/copy (file-path src) (file-path dest) copy-options)))

(defn delete!
  [^File f & [silently]]
  (when (.isDirectory f)
    (doseq [^File c (.listFiles f)] (delete! c)))
  (io/delete-file f silently))

(def ^:private temp-dir-attrs
  (make-array java.nio.file.attribute.FileAttribute 0))

(defn make-temp-dir!
  [^String prefix]
  (file (java.nio.file.Files/createTempDirectory prefix temp-dir-attrs)))

(defn make-dir!
  [& args]
  (let [^File f (apply file args)]
    (when-not (.isDirectory f) (.mkdirs f))
    f))

(defn clear-dir!
  [& args]
  (let [^File f (apply file args)]
    (when (.isDirectory f) (delete! f))
    (make-dir! f)))

(defn dir->zip-stream
  [d ^OutputStream os]
  (with-open [zip-os (ZipOutputStream. os (Charset/forName "UTF-8"))]
    (let [file->path (comp str (partial relativize-path d))]
      (doseq [f (files d)]
        (.putNextEntry zip-os (ZipEntry. (file->path f)))
        (with-open [is (io/input-stream f)] (io/copy is zip-os))
        (.closeEntry zip-os)))))

(defn zip-stream->dir
  [d ^InputStream is]
  (with-open [zip-is (ZipInputStream. is (Charset/forName "UTF-8"))]
    (loop []
      (when-let [^ZipEntry e (.getNextEntry zip-is)]
        (when-not (.isDirectory e)
          (let [^File f (file (resolve-path d (.getName e)))]
            (log/debugf "%s -> %s" e f)
            (make-dir! (.getParentFile f))
            (io/copy zip-is f)))
        (recur)))))
