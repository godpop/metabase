(ns metabase.public-settings
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase
             [config :as config]
             [types :as types]
             [util :as u]]
            [metabase.driver.util :as driver.u]
            [metabase.models
             [common :as common]
             [setting :as setting :refer [defsetting]]]
            [metabase.plugins.classloader :as classloader]
            [metabase.public-settings.metastore :as metastore]
            [metabase.util
             [i18n :refer [available-locales-with-names deferred-tru set-locale trs tru]]
             [password :as password]]
            [toucan.db :as db])
  (:import [java.util TimeZone UUID]))

(defsetting check-for-updates
  (deferred-tru "Identify when new versions of Metabase are available.")
  :type    :boolean
  :default true)

(defsetting version-info
  (deferred-tru "Information about available versions of Metabase.")
  :type    :json
  :default {})

(defsetting site-name
  (deferred-tru "The name used for this instance of Metabase.")
  :default "Metabase")

(defsetting site-uuid
  ;; Don't i18n this docstring because it's not user-facing! :)
  "Unique identifier used for this instance of Metabase. This is set once and only once the first time it is fetched via
  its magic getter. Nice!"
  :internal? true
  :setter    (fn [& _]
               (throw (UnsupportedOperationException. "site-uuid is automatically generated. Don't try to change it!")))
  ;; magic getter will either fetch value from DB, or if no value exists, set the value to a random UUID.
  :getter    (fn []
               (or (setting/get-string :site-uuid)
                   (let [value (str (UUID/randomUUID))]
                     (setting/set-string! :site-uuid value)
                     value))))

(defn- normalize-site-url [^String s]
  (let [ ;; remove trailing slashes
        s (str/replace s #"/$" "")
        ;; add protocol if missing
        s (if (str/starts-with? s "http")
            s
            (str "http://" s))]
    ;; check that the URL is valid
    (assert (u/url? s)
      (tru "Invalid site URL: {0}" s))
    s))

;; This value is *guaranteed* to never have a trailing slash :D
;; It will also prepend `http://` to the URL if there's not protocol when it comes in
(defsetting site-url
  (deferred-tru "The base URL of this Metabase instance, e.g. \"http://metabase.my-company.com\".")
  :getter (fn []
            (try
              (some-> (setting/get-string :site-url) normalize-site-url)
              (catch AssertionError e
                (log/error e (trs "site-url is invalid; returning nil for now. Will be reset on next request.")))))
  :setter (fn [new-value]
            (setting/set-string! :site-url (some-> new-value normalize-site-url))))

(defsetting site-locale
  (str  (deferred-tru "The default language for this Metabase instance.")
        " "
        (deferred-tru "This only applies to emails, Pulses, etc. Users'' browsers will specify the language used in the user interface."))
  :type    :string
  :setter  (fn [new-value]
             (setting/set-string! :site-locale new-value)
             (set-locale new-value))
  :default "en")

(defsetting admin-email
  (deferred-tru "The email address users should be referred to if they encounter a problem."))

(defsetting anon-tracking-enabled
  (deferred-tru "Enable the collection of anonymous usage data in order to help Metabase improve.")
  :type   :boolean
  :default true)

(defsetting map-tile-server-url
  (deferred-tru "The map tile server URL template used in map visualizations, for example from OpenStreetMaps or MapBox.")
  :default "http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png")

(defsetting enable-public-sharing
  (deferred-tru "Enable admins to create publicly viewable links (and embeddable iframes) for Questions and Dashboards?")
  :type    :boolean
  :default false)

(defsetting enable-embedding
  (deferred-tru "Allow admins to securely embed questions and dashboards within other applications?")
  :type    :boolean
  :default false)

(defsetting enable-nested-queries
  (deferred-tru "Allow using a saved question as the source for other queries?")
  :type    :boolean
  :default true)


(defsetting enable-query-caching
  (deferred-tru "Enabling caching will save the results of queries that take a long time to run.")
  :type    :boolean
  :default false)

(def ^:private ^:const global-max-caching-kb
  "Although depending on the database, we can support much larger cached values (1GB for PG, 2GB for H2 and 4GB for
  MySQL) we are not curretly setup to deal with data of that size. The datatypes we are using will hold this data in
  memory and will not truly be streaming. This is a global max in order to prevent our users from setting the caching
  value so high it becomes a performance issue. The value below represents 200MB"
  (* 200 1024))

(defsetting query-caching-max-kb
  (deferred-tru "The maximum size of the cache, per saved question, in kilobytes:")
  ;; (This size is a measurement of the length of *uncompressed* serialized result *rows*. The actual size of
  ;; the results as stored will vary somewhat, since this measurement doesn't include metadata returned with the
  ;; results, and doesn't consider whether the results are compressed, as the `:db` backend does.)
  :type    :integer
  :default 1000
  :setter  (fn [new-value]
             (when (and new-value
                        (> (cond-> new-value
                             (string? new-value) Integer/parseInt)
                           global-max-caching-kb))
               (throw (IllegalArgumentException.
                       (str
                        (deferred-tru "Failed setting `query-caching-max-kb` to {0}." new-value)
                        (deferred-tru "Values greater than {1} are not allowed." global-max-caching-kb)))))
             (setting/set-integer! :query-caching-max-kb new-value)))

(defsetting query-caching-max-ttl
  (deferred-tru "The absolute maximum time to keep any cached query results, in seconds.")
  :type    :integer
  :default (* 60 60 24 100)) ; 100 days

(defsetting query-caching-min-ttl
  (deferred-tru "Metabase will cache all saved questions with an average query execution time longer than this many seconds:")
  :type    :integer
  :default 60)

(defsetting query-caching-ttl-ratio
  (str (deferred-tru "To determine how long each saved question''s cached result should stick around, we take the query''s average execution time and multiply that by whatever you input here.")
       (deferred-tru "So if a query takes on average 2 minutes to run, and you input 10 for your multiplier, its cache entry will persist for 20 minutes."))
  :type    :integer
  :default 10)

(defsetting breakout-bins-num
  (deferred-tru "When using the default binning strategy and a number of bins is not provided, this number will be used as the default.")
  :type :integer
  :default 8)

(defsetting breakout-bin-width
  (deferred-tru "When using the default binning strategy for a field of type Coordinate (such as Latitude and Longitude), this number will be used as the default bin width (in degrees).")
  :type :double
  :default 10.0)

(defsetting custom-formatting
  (deferred-tru "Object keyed by type, containing formatting settings")
  :type    :json
  :default {})

(defsetting enable-xrays
  (deferred-tru "Allow users to explore data using X-rays")
  :type    :boolean
  :default true)

(defsetting source-address-header
  (deferred-tru "Identify the source of HTTP requests by this header's value, instead of its remote address.")
  :getter (fn [] (some-> (setting/get-string :source-address-header)
                         u/lower-case-en)))

(defn remove-public-uuid-if-public-sharing-is-disabled
  "If public sharing is *disabled* and OBJECT has a `:public_uuid`, remove it so people don't try to use it (since it
   won't work). Intended for use as part of a `post-select` implementation for Cards and Dashboards."
  [object]
  (if (and (:public_uuid object)
           (not (enable-public-sharing)))
    (assoc object :public_uuid nil)
    object))


(defn- short-timezone-name*
  "Get a short display name (e.g. `PST`) for `report-timezone`, or fall back to the System default if it's not set."
  [^String timezone-name]
  (let [^TimeZone timezone (or (when (seq timezone-name)
                                 (TimeZone/getTimeZone timezone-name))
                               (TimeZone/getDefault))]
    (.getDisplayName timezone (.inDaylightTime timezone (java.util.Date.)) TimeZone/SHORT)))

(def ^:private short-timezone-name (memoize short-timezone-name*))

;; TODO - it seems like it would be a nice performance win to cache this a little bit
(defn public-settings
  "Return a simple map of key/value pairs which represent the public settings (`MetabaseBootstrap`) for the front-end
   application."
  []
  {:admin_email           (admin-email)
   :anon_tracking_enabled (anon-tracking-enabled)
   :custom_geojson        (setting/get :custom-geojson)
   :custom_formatting     (setting/get :custom-formatting)
   :email_configured      (do (classloader/require 'metabase.email)
                              ((resolve 'metabase.email/email-configured?)))
   :embedding             (enable-embedding)
   :enable_query_caching  (enable-query-caching)
   :enable_nested_queries (enable-nested-queries)
   :enable_xrays          (enable-xrays)
   :engines               (driver.u/available-drivers-info)
   :ga_code               "UA-60817802-1"
   :google_auth_client_id (setting/get :google-auth-client-id)
   :has_sample_dataset    (db/exists? 'Database, :is_sample true)
   :hide_embed_branding   (metastore/hide-embed-branding?)
   :ldap_configured       (do (classloader/require 'metabase.integrations.ldap)
                              ((resolve 'metabase.integrations.ldap/ldap-configured?)))
   :available_locales     (available-locales-with-names)
   :map_tile_server_url   (map-tile-server-url)
   :metastore_url         metastore/store-url
   :password_complexity   password/active-password-complexity
   :premium_token         (metastore/premium-embedding-token)
   :public_sharing        (enable-public-sharing)
   :report_timezone       (setting/get :report-timezone)
   :setup_token           (do
                            (classloader/require 'metabase.setup)
                            ((resolve 'metabase.setup/token-value)))
   :site_name             (site-name)
   :site_url              (site-url)
   :timezone_short        (short-timezone-name (setting/get :report-timezone))
   :timezones             common/timezones
   :types                 (types/types->parents :type/*)
   :entities              (types/types->parents :entity/*)
   :version               config/mb-version-info})
