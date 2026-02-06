;; # Romance Progression PWA
;; 
;; A local-first workout tracking app using event sourcing.

^{:nextjournal.clerk/visibility {:code :hide}}
(ns index
  {:nextjournal.clerk/toc true}
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]))

;; ## Overview
;;
;; This is a ClojureScript PWA for workout tracking. Key features:
;;
;; - **Offline-first**: All data stays in browser localStorage
;; - **Event sourcing**: Immutable event log, reconstructed state
;; - **Feedback-driven progression**: Adjusts weights based on soreness, workload, joint pain
;;
;; ## Quick Start
;;
;; ```bash
;; # Install dependencies
;; npm install
;;
;; # Start development server (http://localhost:3000)
;; npm run dev
;;
;; # Run tests
;; npm run test
;; ```

;; ## Architecture at a Glance
;;
;; ```
;; User action → Event created → DataScript transact → localStorage persist
;;                                       ↓
;; Plan template + Events → state/view-progress-in-plan → UI render
;; ```

;; ### Namespace Map
;;
;; | Namespace | Purpose |
;; |-----------|---------|
;; | `rp.core` | App entry point, service worker registration |
;; | `rp.db` | DataScript schema, transactions, queries |
;; | `rp.state` | Event log → plan structure transformation |
;; | `rp.plan` | Plan templates and expansion |
;; | `rp.progression` | Weight/rep adjustments based on feedback |
;; | `rp.storage` | localStorage persistence |
;; | `rp.ui` | Reagent components |
;; | `rp.util` | Deep merge utilities |

;; ## Pages
;;
;; - [Architecture](./architecture) - Event sourcing deep dive
;; - [Events](./events) - Event type reference

;; ---
;; 
;; ## Source Files

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div
  [:h4 "Core Namespaces"]
  [:ul
   (for [f ["src/rp/core.cljs"
            "src/rp/db.cljs"
            "src/rp/state.cljs"
            "src/rp/plan.cljs"
            "src/rp/progression.cljs"
            "src/rp/storage.cljs"]]
     [:li [:code f]])]])
