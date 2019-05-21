(ns leiningen.clean
  "Remove all files from project's target-path."
  (:require [clojure.java.io :as io]
            [leiningen.core.utils :as utils]
            [leiningen.core.main :as main])
  (:import [java.io IOException]))

(defn real-directory?
  "Returns true if this file is a real directory, false if it is a symlink or a
  normal file."
  [f]
  (if (= :windows (utils/get-os))
    (.isDirectory f)
    (and (.isDirectory f)
         (not (utils/symlink? f)))))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents.
  Raise an exception if any deletion fails unless silently is true."
  [f & [silently]]
  (let [f (io/file f)]
    (when (real-directory? f)
      (doseq [child (.listFiles f)]
        (delete-file-recursively child silently)))
    (.setWritable f true)
    (io/delete-file f silently)))

(defn- protected-paths
  "Returns a set of leiningen project source directories and important files."
  [project]
  (let [root-dir (:root project)]
    (->> [:source-paths :java-source-paths :test-paths :resource-paths]
         (select-keys project)
         (mapcat val)
         (list* (io/file root-dir "doc")
                (io/file root-dir "project.clj"))
         (map io/file)
         (map #(.getCanonicalPath %))
         set)))

(defn- protected-path?
  "Is path one of the leiningen project files or directories (which we expect to
  be version controlled), or a descendant?"
  [project path]
  (let [protected-paths (protected-paths project)]
    (or (protected-paths (.getCanonicalPath (io/file path)))
        (some #(utils/ancestor? % path) protected-paths))))

(defn- protect-clean-targets?
  "Returns the value of :protect in the metadata map for the :clean-targets
  value."
  [project]
  (-> project :clean-targets meta (get :protect true)))

(defn- error-msg [& args]
  (apply str (concat args
                     "\nCheck :clean-targets"
                     " or override this behavior by adding metadata ->"
                     "\n  :clean-targets ^{:protect false} [...targets...]")))

(defn- sanity-check
  "Ensure that a clean-target string refers to a directory that is sensible to
  delete."
  [project clean-target]
  (when (and (string? clean-target)
             (protect-clean-targets? project))
    (cond (not (utils/ancestor? (:root project) clean-target))
          (main/abort (error-msg "Deleting path outside of the project root [\""
                                 clean-target "\"] is not allowed."))
          (protected-path? project clean-target)
          (main/abort (error-msg "Deleting non-target project paths [\""
                                 clean-target "%s\"] is not allowed.")))))

(defn- with-parent-target-path
  "Assoc the :target-path sans the profile suffix, if any format
  specifier is detected in the raw :target-path"
  [project]
  (if-let [tp (->> project meta :without-profiles :target-path (re-find #"(.*?)/[^/]*%") second)]
    (assoc project :target-path (if (.isAbsolute (io/file tp))
                                  tp
                                  (str (io/file (:root project) tp))))
    project))

(defn clean
  "Removes all files from paths in clean-targets for a project"
  [project]
  (let [project (with-parent-target-path project)]
    (doseq [target-key (:clean-targets project)]
      (when-let [target (cond (vector? target-key)  (get-in project target-key)
                          (keyword? target-key) (target-key project)
                          (string? target-key)  target-key)]
        (doseq [f (flatten [target])]
          (sanity-check project f)
          (delete-file-recursively f :silently))))))
