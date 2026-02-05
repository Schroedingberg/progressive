(ns rp.util
  "Deep merge utilities for combining event data with plan templates.")

(defn- ordered-merge-with
  "Like merge-with but preserves key order from the second map."
  [f m1 m2]
  (reduce (fn [acc k]
            (let [v1 (get m1 k)
                  v2 (get m2 k)]
              (assoc acc k (cond
                             (and v1 v2) (f v1 v2)
                             v1 v1
                             :else v2))))
          (array-map)
          (distinct (concat (keys m2) (keys m1)))))

(defn deep-merge-with
  "Recursively merge maps, applying f only at leaf nodes.
  Preserves key order from the second (plan) map."
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (reduce (partial ordered-merge-with m) maps)
       (apply f maps)))
   maps))
