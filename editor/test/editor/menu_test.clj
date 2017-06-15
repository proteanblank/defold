(ns editor.menu-test
  (:require [clojure.test :refer :all]
            [editor.handler :as handler]
            [editor.menu :as menu]
            [editor.ui :as ui])
  (:import
   (javafx.scene.control CheckMenuItem MenuBar MenuItem)))

(defn fixture [f]
  (with-redefs [handler/*handlers* (atom {})
                menu/*menus* (atom {})]
    (f)))

(use-fixtures :each fixture)

(defn- global-context
  [env]
  (let [contexts [(handler/->context :global env)]]
    (handler/eval-contexts contexts true)))


;;--------------------------------------------------------------------
;; Tests for menu-definition -> menu-data

(defonce main-menu-data [{:label "File"
                          :id ::file
                          :children [{:label "New"
                                      :id ::new
                                      :acc "Shortcut+N"
                                      :command :new}
                                     {:label "Open"
                                      :id ::open
                                      :acc "Shortcut+O"
                                      :command :open}]}
                         {:label "Edit"
                          :id ::edit
                          :children [{:label "Undo"
                                      :acc "Shortcut+Z"
                                      :icon "icons/undo.png"
                                      :command :undo}
                                     {:label "Redo"
                                      :acc "Shift+Shortcut+Z"
                                      :icon "icons/redo.png"
                                      :command :redo}]}
                         {:label "Help"
                          :children [{:label "About"
                                      :command :about}]}])

(defonce scene-menu-data [{:label "Scene"
                           :children [{:label "Do stuff"}
                                      {:label :separator
                                       :id ::scene-end}]}])

(defonce tile-map-data [{:label "Tile Map"
                         :children [{:label "Erase Tile"}]}])

(deftest main-data-extension
  (menu/with-test-menus
    (menu/extend-menu ::menubar nil main-menu-data)
    (menu/extend-menu ::menubar ::edit scene-menu-data)
    (menu/extend-menu ::menubar ::scene-end tile-map-data)
    (let [m (menu/realize-menu ::menubar)]
      (is (some? (get-in m [2 :children]))))))


(deftest menu-data-generation
  (let [menu {:label    "Test"
              :id       ::test
              :acc      "Shortcut+T"
              :command  :test}]

    (handler/defhandler :test :global
      (active? [data] (:active? data))
      (enabled? [data] (:enabled? data))
      (state [data] (:state data))
      (label [data] (:label data))
      (options [data] (:options data)))

    (testing "evaluates no further data if command is inactive"
      (menu/with-test-menus
        (menu/extend-menu ::menubar nil [menu])
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? false}}))]
          (is (= {:label   "Test"
                  :id      ::test
                  :acc     "Shortcut+T"
                  :command :test
                  :active? false} menu)))))

    (testing "evaluates enabled if command is active"
      (menu/with-test-menus
        (menu/extend-menu ::menubar nil [menu])
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :enabled? false}}))]
          (is (not (:enabled? menu))))
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :enabled? true}}))]
          (is (:enabled? menu)))))

    (testing "evaluates state if command is active"
      (menu/with-test-menus
        (menu/extend-menu ::menubar nil [menu])
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :state false}}))]
          (is (not (:state menu))))
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :state true}}))]
          (is (:state menu)))))

    (testing "evaluates label if command is active"
      (menu/with-test-menus
        (menu/extend-menu ::menubar nil [menu])
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true}}))]
          (is (= "Test" (:label menu))))
        (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :label "Gurka"}}))]
          (is (= "Gurka" (:label menu))))))
    
    (testing "evaluates options if command is active"
      (let [options [{:label "Child"
                      :id ::child
                      :command :child
                      :options {:blah 42}}]]

        (testing "adds options as data if accelerator is bound"
          (menu/with-test-menus
            (menu/extend-menu ::menubar nil [menu])
            (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :options options}}))]
              (is (= options (:options menu))))))
        
        (testing "adds options as children, and recursively evaluates them, if no accelerator is bound"
          (menu/with-test-menus
            (menu/extend-menu ::menubar nil [(dissoc menu :acc)])

            (handler/defhandler :child :global
              (active? [data] (:active? data))
              (enabled? [child] (:enabled? child))
              (state [child] (:state child))
              (options [child] (:options child)))            
            
            (let [[menu] (menu/realize-menu2 ::menubar (global-context {:data {:active? true :options options}
                                                                        :child {:active? true :enabled? true}}))]
              (is (= [{:label "Child"
                       :id ::child
                       :type :menu-item
                       :command :child
                       :active? true
                       :enabled? true
                       :state nil
                       :options {:blah 42}}]
                     (:children menu))))))))))


;;--------------------------------------------------------------------
;; Tests for menu-data -> MenuBar 

(defn- id-map [^MenuBar menubar]
  (into {}
        (map (juxt (memfn getId) identity))
        (tree-seq ui/menu-items ui/menu-items menubar)))

(defn- lookup [^MenuBar menubar id]
  (->> (tree-seq ui/menu-items ui/menu-items menubar)
       (filter #(= id (.getId %)))
       first))

(defmacro with-menubar
  [name & body]
  `(let [~name (doto (MenuBar.) (.setId "menubar"))]
     ~@body))

(deftest update-menubar!-can-create-and-update-menu-item-from-data
  (testing "active? controls visibility"
    (with-menubar m
      (ui/update-menubar! m [{:id :a :active? false}])
      (let [a (lookup m "a")]
        (is (not (.isVisible a)))
        
        (ui/update-menubar! m [{:id :a :active? true}])
        (let [a' (lookup m "a")]
          (is (identical? a a'))
          (is (.isVisible a'))))))

  (testing "enabled? controls disabled"
    (with-menubar m
      (ui/update-menubar! m [{:id :a :enabled? false}])
      (let [a (lookup m "a")]
        (is (.isDisable a))
        
        (ui/update-menubar! m [{:id :a :enabled? true}])
        (let [a' (lookup m "a")]
          (is (identical? a a'))
          (is (not (.isDisable a')))))))

  (testing "label sets text"
    (with-menubar m
      (ui/update-menubar! m [{:id :a :label "foo"}])
      (let [a (lookup m "a")]
        (is (= "foo" (.getText a)))

        (ui/update-menubar! m [{:id :a :label "bar"}])
        (let [a' (lookup m "a")]
          (is (identical? a a'))
          (is (= "bar" (.getText a')))))))

  (testing "acc sets accelerator"
    (with-menubar m
      (ui/update-menubar! m [{:id :a :acc "Shortcut+A"}])
      (let [a (lookup m "a")]
        (is (= "Shortcut+A" (str (.getAccelerator a))))

        (ui/update-menubar! m [{:id :a :acc "Shortcut+B"}])
        (let [a' (lookup m "a")]
          (is (identical? a a'))
          (is (= "Shortcut+B" (str (.getAccelerator a))))))))

  (testing "type determines control class"
    (with-menubar m
      (ui/update-menubar! m [{:id :a}])
      (let [a (lookup m "a")]
        (is (instance? MenuItem a))

        (testing "new menu-item is created when type changes"
          (ui/update-menubar! m [{:id :a :type :check-menu-item}])
          (let [a' (lookup m "a")]
            (is (not (identical? a a')))
            (is (instance? CheckMenuItem a'))

            (ui/update-menubar! m [{:id :a}])
            (let [a'' (lookup m "a")]
              (is (not (identical? a' a'')))
              (is (instance? MenuItem a'')))))))))

(deftest update-menubar!-can-clear-itself
  (with-menubar m
    (is (nil? (seq (ui/menu-items m))))

    (ui/update-menubar! m [{:id :a} {:id :b :children [{:id :c}]}])
    (is (seq (ui/menu-items m)))

    (ui/update-menubar! m [{:id :a} {:id :b :children [{:id :c}]}])
    (is (seq (ui/menu-items m)))
    
    (ui/update-menubar! m [])
    (is (nil? (seq (ui/menu-items m))))))

(deftest update-menubar!-reuses-children
  (let [c1 {:id :child-1 :label "1"}
        c2 {:id :child-2 :label "2"}
        c3 {:id :child-3 :label "3"}
        c4 {:id :child-4 :label "4"}
        p  {:id :parent :children [c1 c2 c3]}]
    
    (testing "noop update reuses all children"
      (with-menubar m
        (ui/update-menubar! m [p])
        (let [[m1 m2 m3] (->> m ui/menu-items first ui/menu-items vec)]
          (ui/update-menubar! m [p])
          (let [items (->> m ui/menu-items first ui/menu-items vec)
                [m1' m2' m3'] items]
            (is (= 3 (count items)))
            (is (identical? m1 m1'))
            (is (identical? m2 m2'))
            (is (identical? m3 m3'))))))

    (testing "reordering reuses existing children"
      (with-menubar m
        (ui/update-menubar! m [p])
        (let [[m1 m2 m3] (->> m ui/menu-items first ui/menu-items vec)]
          (ui/update-menubar! m [(assoc p :children [c2 c3 c1])])
          (let [items (->> m ui/menu-items first ui/menu-items vec)
                [m2' m3' m1'] items]
            (is (= 3 (count items)))
            (is (identical? m1 m1'))
            (is (identical? m2 m2'))
            (is (identical? m3 m3'))))))

    (testing "adding one child reuses existing children"
      (with-menubar m
        (ui/update-menubar! m [p])
        (let [[m1 m2 m3] (->> m ui/menu-items first ui/menu-items vec)]
          (ui/update-menubar! m [(update p :children conj c4)])
          (let [items (->> m ui/menu-items first ui/menu-items vec)
                [m1' m2' m3' m4] items]
            (is (= 4 (count items)))
            (is (identical? m1 m1'))
            (is (identical? m2 m2'))
            (is (identical? m3 m3'))
            (is (some? m4))))))

    (testing "removing one child reuses existing children"
      (with-menubar m
        (ui/update-menubar! m [p])
        (let [[m1 m2 m3] (->> m ui/menu-items first ui/menu-items vec)]
          (ui/update-menubar! m [(assoc p :children [c1 c3])])
          (let [items (->> m ui/menu-items first ui/menu-items vec)
                [m1' m3'] items]
            (is (= 2 (count items)))
            (is (identical? m1 m1'))
            (is (identical? m3 m3'))))))))








