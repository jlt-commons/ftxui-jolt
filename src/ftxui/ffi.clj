(ns ftxui.ffi
  "Raw bindings to the C shim in native/ftxui_jolt.cpp — a thin defcfn layer
  with no logic. See native/ftxui_jolt.h for the ABI each binding mirrors.

  FTXUI is C++ (std::function, shared_ptr, std::string), which jolt.ffi cannot
  bind directly, so the shim flattens it to ints, strings and pointers:

  - Elements (the DOM) are int handles into a per-frame arena on the C side;
    they are only valid within the frame that produced them.
  - Components (the stateful, focusable widgets) live in slots keyed by an
    int id the jolt side chooses. Their state lives in the slot: setters push
    into it, getters read back what the widget last wrote.
  - The app loop and the headless entry points call back into jolt through
    three function pointers registered with set-callbacks; ftxui.render
    creates them as :collect-safe foreign-callables, because the loop runs
    inside the :blocking app-loop call."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn version "fj_version" [] :string)
(ffi/defcfn set-callbacks "fj_set_callbacks" [:pointer :pointer :pointer] :void)

;; --- fj_event, the struct handed to the event callback -----------------------
(def event-layout
  (ffi/layout [:struct [[:type :int32] [:key :int32] [:button :int32] [:motion :int32]
                        [:shift :int32] [:meta :int32] [:control :int32]
                        [:x :int32] [:y :int32] [:input [:array :uint8 32]]]]))

(def ^:private input-offset (ffi/field-offset event-layout :input))

(defn read-event
  "Read a fj_event* into a map of its raw fields."
  [ptr]
  {:type    (ffi/read-field ptr event-layout :type)
   :key     (ffi/read-field ptr event-layout :key)
   :button  (ffi/read-field ptr event-layout :button)
   :motion  (ffi/read-field ptr event-layout :motion)
   :shift   (ffi/read-field ptr event-layout :shift)
   :meta    (ffi/read-field ptr event-layout :meta)
   :control (ffi/read-field ptr event-layout :control)
   :x       (ffi/read-field ptr event-layout :x)
   :y       (ffi/read-field ptr event-layout :y)
   :input   (ffi/ptr->string (+ ptr input-offset) 32)})

;; --- int arrays for the child-list calls --------------------------------------
(defn- with-ints
  "Marshal `xs` (ints) into a temporary C int array and call (f ptr n)."
  [xs f]
  (let [xs (vec xs) n (count xs)]
    (ffi/with-alloc [p (max 4 (* 4 n))]
      (dotimes [i n] (ffi/write p :int (int (nth xs i)) (* 4 i)))
      (f p n))))

;; --- elements -----------------------------------------------------------------
(ffi/defcfn text "fj_text" [:string] :int)
(ffi/defcfn vtext "fj_vtext" [:string] :int)
(ffi/defcfn paragraph "fj_paragraph" [:string :int] :int)
(ffi/defcfn separator "fj_separator" [:int] :int)
(ffi/defcfn separator-char "fj_separator_char" [:string] :int)
(ffi/defcfn gauge "fj_gauge" [:double :int] :int)
(ffi/defcfn spinner "fj_spinner" [:int :int] :int)
(ffi/defcfn empty "fj_empty" [] :int)
(ffi/defcfn filler "fj_filler" [] :int)

(ffi/defcfn ^:private hbox* "fj_hbox" [:pointer :int] :int)
(ffi/defcfn ^:private vbox* "fj_vbox" [:pointer :int] :int)
(ffi/defcfn ^:private dbox* "fj_dbox" [:pointer :int] :int)
(ffi/defcfn ^:private hflow* "fj_hflow" [:pointer :int] :int)
(ffi/defcfn ^:private vflow* "fj_vflow" [:pointer :int] :int)
(ffi/defcfn ^:private flexbox* "fj_flexbox"
  [:pointer :int :int :int :int :int :int :int :int] :int)
(ffi/defcfn ^:private gridbox* "fj_gridbox" [:pointer :int :int] :int)
(ffi/defcfn ^:private table* "fj_table" [:pointer :int :int :int :int :int] :int)

(defn hbox [children] (with-ints children hbox*))
(defn vbox [children] (with-ints children vbox*))
(defn dbox [children] (with-ints children dbox*))
(defn hflow [children] (with-ints children hflow*))
(defn vflow [children] (with-ints children vflow*))
(defn flexbox [children direction wrap justify align-items align-content gap-x gap-y]
  (with-ints children
    (fn [p n] (flexbox* p n direction wrap justify align-items align-content gap-x gap-y))))
(defn gridbox
  "cells: row-major handles, rows * cols long (0 = empty cell)."
  [cells cols rows]
  (with-ints cells (fn [p _] (gridbox* p cols rows))))
(defn table [cells cols rows border-style header separators]
  (with-ints cells (fn [p _] (table* p cols rows border-style header separators))))

(ffi/defcfn border "fj_border" [:int :int :int] :int)
(ffi/defcfn window "fj_window" [:int :int :int] :int)
(ffi/defcfn style "fj_style" [:int :int] :int)
(ffi/defcfn color "fj_color" [:int :int] :int)
(ffi/defcfn bgcolor "fj_bgcolor" [:int :int] :int)
(ffi/defcfn flex "fj_flex" [:int :int] :int)
(ffi/defcfn flex-factor "fj_flex_factor" [:int :int :int :int] :int)
(ffi/defcfn size "fj_size" [:int :int :int :int] :int)
(ffi/defcfn frame "fj_frame" [:int :int] :int)
(ffi/defcfn focus "fj_focus" [:int :int] :int)
(ffi/defcfn align "fj_align" [:int :int] :int)
(ffi/defcfn scroll-indicator "fj_scroll_indicator" [:int :int] :int)
(ffi/defcfn clear-under "fj_clear_under" [:int] :int)
(ffi/defcfn hyperlink "fj_hyperlink" [:int :string] :int)
(ffi/defcfn automerge "fj_automerge" [:int] :int)

(ffi/defcfn render-text "fj_render_text" [:int :int :int] :string)
(ffi/defcfn render-ansi "fj_render_ansi" [:int :int :int] :string)
(ffi/defcfn arena-size "fj_arena_size" [] :int)

;; --- components ---------------------------------------------------------------
(ffi/defcfn button-new "fj_button_new" [:int :int] :void)
(ffi/defcfn input-new "fj_input_new" [:int] :void)
(ffi/defcfn checkbox-new "fj_checkbox_new" [:int] :void)
(ffi/defcfn menu-new "fj_menu_new" [:int :int :int] :void)
(ffi/defcfn radiobox-new "fj_radiobox_new" [:int] :void)
(ffi/defcfn dropdown-new "fj_dropdown_new" [:int] :void)
(ffi/defcfn slider-new "fj_slider_new" [:int :int :int :int] :void)
(ffi/defcfn container-new "fj_container_new" [:int :int] :void)
(ffi/defcfn node-new "fj_node_new" [:int :int] :void)
(ffi/defcfn maybe-new "fj_maybe_new" [:int :int] :void)
(ffi/defcfn modal-new "fj_modal_new" [:int :int :int] :void)
(ffi/defcfn collapsible-new "fj_collapsible_new" [:int :int] :void)

(ffi/defcfn component-exists "fj_component_exists" [:int] :int)
(ffi/defcfn component-free "fj_component_free" [:int] :void)
(ffi/defcfn ^:private set-children* "fj_set_children" [:int :pointer :int] :void)
(defn set-children [id children] (with-ints children (fn [p n] (set-children* id p n))))
(ffi/defcfn child-count "fj_child_count" [:int] :int)
(ffi/defcfn take-focus "fj_take_focus" [:int] :void)
(ffi/defcfn focused "fj_focused" [:int] :int)
(ffi/defcfn active "fj_active" [:int] :int)
(ffi/defcfn focusable "fj_focusable" [:int] :int)
;; component-render / component-render-text / send-* may re-enter jolt (a node
;; renders through the render callback), so they are :blocking: the calling
;; thread is deactivated for the call and the :collect-safe callback re-activates
;; it on the way in, exactly as it does under the app loop.
(ffi/defcfn component-render "fj_component_render" [:int] :int :blocking)
(ffi/defcfn component-render-text "fj_component_render_text" [:int :int :int] :string :blocking)

(ffi/defcfn set-label "fj_set_label" [:int :string] :void)
(ffi/defcfn set-content "fj_set_content" [:int :string] :void)
(ffi/defcfn get-content "fj_get_content" [:int] :string)
(ffi/defcfn set-placeholder "fj_set_placeholder" [:int :string] :void)
(ffi/defcfn entries-clear "fj_entries_clear" [:int] :void)
(ffi/defcfn entries-add "fj_entries_add" [:int :string] :void)
(ffi/defcfn entries-count "fj_entries_count" [:int] :int)
(ffi/defcfn set-selected "fj_set_selected" [:int :int] :void)
(ffi/defcfn get-selected "fj_get_selected" [:int] :int)
(ffi/defcfn set-checked "fj_set_checked" [:int :int] :void)
(ffi/defcfn get-checked "fj_get_checked" [:int] :int)
(ffi/defcfn set-show "fj_set_show" [:int :int] :void)
(ffi/defcfn get-show "fj_get_show" [:int] :int)
(ffi/defcfn set-value "fj_set_value" [:int :int] :void)
(ffi/defcfn get-value "fj_get_value" [:int] :int)
(ffi/defcfn set-range "fj_set_range" [:int :int :int :int] :void)
(ffi/defcfn set-password "fj_set_password" [:int :int] :void)
(ffi/defcfn set-multiline "fj_set_multiline" [:int :int] :void)
(ffi/defcfn get-cursor-position "fj_get_cursor_position" [:int] :int)
(ffi/defcfn set-cursor-position "fj_set_cursor_position" [:int :int] :void)

;; --- driving a component directly (headless) ----------------------------------
(ffi/defcfn send-key "fj_send_key" [:int :int] :int :blocking)
;; Chez allows no :string argument on a :blocking call, so the text goes
;; through a C string we allocate for the call.
(ffi/defcfn ^:private send-char* "fj_send_char" [:int :pointer] :int :blocking)
(defn send-char [id s] (ffi/with-c-string [p (str s)] (send-char* id p)))
(ffi/defcfn send-mouse "fj_send_mouse" [:int :int :int :int :int] :int :blocking)
(ffi/defcfn send-custom "fj_send_custom" [:int] :int :blocking)

;; --- app / loop ---------------------------------------------------------------
(ffi/defcfn app-new "fj_app_new" [:int :int :int] :pointer)
(ffi/defcfn app-free "fj_app_free" [:pointer] :void)
;; app-loop blocks for the life of the UI; :blocking releases the collector
;; while the thread sits in it, and every callback it makes is :collect-safe.
(ffi/defcfn app-loop "fj_app_loop" [:pointer :int] :void :blocking)
(ffi/defcfn app-exit "fj_app_exit" [:pointer] :void)
(ffi/defcfn app-post-custom "fj_app_post_custom" [:pointer] :void)
(ffi/defcfn app-post-key "fj_app_post_key" [:pointer :int] :void)
(ffi/defcfn app-post-char "fj_app_post_char" [:pointer :string] :void)
(ffi/defcfn app-request-animation-frame "fj_app_request_animation_frame" [:pointer] :void)
(ffi/defcfn app-track-mouse "fj_app_track_mouse" [:pointer :int] :void)
(ffi/defcfn app-force-handle-ctrl-c "fj_app_force_handle_ctrl_c" [:pointer :int] :void)
(ffi/defcfn app-force-handle-ctrl-z "fj_app_force_handle_ctrl_z" [:pointer :int] :void)
(ffi/defcfn app-active "fj_app_active" [] :pointer)

(ffi/defcfn loop-new "fj_loop_new" [:pointer :int] :pointer)
(ffi/defcfn loop-free "fj_loop_free" [:pointer] :void)
(ffi/defcfn loop-run-once "fj_loop_run_once" [:pointer] :void :blocking)
(ffi/defcfn loop-run-once-blocking "fj_loop_run_once_blocking" [:pointer] :void :blocking)
(ffi/defcfn loop-has-quitted "fj_loop_has_quitted" [:pointer] :int)

(ffi/defcfn terminal-width "fj_terminal_width" [] :int)
(ffi/defcfn terminal-height "fj_terminal_height" [] :int)
