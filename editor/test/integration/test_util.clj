(ns integration.test-util
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.atlas :as atlas]
            [editor.collection :as collection]
            [editor.cubemap :as cubemap]
            [editor.game-object :as game-object]
            [editor.game-project :as game-project]
            [editor.image :as image]
            [editor.platformer :as platformer]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.scene-selection :as scene-selection]
            [editor.sprite :as sprite]
            [editor.switcher :as switcher]
            [editor.font :as font]
            [editor.protobuf-types :as protobuf-types]
            [editor.script :as script]
            [editor.workspace :as workspace]
            [editor.gl.shader :as shader]
            [editor.tile-source :as tile-source]
            [editor.sound :as sound]
            [editor.spine :as spine]
            [editor.particlefx :as particlefx]
            [editor.gui :as gui]
            [editor.json :as json]
            [editor.mesh :as mesh]
            [editor.material :as material]
            [editor.outline :as outline])
  (:import [java.io File]
           [java.nio.file Files attribute.FileAttribute]
           [javax.imageio ImageIO]
           [org.apache.commons.io FilenameUtils FileUtils]))

(def project-path "resources/test_project")

(defn setup-workspace!
  ([graph]
    (setup-workspace! graph project-path))
  ([graph project-path]
    (let [workspace (workspace/make-workspace graph project-path)]
      (g/transact
        (concat
          (scene/register-view-types workspace)))
      (g/transact
       (concat
        (collection/register-resource-types workspace)
        (font/register-resource-types workspace)
        (game-object/register-resource-types workspace)
        (game-project/register-resource-types workspace)
        (cubemap/register-resource-types workspace)
        (image/register-resource-types workspace)
        (atlas/register-resource-types workspace)
        (platformer/register-resource-types workspace)
        (protobuf-types/register-resource-types workspace)
        (switcher/register-resource-types workspace)
        (sprite/register-resource-types workspace)
        (script/register-resource-types workspace)
        (shader/register-resource-types workspace)
        (tile-source/register-resource-types workspace)
        (sound/register-resource-types workspace)
        (spine/register-resource-types workspace)
        (json/register-resource-types workspace)
        (mesh/register-resource-types workspace)
        (particlefx/register-resource-types workspace)
        (gui/register-resource-types workspace)
        (material/register-resource-types workspace)))
      (workspace/resource-sync! workspace)
      workspace)))

(defn setup-scratch-workspace! [graph project-path]
  (let [temp-project-path (-> (Files/createTempDirectory "test" (into-array FileAttribute []))
                              (.toFile)
                              (.getAbsolutePath))]
    (FileUtils/copyDirectory (io/file project-path) (io/file temp-project-path))
    (setup-workspace! graph temp-project-path)))

(defn setup-project!
  [workspace]
  (let [proj-graph (g/make-graph! :history true :volatility 1)
        project (project/make-project proj-graph workspace)
        project (project/load-project project)]
    (g/reset-undo! proj-graph)
    project))

(defn resource-node [project path]
  (project/get-resource-node project path))

(defn empty-selection? [project]
  (let [sel (g/node-value project :selected-node-ids)]
    (empty? sel)))

(defn selected? [project tgt-node-id]
  (let [sel (g/node-value project :selected-node-ids)]
    (not (nil? (some #{tgt-node-id} sel)))))

(g/defnode DummyAppView
  (property active-tool g/Keyword))

(defn setup-app-view! []
  (let [view-graph (g/make-graph! :history false :volatility 2)
        app-view (first (g/tx-nodes-added (g/transact (g/make-node view-graph DummyAppView :active-tool :move))))]
    app-view))

(defn set-active-tool! [app-view tool]
  (g/transact (g/set-property app-view :active-tool tool)))

(defn open-scene-view! [project app-view resource-node width height]
  (let [view-graph (g/make-graph! :history false :volatility 2)]
    (scene/make-preview view-graph resource-node {:app-view app-view :project project} width height)))

(defn- fake-input!
  ([view type x y]
    (fake-input! view type x y []))
  ([view type x y modifiers]
    (let [pos [x y 0.0]]
      (g/transact (g/set-property view :picking-rect (scene/calc-picking-rect pos pos))))
    (let [handlers  (g/sources-of view :input-handlers)
          user-data (g/node-value view :selected-tool-renderables)
          action    (reduce #(assoc %1 %2 true) {:type type :x x :y y} modifiers)
          action    (scene/augment-action view action)]
      (doseq [[node-id label] handlers]
        (let [handler-fn (g/node-value node-id label)]
          (handler-fn node-id action user-data))))))

(defn mouse-press!
  ([view x y]
    (fake-input! view :mouse-pressed x y))
  ([view x y modifiers]
    (fake-input! view :mouse-pressed x y modifiers)))

(defn mouse-move! [view x y]
  (fake-input! view :mouse-moved x y))

(defn mouse-release! [view x y]
  (fake-input! view :mouse-released x y))

(defn mouse-click!
  ([view x y]
    (mouse-click! view x y []))
  ([view x y modifiers]
    (mouse-press! view x y modifiers)
    (mouse-release! view x y)))

(defn mouse-drag! [view x0 y0 x1 y1]
  (mouse-press! view x0 y0)
  (mouse-move! view x1 y1)
  (mouse-release! view x1 y1))

(defn dump-frame! [view path]
  (let [image (g/node-value view :frame)]
    (let [file (File. path)]
      (ImageIO/write image "png" file))))

;; Copy-paste, DND

(def ^:private ^:dynamic *clipboard* nil)
(def ^:private ^:dynamic *dragboard* nil)
(def ^:private ^:dynamic *drag-source-iterators* nil)

(defn outline
  ([node]
    (outline node []))
  ([node path]
    (loop [outline (g/node-value node :node-outline)
           path path]
      (if-let [segment (first path)]
        (recur (get (vec (:children outline)) segment) (rest path))
        outline))))

(defrecord TestItemIterator [root-node path]
  outline/ItemIterator
  (value [this] (outline root-node path))
  (parent [this] (when (not (empty? path))
                   (TestItemIterator. root-node (butlast path)))))

(defn- ->iterator [root-node path]
  (TestItemIterator. root-node path))

(defn copy! [node path]
  (let [data (outline/copy [(->iterator node path)])]
    (alter-var-root #'*clipboard* (constantly data))))

(defn cut? [node path]
  (outline/cut? [(->iterator node path)]))

(defn cut! [node path]
  (let [data (outline/cut! [(->iterator node path)])]
    (alter-var-root #'*clipboard* (constantly data))))

(defn paste!
  ([project node]
    (paste! project node []))
  ([project node path]
    (let [it (->iterator node path)]
      (outline/paste! (project/graph project) it *clipboard* (partial project/select project)))))

(defn copy-paste! [project node path]
  (copy! node path)
  (paste! project node (butlast path)))

(defn drag! [node path]
  (let [src-item-iterators [(->iterator node path)]
        data (outline/copy src-item-iterators)]
    (alter-var-root #'*dragboard* (constantly data))
    (alter-var-root #'*drag-source-iterators* (constantly src-item-iterators))))

(defn drop!
  ([project node]
    (drop! project node []))
  ([project node path]
    (outline/drop! (project/graph project) *drag-source-iterators* (->iterator node path) *dragboard* (partial project/select project))))

(defn drop?
  ([project node]
    (drop? project node []))
  ([project node path]
    (outline/drop? (project/graph project) *drag-source-iterators* (->iterator node path) *dragboard*)))
