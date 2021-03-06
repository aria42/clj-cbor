(ns clj-cbor.header
  "Functions for reading and writing CBOR headers."
  (:require
    [clj-cbor.error :as error]
    [clojure.string :as str])
  (:import
    (java.io
      DataInputStream
      DataOutputStream)))


(def major-types
  "Vector of major type keywords, indexed by the three-bit values 0-7. (§2.1)"
  [:unsigned-integer
   :negative-integer
   :byte-string
   :text-string
   :data-array
   :data-map
   :tagged-value
   :simple-value])


(def ^:private major-type-codes
  "Map of major type keywords to code values."
  (zipmap major-types (range)))



;; ## Encoding Functions

(defn write-leader
  "Writes a header byte for the given major-type and additional info numbers.
  Returns the number of bytes written."
  [^DataOutputStream out mtype info]
  (let [header (-> (bit-and (major-type-codes mtype) 0x07)
                   (bit-shift-left 5)
                   (bit-or (bit-and (long info) 0x1F)))]
    (.writeByte out header))
  1)


(defn write
  "Writes a header byte for the given major-type, plus extra bytes to encode
  the given integer code. Always writes the smallest possible representation.
  Returns the number of bytes written."
  [^DataOutputStream out mtype i]
  (cond
    (neg? i)
      (error/*handler*
        ::negative-info-code
        (str "Cannot write negative integer code: " i)
        {:code i})
    (<= i 23)
      (do (write-leader out mtype i)
          1)
    (<= i 0xFF)
      (do (write-leader out mtype 24)
          (.writeByte out i)
          2)
    (<= i 0xFFFF)
      (do (write-leader out mtype 25)
          (.writeShort out i)
          3)
    (<= i Integer/MAX_VALUE)
      (do (write-leader out mtype 26)
          (.writeInt out i)
          5)
    (<= i 0xFFFFFFFF)
      (do (write-leader out mtype 26)
          (.writeInt out (+ Integer/MIN_VALUE (- (dec i) Integer/MAX_VALUE)))
          5)
    (<= i Long/MAX_VALUE)
      (do (write-leader out mtype 27)
          (.writeLong out i)
          9)
    (<= i (* -2N Long/MIN_VALUE))
      (do (write-leader out mtype 27)
          (.writeLong out (+ Long/MIN_VALUE (- (dec i) Long/MAX_VALUE)))
          9)
    :else
      (error/*handler*
        ::overflow-info-code
        (str "Cannot write integer code requiring 9 bytes of space: " i)
        {:code i})))



;; ## Decoding Functions

(defn decode
  "Determines the major type keyword and additional information encoded by the
  header byte. §2.1"
  [header]
  [(-> header
       (bit-and 0xE0)
       (bit-shift-right 5)
       (bit-and 0x07)
       (major-types))
   (bit-and header 0x1F)])


(defn- read-unsigned-long
  "Reads an unsigned long value from the input stream. If the value overflows
  into the negative, it is promoted to a bigint."
  [^DataInputStream input]
  (let [value (.readLong input)]
    (if (neg? value)
      ; Overflow, promote to BigInt.
      (->>
        [(bit-and 0xFF (bit-shift-right value  0))
         (bit-and 0xFF (bit-shift-right value  8))
         (bit-and 0xFF (bit-shift-right value 16))
         (bit-and 0xFF (bit-shift-right value 24))
         (bit-and 0xFF (bit-shift-right value 32))
         (bit-and 0xFF (bit-shift-right value 40))
         (bit-and 0xFF (bit-shift-right value 48))
         (bit-and 0xFF (bit-shift-right value 56))]
        (byte-array)
        (java.math.BigInteger. 1)
        (bigint))
      ; Value fits in a long, return directly.
      value)))


(defn read-code
  "Reads a size value from the initial bytes of the input stream. Returns
  either a number, the keyword `:indefinite`, or calls the error handler on
  reserved info codes."
  [^DataInputStream input ^long info]
  (if (< info 24)
    ; Info codes less than 24 directly represent the number.
    info
    ; Otherwise, signify the number of bytes following.
    (case info
      24 (long (.readUnsignedByte input))
      25 (long (.readUnsignedShort input))
      26 (long (bit-and (.readInt input) 0xFFFFFFFF))
      27 (read-unsigned-long input)
      (28 29 30)
        (error/*handler*
          ::reserved-info-code
          (format "Additional information int code %d is reserved."
                  info)
          {:info info})
      31 :indefinite)))
