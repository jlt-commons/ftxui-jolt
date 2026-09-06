# ftxui-jolt

A reagent-style API over [FTXUI](https://github.com/ArthurSonzogni/FTXUI), the
C++ terminal UI library, for [jolt](https://github.com/jolt-lang/jolt).

You write components as functions returning hiccup; ftxui-jolt renders them
through FTXUI's own component tree, with FTXUI's focus handling, mouse support,
input editing and event loop underneath.

```clojure
(ns myapp
  (:require [ftxui.core :as ui :refer [atom]]))

(defn counter []
  (let [n (atom 0)]
    (fn []
      [:vbox {:border :rounded}
       [:text {:bold true} " Count: " @n " "]
       [:separator]
       [:hbox
        [:button {:label "-1" :on-click #(swap! n dec)}]
        [:button {:label "+1" :on-click #(swap! n inc)}]
        [:button {:label "quit" :on-click ui/exit!}]]])))

(defn -main [& _] (ui/run counter :mode :fit-component))
```

```
╭──────────────────────╮
│ Count: 0             │
├──────────────────────┤
│┌──┐┌──┐┌─────┐┌────┐ │
││-1││+1││reset││quit│ │
│└──┘└──┘└─────┘└────┘ │
╰──────────────────────╯
```

## How it fits together

FTXUI has no retained widget tree to patch. Its loop calls `Render()` on a
component tree after every event, and `Render()` rebuilds the DOM from scratch.
That is already reagent's model — a render function re-run whenever something
changes — so this library needs no reactive cells and no reconciler of its own:

- **Elements** (`:text`, `:vbox`, `:border`, `:gauge`, …) are FTXUI DOM nodes,
  built fresh on every frame from whatever your component returned.
- **Widgets** (`:button`, `:input`, `:menu`, …) are FTXUI components. They hold
  state — focus, a cursor, a selection — so they are created once at their
  position in the tree and updated from their props after that.
- **Layout is the focus tree.** A `:vbox` or `:hbox` with more than one
  focusable descendant becomes an FTXUI container, so arrows and Tab move
  through the UI the way it is laid out. You never wire containers by hand.
- **State is plain atoms.** A handler that swaps one is followed by a redraw,
  because every event ends in a draw. State changed from anywhere else — a
  timer, another thread, the REPL — reaches the screen through `refresh!`, or
  automatically when it lives in an `ftxui.core/atom`.

Nothing here depends on [glimmer](https://github.com/jolt-lang/glimmer); this
is the same reagent shape over FTXUI's reactivity rather than glimmer's.

## Requirements

FTXUI is C++, so the FFI binds a small C shim (`native/ftxui_jolt.cpp`) with
FTXUI linked into it statically. Building it needs **cmake** (3.14+) and a
**C++17 compiler**; nothing else is required at runtime.

```sh
jolt native      # builds native/libftxui_jolt.{dylib,so}; re-run after editing the shim
```

FTXUI itself is fetched from GitHub at a pinned commit and built as part of
that step. To use a checkout instead, point `FTXUI_SOURCE_DIR` at it
(`FTXUI_SOURCE_DIR=~/src/FTXUI jolt native`); an installed FTXUI 7 found by
`find_package` is used as well. The `test` and example tasks depend on
`native`, so a fresh checkout only needs `jolt test`.

## Running

```sh
jolt test      # the suite, headless: real FTXUI widgets, no terminal needed
jolt counter   # the counter above
jolt todo      # a task board: input, keyed checkbox list, derived counts
jolt showcase  # every element and widget, a modal, a background ticker
jolt smoke     # runs a real loop for a second, clicks a button, exits
```

## Components

Two shapes, as in reagent:

- **Form-1** — a function returning hiccup. Re-run on every frame.
- **Form-2** — a function returning a render fn. The outer fn runs once when
  the component appears (so local atoms persist); the returned fn renders.

Components are invoked as `[my-component arg ...]`. Their identity is their
position in the tree (or their `:key`), so a component that disappears and
comes back starts fresh, and a keyed row keeps its widgets and local state
across reorders:

```clojure
(for [t @tasks] ^{:key (:id t)} [task-row t])
```

## Hiccup

Elements are `[:tag props? & children]`. Strings, numbers and keywords become
text; `nil` children are skipped and seqs are spliced, so `(when ...)` and
`(for ...)` work as children. A component must return a single element (wrap
a seq in `[:vbox ...]`).

### Elements

| tag | children | notes |
|---|---|---|
| `:text` | strings, concatenated | `[:text "n=" @n]` |
| `:vtext` | strings | vertical text |
| `:paragraph` | strings | wraps words; `:align :left/:right/:center/:justify` |
| `:separator` | — | `:style` (a border style) or `:char "·"`; orients itself |
| `:gauge` | — | `:value` 0–1, `:direction :right/:left/:up/:down` |
| `:spinner` | — | `:charset` (0–22), `:index` — one frame; advance `:index` yourself |
| `:filler` `:empty` | — | expandable blank / nothing |
| `:hbox` `:vbox` `:dbox` | elements | horizontal, vertical, stacked (`:dbox` draws later children over earlier) |
| `:hflow` `:vflow` | elements | wrapping flows |
| `:flexbox` | elements | `:direction :row/:column(-inversed)`, `:wrap`, `:justify`, `:align-items`, `:align-content`, `:gap [x y]` (CSS flexbox names) |
| `:gridbox` | — | `:rows [[cell ...] ...]`, cells are hiccup |
| `:table` | — | `:rows`, `:border` style, `:header true`, `:separators :vertical/:horizontal/:both` |
| `:border` | one or more | `:style :light/:dashed/:heavy/:double/:rounded/:empty`, `:color` |
| `:window` | one or more | `:title` (string or hiccup), `:style` |

A wrapper given several children lays them out as an `:hbox` first.

### Decorators

Every element and widget accepts these props; the matching tags
(`[:bold ...]`, `[:center ...]`, …) are sugar for the same thing:

- `:bold :dim :italic :inverted :underlined :underlined-double :blink :strikethrough` — booleans
- `:color` / `:bg` — see [Color](#color)
- `:border` — `true`, a style keyword, or `{:style ... :color ...}`
- `:width` / `:height` — `n`, `[:<= n]`, `[:>= n]` or `[:= n]`
- `:flex` — `true`, `:grow`, `:shrink`, `:x`, `:y`, `:x-grow`, `:x-shrink`, `:y-grow`, `:y-shrink`, `:none`
- `:frame` — `true`, `:x` or `:y`: a viewport that scrolls to keep the focused element visible
- `:scroll-indicator` — `:v` or `:h`
- `:align` — `:center`, `:hcenter`, `:vcenter` or `:right`
- `:focus` — `true` or a cursor shape (`:block`, `:bar`, `:underline`, each also `-blinking`)
- `:clear-under`, `:automerge` — booleans; `:hyperlink` — a URL

Decorators apply inner to outer in the order listed, so `{:color :red :border true}`
colors the text but not the border. Nest explicit tags for a different order:
`[:color {:fg :red} [:border ...]]`.

### Widgets

| tag | props | events |
|---|---|---|
| `:button` | `:label` (or a string child), `:style :simple/:ascii/:border/:animated` | `:on-click` |
| `:input` | `:value`, `:placeholder`, `:password`, `:multiline` | `:on-change` (text), `:on-enter` (text) |
| `:checkbox` | `:label`, `:checked` | `:on-change` (boolean) |
| `:menu` | `:entries`, `:selected`, `:direction :down/:up/:left/:right`, `:style :plain/:animated/:toggle` | `:on-change` (index), `:on-enter` (index) |
| `:toggle` | `:entries`, `:selected` | as menu |
| `:radiobox` | `:entries`, `:selected` | `:on-change` (index) |
| `:dropdown` | `:entries`, `:selected`, `:open` | `:on-change` (index) |
| `:slider` | `:value`, `:min`, `:max`, `:increment`, `:direction`, `:color`, `:color-inactive` | `:on-change` (value) |
| `:collapsible` | `:label`, `:show`; one child subtree | `:on-change` (boolean) |
| `:modal` | `:show`; two children: main, dialog | — |
| `:maybe` | `:show`; one child subtree | — |
| `:catch-event` | `:on-event`; the children it guards | `:on-event` (event map) |

Any widget also takes `:autofocus true` to start with the focus, and `:key`.

**Controlled props.** `:value`, `:checked`, `:selected`, `:show` follow the
reagent contract: what the prop says each frame is what the widget shows. The
widget fires `:on-change` with the value the user produced; if the handler does
not write it back, the next frame restores the prop's value. Leave the prop out
for an uncontrolled widget that keeps its own state.

**Focus.** Arrows move within a layout (Up/Down in a `:vbox`, Left/Right in an
`:hbox`), Tab and Shift-Tab cycle. A widget that uses a key itself keeps it — a
menu takes Up/Down until its ends, a radiobox takes Tab to cycle its entries —
exactly as in FTXUI.

### Events

An `:on-event` handler (on `run`, or a `:catch-event`) receives a map and
returns truthy to consume the event:

```clojure
{:type :key       :key :arrow-down :input "\e[B"}     ; :return :tab :escape :f1 ... :ctrl-a :alt-x
{:type :character :char "q" :input "q"}
{:type :mouse     :button :left :motion :pressed :x 3 :y 4 :shift false :meta false :control false}
{:type :custom}
```

### Color

`:red`, `:green`, `:yellow`, `:blue`, `:magenta`, `:cyan`, `:white`, `:black`,
`:gray-light`, `:gray-dark` and the `-light` variants; a 0–255 palette index;
`[:rgb r g b]`; or `"#ff8800"` / `"#f80"`. `:default` (or nil) is the
terminal's own.

## API (`ftxui.core`)

- `(run component & opts)` — mount and run the loop on the calling thread until
  `exit!` or Ctrl-C. Options: `:mode` (`:fullscreen` default, `:fit-component`,
  `:terminal-output`, `:fixed` with `:width`/`:height`, `:fullscreen-alternate`,
  `:fullscreen-primary`), `:mouse false`, `:on-event`, `:auto-exit-ms`, and
  `:async true` to run on another thread and return a future.
- `(exit!)` — stop the loop, from any thread.
- `(refresh!)` — redraw after a state change made outside a handler (thread-safe).
- `(atom x)` — a clojure atom that calls `refresh!` when it changes.
- `(post-key! :return)`, `(post-char! "abc")` — inject events into the running loop.
- `(reload!)` — remount every component (after redefining them at the REPL).

Errors thrown by a handler or a render stop the loop and are rethrown by `run`.

### Headless

The same machinery runs without a terminal, which is how the tests drive real
FTXUI widgets:

```clojure
(ui/with-screen [s counter]
  (ui/render-text s 30 5)          ; the next frame, as text
  (ui/send-key! s :return)         ; deliver a key; then prepare the next frame
  (ui/send-char! s "abc")
  (ui/send-mouse! s {:button :left :motion :pressed :x 2 :y 1})
  (ui/refresh! s)                  ; sync state changed outside a handler
  (ui/stats s))                    ; {:components 3 :containers 1 ...}

(ui/render-text [:border "hi"] 6 3)   ; bare hiccup renders too
(ui/render-ansi [:bold "x"] 1 1)      ; with escape codes
```

## Live development

Under `jolt nrepl-server`, `(ui/run app :async true)` returns right away and
the UI runs on its own thread. Mutate an `ftxui.core/atom` and the screen
redraws; after redefining components, `(ui/reload!)` remounts them in place.
The TUI takes over the terminal the server was started from, so evaluate from
an editor connected to the nREPL port.

## Architecture

- **`native/ftxui_jolt.cpp`** — the C ABI over FTXUI: a per-frame arena of
  element handles, component slots keyed by id, the app loop, and three
  callbacks into jolt (render, action, event). `native/ftxui_jolt.h` documents it.
- **`ftxui.ffi`** — `defcfn` bindings to the shim. No logic.
- **`ftxui.color`**, **`ftxui.keys`** — the color and key vocabularies.
- **`ftxui.dom`** — element tags and the universal decorators; builds the
  DOM from a prepared tree each frame.
- **`ftxui.widget`** — widget tags: how each is created, updated from its
  props, and which `:on-*` handlers it fires.
- **`ftxui.render`** — the frame: walks hiccup, keeps widgets and focus
  containers in step by tree position, sweeps what disappeared, and serves the
  callbacks.
- **`ftxui.core`** — the public API.

## Not yet covered

Canvas drawing, gradients, `ResizableSplit`, `Hoverable`, the draggable
`Window` component, and animated button/menu colors (they build but need an
animation frame source). The shim exposes what FTXUI has; adding a tag is a
spec in `ftxui.widget` or `ftxui.dom` plus, where needed, a shim function.
