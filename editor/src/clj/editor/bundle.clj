(ns editor.bundle
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.ui :as ui]
            [editor.dialogs :as dialogs]
            [editor.system :as system]
            [editor.handler :as handler])
  (:import [java.io File]
           [javafx.scene Parent Node Scene Group]
           [javafx.stage Stage StageStyle Modality]
           [com.defold.editor.sign Signer]))

(set! *warn-on-reflection* true)

(defn- create-fat-ios-engine []
  (let [unpack (System/getProperty "defold.unpack.path")
        engine-armv7 (format "%s/armv7-ios/bin/dmengine" unpack)
        engine-arm64 (format "%s/arm64-ios/bin/dmengine" unpack)
        lipo (format "%s/bin/lipo" unpack)
        engine (File/createTempFile "dmengine" "")]
    ;; TODO: Check that engine-* exists!!!
    (system/exec [lipo "-create" engine-armv7 engine-arm64 "-output" (.getAbsolutePath engine)] {})
    (prn "!!!!!!!!!!!")
    (prn engine-armv7)
    (prn engine-arm64)
    (prn (.getAbsolutePath engine))
    (prn "!!!!!!!!!!!")

    engine))

(defn- sign-ios-app [project identity profile]
  (let [settings (g/node-value project :settings)
        w (get settings ["display" "width"] 1)
        h (get settings ["display" "height"] 1)
        orient (if (> w h)
                 {"UISupportedInterfaceOrientations"      "UIInterfaceOrientationLandscapeRight"
                  "UISupportedInterfaceOrientations~ipad" "UIInterfaceOrientationLandscapeRight"}
                 {"UISupportedInterfaceOrientations"      "UIInterfaceOrientationPortrait"
                  "UISupportedInterfaceOrientations~ipad" "UIInterfaceOrientationPortrait"})
        props {"CFBundleDisplayName" (get settings ["project" "title"] "Unnamed")
               "CFBundleExecutable" "dmengine"
               "CFBundleIdentifier" (get settings ["ios" "bundle_identifier"] "dmengine")}
        props (merge props orient)
        engine ^File (create-fat-ios-engine)
        unpack (System/getProperty "defold.unpack.path")
        codesign-alloc (format "%s/bin/codesign_allocate" unpack)]
    (prn "####" identity)
    (let [ipa (.sign (Signer.) identity profile (.getAbsolutePath engine) codesign-alloc props)]
      (.delete engine)
      ipa)))

(handler/defhandler ::sign :dialog
  (enabled? [controls] (and (ui/selection (:identities controls))
                            (.exists (io/file (ui/text (:provisioning-profile controls))))))
  (run [^Stage stage controls project]
    (let [unpack (System/getProperty "defold.unpack.path")
          engine-armv7 (format "%s/armv7-ios/bin/dmengine" unpack)
          engine-arm64 (format "%s/arm64-ios/bin/dmengine" unpack)
          lipo (format "%s/bin/lipo" unpack)
          engine (File/createTempFile "dmengine" "")]
      (system/exec [lipo "-create" engine-armv7 engine-arm64 "-output" (.getAbsolutePath engine)] {})
      (prn "->" engine-armv7 engine-arm64 engine))

    (let [identity (get-in (ui/selection (:identities controls)) [0 0])
          profile (ui/text (:provisioning-profile controls))
          ipa (sign-ios-app project identity profile)]
      (prn "IPA" ipa))

    (prn (get-in (ui/selection (:identities controls)) [0 0])
         (ui/text (:provisioning-profile controls))
         (format "%s/arm64-ios/bin/dmengine" (System/getProperty "defold.unpack.path")))
    (.close stage)))

(handler/defhandler ::select-provisioning-profile :dialog
  (enabled? [] true)
  (run [stage controls]
    (prn "DO SELECT")
    (let [f (ui/choose-file "Selection Provisioning Profile" "Provisioning Profile" ["*.mobileprovision"])]
      (ui/text! (:provisioning-profile controls) f))))

(defn- find-identities []
  (let [re #"\s+\d+\)\s+([0-9A-Z]+)\s+\"(.*?)\""
        lines (.split ^String (second (system/exec ["security" "find-identity" "-v" "-p" "codesigning"] {})) "\n" )]
    (->> lines
         (map #(first (re-seq re %)))
         (remove nil?)
         (map (fn [[_ id name]] [id name])))))

(defn make-sign-dialog [project]
  (let [root ^Parent (ui/load-fxml "sign-dialog.fxml")
        stage (ui/make-stage)
        scene (Scene. root)
        controls (ui/collect-controls root ["identities" "sign" "provisioning-profile" "provisioning-profile-button"])]

    (ui/context! root :dialog {:controls controls :stage stage :project project} nil)
    (ui/cell-factory! (:identities controls) (fn [i] {:text (second i)}))

    (ui/bind-action! (:provisioning-profile-button controls) ::select-provisioning-profile)
    (ui/bind-action! (:sign controls) ::sign)

    (ui/items! (:identities controls) (find-identities))
    (dialogs/observe-focus stage)
    (.initOwner stage (ui/main-stage))
    (ui/title! stage "Sign iOS Application")
    (.initModality stage Modality/NONE)
    (.setScene stage scene)
    (ui/show! stage)
    stage))


#_(with-open [ins (io/input-stream (io/resource "diff.fxml"))]
  (io/copy ins (io/file "/tmp/foobar123")))

#_(ui/run-later (make-sign-dialog nil))
