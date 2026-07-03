(ns kotoba.cofog-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.cofog :as cofog]))

(deftest registry-loads
  (let [reg (cofog/registry)]
    (is (= :kotoba/cofog (:kotoba.registry/id reg)))
    (is (= 79 (count (cofog/functions reg))))))

(deftest curated-groups-resolve
  (doseq [code ["03.2" "04.5" "05.1" "06.3" "07.4"]]
    (is (:business-id (cofog/get-cofog code)))
    (is (seq (cofog/required-technologies code)))
    (is (seq (:technology-stack (cofog/execution-plan code))))))

(deftest readiness-reports-missing-tech
  (let [r (cofog/readiness "04.5" #{:telemetry :forms})]
    (is (false? (:ready? r)))
    (is (contains? (:missing r) :audit-ledger)))
  ;; :robotics is required on every dispatchable group entry (robotics-premise
  ;; design, mirrors kotoba-lang/occupation and kotoba-lang/industry), so it
  ;; must be in the available set to read ready.
  (is (:ready? (cofog/readiness "05.1" #{:robotics :telemetry :optimization :bpmn :audit-ledger}))))

(deftest maturity-tier
  (testing "published blueprint repos are :blueprint"
    (is (= :blueprint (cofog/maturity "03.2")))
    (is (= :blueprint (cofog/maturity "04.5")))
    (is (= :blueprint (cofog/maturity "05.1")))
    (is (= :blueprint (cofog/maturity "06.3")))
    (is (= :blueprint (cofog/maturity "07.4"))))
  (testing "a registry-only group entry is :spec"
    (is (= :spec (cofog/maturity "01.1")))
    (is (= :spec (cofog/maturity "09.4"))))
  (testing "a division (parent node) is :spec -- not independently blueprint-eligible"
    (is (= :spec (cofog/maturity "01")))
    (is (= :spec (cofog/maturity "10"))))
  (testing "maturity-summary counts tiers"
    (let [m (cofog/maturity-summary)]
      (is (= (:total m) (+ (:spec m) (:blueprint m) (:implemented m))))
      (is (= 79 (:total m)))
      (is (= 5 (:blueprint m)))
      (is (= 0 (:implemented m))))))

(deftest maturity-roadmap-next-step
  (is (= :implemented (:next-step (cofog/maturity-roadmap "03.2"))))
  (is (= :blueprint (:next-step (cofog/maturity-roadmap "01.1")))))

(deftest all-groups-have-a-parent-division
  (doseq [{:keys [level parent]} (cofog/functions)]
    (when (= level :group)
      (is (some? parent))
      (is (some? (cofog/get-cofog parent)) (str parent " must resolve to a division entry")))))
