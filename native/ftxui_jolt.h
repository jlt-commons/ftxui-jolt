/* ftxui_jolt.h — a C ABI over FTXUI, for jolt's foreign-function interface.
 *
 * FTXUI is a C++ library (std::function, shared_ptr, std::string), which the
 * FFI cannot bind directly, so this shim exposes the small, flat surface the
 * jolt side needs:
 *
 *   elements    — the DOM. Built fresh every frame into an arena of int
 *                 handles (1-based; 0 is "no element"). The arena is cleared
 *                 once the outermost render returns, so handles are valid for
 *                 the frame that produced them and no longer.
 *   components  — the stateful, focusable widgets (button, input, menu, ...).
 *                 Each lives in a slot keyed by an int id the caller chooses.
 *                 The widget's state (label, content, selected index, ...)
 *                 lives in the slot; setters push into it, getters read back.
 *   nodes       — a component whose rendering is delegated back to jolt
 *                 (fj_render_fn) and whose events are offered to jolt first
 *                 (fj_event_fn). The root of an app is one; so are the
 *                 subtrees a wrapper (modal, collapsible) holds.
 *   app / loop  — ScreenInteractive: run the blocking loop, or step it.
 *
 * Callbacks are plain C function pointers registered once via
 * fj_set_callbacks. They are invoked on the thread running the loop (or the
 * thread calling fj_component_render_text / fj_component_send_* headlessly).
 */
#ifndef FTXUI_JOLT_H
#define FTXUI_JOLT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define FJ_API __declspec(dllexport)
#else
#define FJ_API __attribute__((visibility("default")))
#endif

/* --- events ---------------------------------------------------------------- */

/* fj_event.type */
enum { FJ_EV_UNKNOWN = 0, FJ_EV_CHARACTER = 1, FJ_EV_MOUSE = 2, FJ_EV_CUSTOM = 3, FJ_EV_KEY = 4 };

/* fj_event.key for FJ_EV_KEY. 100+n / 200+n / 300+n are ctrl / alt / ctrl+alt
 * with letter n (1 = a .. 26 = z). */
enum {
  FJ_KEY_NONE = 0,
  FJ_KEY_ARROW_LEFT = 1, FJ_KEY_ARROW_RIGHT, FJ_KEY_ARROW_UP, FJ_KEY_ARROW_DOWN,
  FJ_KEY_ARROW_LEFT_CTRL, FJ_KEY_ARROW_RIGHT_CTRL, FJ_KEY_ARROW_UP_CTRL, FJ_KEY_ARROW_DOWN_CTRL,
  FJ_KEY_BACKSPACE, FJ_KEY_DELETE, FJ_KEY_RETURN, FJ_KEY_ESCAPE, FJ_KEY_TAB, FJ_KEY_TAB_REVERSE,
  FJ_KEY_INSERT, FJ_KEY_HOME, FJ_KEY_END, FJ_KEY_PAGE_UP, FJ_KEY_PAGE_DOWN,
  FJ_KEY_F1 = 20, FJ_KEY_F2, FJ_KEY_F3, FJ_KEY_F4, FJ_KEY_F5, FJ_KEY_F6,
  FJ_KEY_F7, FJ_KEY_F8, FJ_KEY_F9, FJ_KEY_F10, FJ_KEY_F11, FJ_KEY_F12,
  FJ_KEY_CTRL_BASE = 100, FJ_KEY_ALT_BASE = 200, FJ_KEY_CTRL_ALT_BASE = 300
};

/* Mouse button / motion mirror ftxui::Mouse. */
enum { FJ_MOUSE_LEFT = 0, FJ_MOUSE_MIDDLE, FJ_MOUSE_RIGHT, FJ_MOUSE_NONE,
       FJ_MOUSE_WHEEL_UP, FJ_MOUSE_WHEEL_DOWN, FJ_MOUSE_WHEEL_LEFT, FJ_MOUSE_WHEEL_RIGHT };
enum { FJ_MOTION_RELEASED = 0, FJ_MOTION_PRESSED, FJ_MOTION_MOVED };

typedef struct fj_event {
  int32_t type;
  int32_t key;
  int32_t button;
  int32_t motion;
  int32_t shift;
  int32_t meta;
  int32_t control;
  int32_t x;
  int32_t y;
  char input[32]; /* the raw bytes the terminal sent, NUL-terminated */
} fj_event;

/* --- callbacks ------------------------------------------------------------- */

/* Render the node `id`: return an element handle (0 renders nothing). */
typedef int32_t (*fj_render_fn)(int32_t id);
/* A component fired an action: FJ_ACTION_* below. */
typedef void (*fj_action_fn)(int32_t id, int32_t kind);
/* A node received an event. Return non-zero if handled. */
typedef int32_t (*fj_event_fn)(int32_t id, const fj_event* ev);

enum { FJ_ACTION_CLICK = 0, FJ_ACTION_CHANGE = 1, FJ_ACTION_ENTER = 2 };

FJ_API void fj_set_callbacks(fj_render_fn render, fj_action_fn action, fj_event_fn event);
FJ_API const char* fj_version(void);

/* --- colors ----------------------------------------------------------------
 * One int encodes a color:  0 = default (transparent);  1..16 = palette16
 * index + 1;  0x100 | i = palette256 index i;  0x1000000 | (r<<16 | g<<8 | b)
 * = true color. */
#define FJ_COLOR_DEFAULT 0
#define FJ_COLOR_P256 0x100
#define FJ_COLOR_RGB 0x1000000

/* --- elements (per-frame arena) ------------------------------------------- */

FJ_API int32_t fj_text(const char* s);
FJ_API int32_t fj_vtext(const char* s);
/* align: 0 default (paragraph), 1 left, 2 right, 3 center, 4 justify */
FJ_API int32_t fj_paragraph(const char* s, int32_t align);
/* style: -1 default, else ftxui::BorderStyle (0 light, 1 dashed, 2 heavy, 3 double, 4 rounded, 5 empty) */
FJ_API int32_t fj_separator(int32_t style);
FJ_API int32_t fj_separator_char(const char* s);
/* direction: ftxui::Direction (0 up, 1 down, 2 left, 3 right) */
FJ_API int32_t fj_gauge(double progress, int32_t direction);
FJ_API int32_t fj_spinner(int32_t charset, int32_t index);
FJ_API int32_t fj_empty(void);
FJ_API int32_t fj_filler(void);
FJ_API int32_t fj_hbox(const int32_t* children, int32_t n);
FJ_API int32_t fj_vbox(const int32_t* children, int32_t n);
FJ_API int32_t fj_dbox(const int32_t* children, int32_t n);
FJ_API int32_t fj_hflow(const int32_t* children, int32_t n);
FJ_API int32_t fj_vflow(const int32_t* children, int32_t n);
/* FlexboxConfig enums by ordinal: direction 0 row 1 row-inversed 2 column 3 column-inversed;
 * wrap 0 no-wrap 1 wrap 2 wrap-inversed; justify 0 flex-start 1 flex-end 2 center 3 stretch
 * 4 space-between 5 space-around 6 space-evenly; align_items 0..3 like justify's first four;
 * align_content 0..6 like justify. */
FJ_API int32_t fj_flexbox(const int32_t* children, int32_t n, int32_t direction, int32_t wrap,
                          int32_t justify, int32_t align_items, int32_t align_content,
                          int32_t gap_x, int32_t gap_y);
/* cells is row-major, rows * cols entries (0 = empty cell). */
FJ_API int32_t fj_gridbox(const int32_t* cells, int32_t cols, int32_t rows);
/* A table over element cells. border_style -1 = no outer border. header: 1 = bold
 * first row with a separator under it. separators: bit 1 vertical, bit 2 horizontal. */
FJ_API int32_t fj_table(const int32_t* cells, int32_t cols, int32_t rows,
                        int32_t border_style, int32_t header, int32_t separators);
/* style -1 = default border; color 0 = default */
FJ_API int32_t fj_border(int32_t child, int32_t style, int32_t color);
FJ_API int32_t fj_window(int32_t title, int32_t content, int32_t style);
/* style: 0 bold 1 dim 2 italic 3 inverted 4 underlined 5 underlined-double 6 blink 7 strikethrough */
FJ_API int32_t fj_style(int32_t child, int32_t style);
FJ_API int32_t fj_color(int32_t child, int32_t color);
FJ_API int32_t fj_bgcolor(int32_t child, int32_t color);
/* kind: 0 flex 1 grow 2 shrink 3 xflex 4 xgrow 5 xshrink 6 yflex 7 ygrow 8 yshrink 9 notflex */
FJ_API int32_t fj_flex(int32_t child, int32_t kind);
/* axis: 0 both, 1 x, 2 y */
FJ_API int32_t fj_flex_factor(int32_t child, int32_t axis, int32_t grow, int32_t shrink);
/* wh: 0 width 1 height; constraint: 0 less-than 1 equal 2 greater-than */
FJ_API int32_t fj_size(int32_t child, int32_t wh, int32_t constraint, int32_t value);
/* kind: 0 frame 1 xframe 2 yframe */
FJ_API int32_t fj_frame(int32_t child, int32_t kind);
/* shape: 0 focus, 1 block 2 block-blinking 3 bar 4 bar-blinking 5 underline 6 underline-blinking */
FJ_API int32_t fj_focus(int32_t child, int32_t shape);
/* kind: 0 center 1 hcenter 2 vcenter 3 align-right */
FJ_API int32_t fj_align(int32_t child, int32_t kind);
/* axis: 0 vertical 1 horizontal */
FJ_API int32_t fj_scroll_indicator(int32_t child, int32_t axis);
FJ_API int32_t fj_clear_under(int32_t child);
FJ_API int32_t fj_hyperlink(int32_t child, const char* url);
FJ_API int32_t fj_automerge(int32_t child);

/* Headless: lay `element` out on a w x h screen and return its plain text
 * (rows joined by '\n', no escape codes). Clears the arena. The returned
 * pointer is valid until the next fj_* call that returns a string. */
FJ_API const char* fj_render_text(int32_t element, int32_t w, int32_t h);
/* Same, but keeps the escape sequences (what a terminal would receive). */
FJ_API const char* fj_render_ansi(int32_t element, int32_t w, int32_t h);
/* Number of handles currently in the arena (tests / diagnostics). */
FJ_API int32_t fj_arena_size(void);

/* --- components (slots keyed by caller-chosen id) --------------------------- */

/* style: 0 simple 1 ascii 2 border 3 animated */
FJ_API void fj_button_new(int32_t id, int32_t style);
FJ_API void fj_input_new(int32_t id);
FJ_API void fj_checkbox_new(int32_t id);
/* direction: ftxui::Direction (0 up 1 down 2 left 3 right); style: 0 plain 1 animated 2 toggle */
FJ_API void fj_menu_new(int32_t id, int32_t direction, int32_t style);
FJ_API void fj_radiobox_new(int32_t id);
FJ_API void fj_dropdown_new(int32_t id);
/* direction as for menu; colors 0 = default */
FJ_API void fj_slider_new(int32_t id, int32_t direction, int32_t color_active, int32_t color_inactive);
/* kind: 0 vertical 1 horizontal 2 tab 3 stacked */
FJ_API void fj_container_new(int32_t id, int32_t kind);
/* A node: renders through fj_render_fn(id), offers events to fj_event_fn(id)
 * when has_event_handler is non-zero, then to its children. */
FJ_API void fj_node_new(int32_t id, int32_t has_event_handler);
FJ_API void fj_maybe_new(int32_t id, int32_t child);
FJ_API void fj_modal_new(int32_t id, int32_t main, int32_t modal);
FJ_API void fj_collapsible_new(int32_t id, int32_t child);

FJ_API int32_t fj_component_exists(int32_t id);
FJ_API void fj_component_free(int32_t id);
/* Replace `id`'s children with `children` (detaching the old ones). A no-op
 * when the list is already exactly that. */
FJ_API void fj_set_children(int32_t id, const int32_t* children, int32_t n);
FJ_API int32_t fj_child_count(int32_t id);
FJ_API void fj_take_focus(int32_t id);
FJ_API int32_t fj_focused(int32_t id);
FJ_API int32_t fj_active(int32_t id);
FJ_API int32_t fj_focusable(int32_t id);
/* Render the component into the arena; returns the element handle. */
FJ_API int32_t fj_component_render(int32_t id);
/* Headless render of a component to plain text (see fj_render_text). */
FJ_API const char* fj_component_render_text(int32_t id, int32_t w, int32_t h);

/* slot state */
FJ_API void fj_set_label(int32_t id, const char* s);
FJ_API void fj_set_content(int32_t id, const char* s);
FJ_API const char* fj_get_content(int32_t id);
FJ_API void fj_set_placeholder(int32_t id, const char* s);
FJ_API void fj_entries_clear(int32_t id);
FJ_API void fj_entries_add(int32_t id, const char* s);
FJ_API int32_t fj_entries_count(int32_t id);
FJ_API void fj_set_selected(int32_t id, int32_t i);
FJ_API int32_t fj_get_selected(int32_t id);
FJ_API void fj_set_checked(int32_t id, int32_t b);
FJ_API int32_t fj_get_checked(int32_t id);
FJ_API void fj_set_show(int32_t id, int32_t b);
FJ_API int32_t fj_get_show(int32_t id);
FJ_API void fj_set_value(int32_t id, int32_t v);
FJ_API int32_t fj_get_value(int32_t id);
FJ_API void fj_set_range(int32_t id, int32_t min, int32_t max, int32_t increment);
FJ_API void fj_set_password(int32_t id, int32_t b);
FJ_API void fj_set_multiline(int32_t id, int32_t b);
FJ_API int32_t fj_get_cursor_position(int32_t id);
FJ_API void fj_set_cursor_position(int32_t id, int32_t pos);

/* --- driving a component directly (headless tests) ------------------------- */
FJ_API int32_t fj_send_key(int32_t id, int32_t key);
FJ_API int32_t fj_send_char(int32_t id, const char* utf8);
FJ_API int32_t fj_send_mouse(int32_t id, int32_t button, int32_t motion, int32_t x, int32_t y);
FJ_API int32_t fj_send_custom(int32_t id);

/* --- app (ScreenInteractive) ---------------------------------------------- */
/* mode: 0 fullscreen 1 fit-component 2 terminal-output 3 fixed(w,h)
 *       4 fullscreen-alternate-screen 5 fullscreen-primary-screen */
FJ_API void* fj_app_new(int32_t mode, int32_t w, int32_t h);
FJ_API void fj_app_free(void* app);
/* Blocks running the loop on the calling thread until fj_app_exit. */
FJ_API void fj_app_loop(void* app, int32_t root);
FJ_API void fj_app_exit(void* app);
/* Thread-safe: queue a custom event, which invalidates the frame and redraws. */
FJ_API void fj_app_post_custom(void* app);
/* Thread-safe: queue a key / character event (tests, automation). */
FJ_API void fj_app_post_key(void* app, int32_t key);
FJ_API void fj_app_post_char(void* app, const char* utf8);
FJ_API void fj_app_request_animation_frame(void* app);
FJ_API void fj_app_track_mouse(void* app, int32_t enable);
FJ_API void fj_app_force_handle_ctrl_c(void* app, int32_t force);
FJ_API void fj_app_force_handle_ctrl_z(void* app, int32_t force);
/* The app currently running a loop, or NULL. */
FJ_API void* fj_app_active(void);

/* Stepping the loop by hand instead of fj_app_loop. */
FJ_API void* fj_loop_new(void* app, int32_t root);
FJ_API void fj_loop_free(void* loop);
FJ_API void fj_loop_run_once(void* loop);
FJ_API void fj_loop_run_once_blocking(void* loop);
FJ_API int32_t fj_loop_has_quitted(void* loop);

FJ_API int32_t fj_terminal_width(void);
FJ_API int32_t fj_terminal_height(void);

#ifdef __cplusplus
}
#endif
#endif /* FTXUI_JOLT_H */
