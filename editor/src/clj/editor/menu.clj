(ns editor.menu
  (:require [editor.handler :as handler]))

(set! *warn-on-reflection* true)

;; *menus* is a map from id to a list of extensions, extension with location nil effectively root menu
(defonce ^:dynamic *menus* (atom {}))

(defn extend-menu [id location menu]
  (swap! *menus* update id (comp distinct conj) {:location location :menu menu}))

(defn- collect-menu-extensions []
  (->>
    (flatten (vals @*menus*))
    (filter :location)
    (reduce (fn [acc x] (update acc (:location x) concat (:menu x))) {})))


(defn- do-realize-menu [menu exts]
  (into []
        (comp (map (fn [{:keys [command children] :as menu-data-item}]
                     (cond-> menu-data-item
                       children
                       (update :children do-realize-menu exts))))
              (mapcat (fn [x]
                        (if (and (contains? x :id) (contains? exts (:id x)))
                          (into [x] (do-realize-menu (get exts (:id x)) exts))
                          [x]))))
        menu))

(defn- add-handler-data
  [command-contexts menu-data]
  (let [{:keys [command label user-data acc]} menu-data]
    (let [menu-data' (if-not command
                       menu-data
                       (if-let [handler-ctx (handler/active command command-contexts user-data)]
                         (let [options  (when acc (handler/options handler-ctx))
                               children (when-not acc
                                          (when-let [options (seq (handler/options handler-ctx))]
                                            (into (or (:children menu-data) []) options)))
                               type (cond
                                      (true? (:check menu-data))
                                      :check-menu-item

                                      :else
                                      :menu-item)]
                           (cond-> (assoc menu-data
                                          :type type
                                          :active? true
                                          :enabled? (handler/enabled? handler-ctx)
                                          :state (handler/state handler-ctx)
                                          :label (or (handler/label handler-ctx) label))

                             options
                             (assoc :options options)

                             children
                             (assoc :children children)))
                         (assoc menu-data :active? false)))]
      (if (seq (:children menu-data'))
        (update menu-data' :children #(mapv (partial add-handler-data command-contexts) %))
        menu-data'))))

(defn realize-menu [id]
  (let [exts (collect-menu-extensions)
        menu (:menu (some (fn [x] (and (nil? (:location x)) x)) (get @*menus* id)))]
    (do-realize-menu menu exts)))

(defn realize-menu2 [id command-contexts]
  (let [exts (collect-menu-extensions)
        menu (:menu (some (fn [x] (and (nil? (:location x)) x)) (get @*menus* id)))]
    (mapv (partial add-handler-data command-contexts) (do-realize-menu menu exts))))


;; For testing only
(defmacro with-test-menus [& body]
  `(with-bindings {#'*menus* (atom {})}
     ~@body))

(comment

  (time (dotimes [n 100]
          (realize-menu :editor.app-view/menubar)))

  (time (dotimes [n 100]
          (realize-menu2 :editor.app-view/menubar (editor.ui/contexts (.getScene (editor.ui/main-stage))))))

  (clojure.pprint/pprint
    (realize-menu2 :editor.app-view/menu (editor.ui/contexts (.getScene (editor.ui/main-stage)))))
  


  )
