(ns kotoba.cofog
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [kotoba.technology :as technology]))

(def registry-resource "kotoba/cofog/registry.edn")

;; registry.edn is stored as Datomic/Datascript tx-data (a single-entity
;; vector, see scripts/edn-datomize.bb `wrap-generic`) rather than a raw map,
;; so it is directly transactable/queryable. Keys that already carried their
;; own namespace (e.g. :kotoba.registry/id) were left untouched; only the
;; genuinely bare :cofog key was promoted under this ns. `registry`
;; reconstitutes the original bare-keyed map (with :cofog un-blobbed back
;; into its live vector-of-maps) so every downstream fn below keeps working
;; against the exact pre-datomize shape.
(def ^:private wrap-ns "kotoba.cofog")

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- reconstitute-entity [tx-data]
  (into {}
        (map (fn [[k v]]
               (let [bare? (= (namespace k) wrap-ns)]
                 [(if bare? (keyword (name k)) k) (unblob v)])))
        (dissoc (first tx-data) :db/id)))

(defn registry []
  (reconstitute-entity (edn/read-string (slurp (io/resource registry-resource)))))

(defn functions
  ([] (:cofog (registry)))
  ([reg] (:cofog reg)))

(defn by-code
  ([] (by-code (registry)))
  ([reg] (into {} (map (juxt :code identity) (functions reg)))))

(defn get-cofog
  ([code] (get-cofog (registry) code))
  ([reg code] (get (by-code reg) (str code))))

(defn required-technologies
  ([code] (required-technologies (registry) code))
  ([reg code] (:required-technologies (get-cofog reg code))))

(defn optional-technologies
  ([code] (optional-technologies (registry) code))
  ([reg code] (:optional-technologies (get-cofog reg code))))

(defn technology-stack
  "Resolve the required technology records for a COFOG group."
  ([code] (technology-stack (registry) code))
  ([reg code]
   (technology/stack (required-technologies reg code))))

(defn readiness
  "Return an execution-readiness summary for a COFOG code and available technology IDs."
  [code available-tech-ids]
  (let [cofog-fn (get-cofog code)
        required (set (:required-technologies cofog-fn))
        available (set available-tech-ids)
        missing (set/difference required available)]
    {:cofog (str code)
     :business-id (:business-id cofog-fn)
     :ready? (empty? missing)
     :required required
     :available available
     :missing missing
     :operating-states (:operating-states cofog-fn)}))

(defn execution-plan
  "Data contract cloud-itonami-cofog can expose in business state."
  [code]
  (let [cofog-fn (get-cofog code)
        stack (technology-stack code)]
    {:cofog (str code)
     :business-id (:business-id cofog-fn)
     :function (:name cofog-fn)
     :maturity (:maturity cofog-fn)
     :required-technologies (:required-technologies cofog-fn)
     :optional-technologies (:optional-technologies cofog-fn)
     :operating-states (:operating-states cofog-fn)
     :ui-ready? (some :ui? stack)
     :export-ready? (some :export? stack)
     :technology-stack (mapv #(select-keys % [:id :name :layer :capabilities :repos :contracts :ui? :export?])
                             stack)}))

(defn maturity
  "Return the maturity level of a COFOG entry: :spec (registry only),
  :blueprint (blueprint repo published), or :implemented (source actor exists).
  Defaults to :spec when unset."
  [code]
  (let [cofog-fn (get-cofog code)]
    (or (:maturity cofog-fn)
        (cond
          (:implemented? cofog-fn) :implemented
          (:repo cofog-fn)         :blueprint
          :else               :spec))))

(defn maturity-summary
  "Aggregate maturity counts across all COFOG entries (divisions + groups)."
  []
  (let [fns (functions)]
    {:total       (count fns)
     :spec        (count (filter #(= :spec (maturity (:code %))) fns))
     :blueprint   (count (filter #(= :blueprint (maturity (:code %))) fns))
     :implemented (count (filter #(= :implemented (maturity (:code %))) fns))}))

(defn maturity-roadmap
  "Return the next maturity step for a COFOG entry: :spec->:blueprint->:implemented,
  with the action required to advance and whether a capability lib with UI/export
  already backs it."
  [code]
  (let [cofog-fn (get-cofog code)
        level (maturity code)
        stack (technology-stack code)
        ui? (some :ui? stack)
        export? (some :export? stack)
        has-repo (boolean (:repo cofog-fn))]
    {:cofog (str code)
     :maturity level
     :next-step (condp = level
                  :spec        :blueprint
                  :blueprint   :implemented
                  :implemented nil)
     :next-action (condp = level
                    :spec        "publish a blueprint repo (scaffold + blueprint.edn + docs)"
                    :blueprint   "implement the actor (source + tests)"
                    :implemented "at maturity ceiling")
     :ui-ready? ui?
     :export-ready? export?
     :has-repo has-repo}))
