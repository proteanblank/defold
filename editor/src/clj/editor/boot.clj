(ns editor.boot
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.stacktrace :as stack]
   [clojure.tools.cli :as cli]
   [dynamo.graph :as g]
   [editor.dialogs :as dialogs]
   [editor.error-reporting :as error-reporting]
   [editor.prefs :as prefs]
   [editor.progress :as progress]
   [editor.sentry :as sentry]
   [editor.ui :as ui]
   [editor.ui.open-project :as open-project]
   [service.log :as log])
  (:import
   [java.util Arrays]))

(set! *warn-on-reflection* true)

(def namespace-counter (atom 0))
(def namespace-progress-reporter (atom nil))

(alter-var-root (var clojure.core/load-lib)
                (fn [f]
                  (fn [prefix lib & options]
                    (swap! namespace-counter inc)
                    (when @namespace-progress-reporter
                      (@namespace-progress-reporter
                       #(assoc %
                               :message (str "Initializing editor " (if prefix
                                                                      (str prefix "." lib)
                                                                      (str lib)))
                               :pos @namespace-counter)))
                    (apply f prefix lib options))))



(defn- load-namespaces-in-background
  []
  ;; load the namespaces of the project with all the defnode
  ;; creation in the background
  (future
    (require 'editor.boot-open-project)))

(defn- open-project-with-progress-dialog
  [namespace-loader prefs project]
  (ui/modal-progress
   "Loading project" 100
   (fn [render-progress!]
     (let [progress (atom (progress/make "Loading project..." 733))
           project-file (io/file project)]
       (reset! namespace-progress-reporter #(render-progress! (swap! progress %)))
       (render-progress! (swap! progress progress/message "Initializing project..."))
       ;; ensure that namespace loading has completed
       @namespace-loader
       (apply (var-get (ns-resolve 'editor.boot-open-project 'initialize-project)) [])
       (apply (var-get (ns-resolve 'editor.boot-open-project 'open-project)) [project-file prefs render-progress!])
       (reset! namespace-progress-reporter nil)))))

(defn- select-project-from-welcome
  [namespace-loader prefs]
  (ui/run-later
   (open-project/open-project prefs
                              (fn [project]
                                (open-project-with-progress-dialog namespace-loader prefs project)))))

(defn notify-user
  [ex-map sentry-id-promise]
  (when (.isShowing (ui/main-stage))
    (ui/run-now
      (dialogs/make-error-dialog ex-map sentry-id-promise))))

(def cli-options
  ;; Path to preference file, mainly used for testing
  [["-prefs" "--preferences PATH" "Path to preferences file"]])

(defn main [args]
  (error-reporting/setup-error-reporting! {:notifier {:notify-fn notify-user}
                                           :sentry   {:project-id "97739"
                                                      :key        "9e25fea9bc334227b588829dd60265c1"
                                                      :secret     "f694ef98d47d42cf8bb67ef18a4e9cdb"}})
  (let [args (Arrays/asList args)
        opts (cli/parse-opts args cli-options)
        namespace-loader (load-namespaces-in-background)
        prefs (if-let [prefs-path (get-in opts [:options :preferences])]
                (prefs/load-prefs prefs-path)
                (prefs/make-prefs "defold"))]
    (try
      (if-let [game-project-path (get-in opts [:arguments 0])]
        (open-project-with-progress-dialog namespace-loader prefs game-project-path)
        (select-project-from-welcome namespace-loader prefs))
      (catch Throwable t
        (log/error :exception t)
        (stack/print-stack-trace t)
        (.flush *out*)
        ;; note - i'm not sure System/exit is a good idea here. it
        ;; means that failing to open one project causes the whole
        ;; editor to quit, maybe losing unsaved work in other open
        ;; projects.
        (System/exit -1)))))
