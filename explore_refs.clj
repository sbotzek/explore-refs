(ns botzek.explore-refs
  (:require [clojure.core.async :as async]))

;; sync blocks seem to reset thread interrupted status
(defn interrupt-thread
  []
  (println "Testing interrupting the thread outside dosync")
  (let [p1 (ref 1)
        f (fn []
            (.interrupt (Thread/currentThread))
            (dosync (alter p1 inc))
            (println "  interrupted?" (Thread/interrupted)))
        thread (Thread. f)]
    (.start thread)))
(interrupt-thread)

;; interrupting the thread in sync causes it to hit the retry limit
(defn interrupt-thread-in-dosync
  []
  (println "Testing interrupting the thread inside dosync")
  (let [p1 (ref 1)]
    (try
      (dosync
       (alter p1 inc)
       (.interrupt (Thread/currentThread)))
      (catch RuntimeException e
        (println "  exception:" (.getMessage e))))
    (println "  interrupted?" (Thread/interrupted))))
(interrupt-thread-in-dosync)


;; if a deref can't find a history for the point in time, it seems to retry the transaction
(defn deref-no-history-for-time
  []
  (let [player1 (ref 1)
        player2 (ref 2)
        stop? (atom false)]
  (println "Testing when transactional derefs have no history for the point in time")
    (.start (Thread. (fn []
                       (println "  modify loop starting")
                       (loop [c 0]
                         (dosync (alter player1 inc)
                                 (alter player1 inc)
                                 (alter player2 inc)
                                 (alter player2 inc))
                         (when-not @stop?
                           (recur (inc c))))
                       (println "  modify loop stopping"))))
    (let [dosync-global-count (atom 0)]
      (try
        (loop [c 1]
            (dosync
             (Thread/sleep 1)
             (swap! dosync-global-count inc)
             (let [p2 @player2]
               (let [p1 @player1]
                 (when (not= (inc p1) p2)
                   (throw (IllegalStateException. (str "p1: " p1 ", p2: " p2)))))))
          (when (<= c 10)
            (recur (inc c))))
        (println "  total dosyncs:" @dosync-global-count)
        (catch RuntimeException e
          (println "  exception:" (.getMessage e) "- total dosyncs:" @dosync-global-count))))
    (reset! stop? true)))
(deref-no-history-for-time)
