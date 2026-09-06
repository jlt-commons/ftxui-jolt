// ftxui_jolt.cpp — the C ABI over FTXUI declared in ftxui_jolt.h.
//
// Everything here is single-threaded by construction: FTXUI's loop runs on one
// thread and invokes Render / OnEvent on it; the headless entry points are
// called from a test thread that is not running a loop. The only thread-safe
// entry points are the fj_app_post_* ones, which go through FTXUI's own
// mutex-protected task queue.

#include "ftxui_jolt.h"

#include <algorithm>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include <ftxui/component/app.hpp>
#include <ftxui/component/component.hpp>
#include <ftxui/component/component_base.hpp>
#include <ftxui/component/component_options.hpp>
#include <ftxui/component/event.hpp>
#include <ftxui/component/loop.hpp>
#include <ftxui/component/mouse.hpp>
#include <ftxui/dom/canvas.hpp>
#include <ftxui/dom/elements.hpp>
#include <ftxui/dom/flexbox_config.hpp>
#include <ftxui/dom/linear_gradient.hpp>
#include <ftxui/dom/node.hpp>
#include <ftxui/dom/table.hpp>
#include <ftxui/screen/color.hpp>
#include <ftxui/screen/screen.hpp>
#include <ftxui/screen/terminal.hpp>
#include <ftxui/util/ref.hpp>

using namespace ftxui;

namespace {

// --- callbacks ---------------------------------------------------------------
fj_render_fn g_render = nullptr;
fj_action_fn g_action = nullptr;
fj_event_fn g_event = nullptr;

void fire(int32_t id, int32_t kind) {
  if (g_action) g_action(id, kind);
}

// --- element arena -----------------------------------------------------------
// Handles are 1-based indices into g_arena; 0 is "nothing". g_depth counts
// nested node renders so the arena is only cleared when the outermost one
// returns — a subtree rendered from inside a wrapper component pushes into the
// same arena and its handles must stay valid until the frame is complete.
std::vector<Element> g_arena;
int g_depth = 0;
std::string g_string_result;

int32_t push(Element e) {
  g_arena.push_back(std::move(e));
  return static_cast<int32_t>(g_arena.size());
}

Element get(int32_t h) {
  if (h <= 0 || h > static_cast<int32_t>(g_arena.size())) return emptyElement();
  return g_arena[static_cast<size_t>(h - 1)];
}

Elements gather(const int32_t* children, int32_t n) {
  Elements out;
  out.reserve(static_cast<size_t>(std::max(n, 0)));
  for (int32_t i = 0; i < n; ++i) out.push_back(get(children[i]));
  return out;
}

// Canvases and gradients are per-frame values feeding elements, so they share
// the element arena's lifetime: built during a render, dropped with it.
std::vector<Canvas> g_canvases;
std::vector<LinearGradient> g_gradients;

Canvas* canvas_at(int32_t h) {
  if (h <= 0 || h > static_cast<int32_t>(g_canvases.size())) return nullptr;
  return &g_canvases[static_cast<size_t>(h - 1)];
}

LinearGradient* gradient_at(int32_t h) {
  if (h <= 0 || h > static_cast<int32_t>(g_gradients.size())) return nullptr;
  return &g_gradients[static_cast<size_t>(h - 1)];
}

void clear_arena_if_outermost() {
  if (g_depth != 0) return;
  g_arena.clear();
  g_canvases.clear();
  g_gradients.clear();
}

// --- colors ------------------------------------------------------------------
Color decode_color(int32_t c) {
  if (c <= 0) return Color::Default;
  if (c & FJ_COLOR_RGB) {
    return Color::RGB(static_cast<uint8_t>((c >> 16) & 0xff),
                      static_cast<uint8_t>((c >> 8) & 0xff),
                      static_cast<uint8_t>(c & 0xff));
  }
  if (c & FJ_COLOR_P256) return Color(static_cast<Color::Palette256>(c & 0xff));
  if (c >= 1 && c <= 16) return Color(static_cast<Color::Palette16>(c - 1));
  return Color::Default;
}

BorderStyle border_style(int32_t s) {
  switch (s) {
    case 1: return DASHED;
    case 2: return HEAVY;
    case 3: return DOUBLE;
    case 4: return ROUNDED;
    case 5: return EMPTY;
    default: return LIGHT;
  }
}

Direction direction(int32_t d) {
  switch (d) {
    case 0: return Direction::Up;
    case 2: return Direction::Left;
    case 3: return Direction::Right;
    default: return Direction::Down;
  }
}

// --- events ------------------------------------------------------------------
struct NamedKey { const Event* ev; int32_t code; };

const std::vector<NamedKey>& named_keys() {
  static const std::vector<NamedKey> keys = {
      {&Event::ArrowLeft, FJ_KEY_ARROW_LEFT},
      {&Event::ArrowRight, FJ_KEY_ARROW_RIGHT},
      {&Event::ArrowUp, FJ_KEY_ARROW_UP},
      {&Event::ArrowDown, FJ_KEY_ARROW_DOWN},
      {&Event::ArrowLeftCtrl, FJ_KEY_ARROW_LEFT_CTRL},
      {&Event::ArrowRightCtrl, FJ_KEY_ARROW_RIGHT_CTRL},
      {&Event::ArrowUpCtrl, FJ_KEY_ARROW_UP_CTRL},
      {&Event::ArrowDownCtrl, FJ_KEY_ARROW_DOWN_CTRL},
      {&Event::Backspace, FJ_KEY_BACKSPACE},
      {&Event::Delete, FJ_KEY_DELETE},
      {&Event::Return, FJ_KEY_RETURN},
      {&Event::Escape, FJ_KEY_ESCAPE},
      {&Event::Tab, FJ_KEY_TAB},
      {&Event::TabReverse, FJ_KEY_TAB_REVERSE},
      {&Event::Insert, FJ_KEY_INSERT},
      {&Event::Home, FJ_KEY_HOME},
      {&Event::End, FJ_KEY_END},
      {&Event::PageUp, FJ_KEY_PAGE_UP},
      {&Event::PageDown, FJ_KEY_PAGE_DOWN},
      {&Event::F1, FJ_KEY_F1},   {&Event::F2, FJ_KEY_F2},   {&Event::F3, FJ_KEY_F3},
      {&Event::F4, FJ_KEY_F4},   {&Event::F5, FJ_KEY_F5},   {&Event::F6, FJ_KEY_F6},
      {&Event::F7, FJ_KEY_F7},   {&Event::F8, FJ_KEY_F8},   {&Event::F9, FJ_KEY_F9},
      {&Event::F10, FJ_KEY_F10}, {&Event::F11, FJ_KEY_F11}, {&Event::F12, FJ_KEY_F12},
  };
  return keys;
}

int32_t key_code(const Event& e) {
  for (const auto& k : named_keys()) {
    if (e == *k.ev) return k.code;
  }
  const std::string& in = e.input();
  if (in.size() == 1) {
    const int c = static_cast<unsigned char>(in[0]);
    if (c >= 1 && c <= 26) return FJ_KEY_CTRL_BASE + c;
  }
  if (in.size() == 2 && in[0] == '\x1b') {
    const int c = static_cast<unsigned char>(in[1]);
    if (c >= 1 && c <= 26) return FJ_KEY_CTRL_ALT_BASE + c;
    if (c >= 'a' && c <= 'z') return FJ_KEY_ALT_BASE + (c - 'a' + 1);
    if (c >= 'A' && c <= 'Z') return FJ_KEY_ALT_BASE + (c - 'A' + 1);
  }
  return FJ_KEY_NONE;
}

// Build an Event from a key code (the inverse of key_code).
bool event_from_key(int32_t code, Event* out) {
  for (const auto& k : named_keys()) {
    if (k.code == code) { *out = *k.ev; return true; }
  }
  if (code > FJ_KEY_CTRL_ALT_BASE && code <= FJ_KEY_CTRL_ALT_BASE + 26) {
    *out = Event::Special(std::string("\x1b") + static_cast<char>(code - FJ_KEY_CTRL_ALT_BASE));
    return true;
  }
  if (code > FJ_KEY_ALT_BASE && code <= FJ_KEY_ALT_BASE + 26) {
    *out = Event::Special(std::string("\x1b") + static_cast<char>('a' + code - FJ_KEY_ALT_BASE - 1));
    return true;
  }
  if (code > FJ_KEY_CTRL_BASE && code <= FJ_KEY_CTRL_BASE + 26) {
    *out = Event::Special(std::string(1, static_cast<char>(code - FJ_KEY_CTRL_BASE)));
    return true;
  }
  return false;
}

void fill_event(Event e, fj_event* out) {
  std::memset(out, 0, sizeof(*out));
  const std::string& in = e.input();
  std::strncpy(out->input, in.c_str(), sizeof(out->input) - 1);
  if (e.is_mouse()) {
    out->type = FJ_EV_MOUSE;
    const Mouse& m = e.mouse();
    out->button = m.button;
    out->motion = m.motion;
    out->shift = m.shift ? 1 : 0;
    out->meta = m.meta ? 1 : 0;
    out->control = m.control ? 1 : 0;
    out->x = m.x;
    out->y = m.y;
    return;
  }
  if (e == Event::Custom) { out->type = FJ_EV_CUSTOM; return; }
  if (e.is_character()) { out->type = FJ_EV_CHARACTER; return; }
  const int32_t code = key_code(e);
  out->type = code == FJ_KEY_NONE ? FJ_EV_UNKNOWN : FJ_EV_KEY;
  out->key = code;
}

bool offer_event(int32_t id, const Event& e) {
  if (!g_event) return false;
  fj_event ev;
  fill_event(e, &ev);
  return g_event(id, &ev) != 0;
}

// --- component slots ---------------------------------------------------------
// The widget's state lives here, and the option structs point into it
// (Ref<T>(T*)), so a setter is a plain assignment the widget sees on its next
// render and a getter reads what the widget last wrote.
class ChangeWatcher;

struct Slot {
  int32_t id = 0;
  Component comp;
  // The watcher wrapped around comp, when the component has one.
  ChangeWatcher* watcher = nullptr;
  std::string label;
  std::string content;
  std::string placeholder;
  std::vector<std::string> entries;
  int selected = 0;
  int focused_entry = 0;
  bool checked = false;
  bool show = false;
  bool password = false;
  bool multiline = false;
  bool insert = true;
  int cursor_position = 0;
  int value = 0;
  int min = 0;
  int max = 100;
  int increment = 1;
  int selector = 0;
  // The floating window's geometry, in cells.
  int left = 0;
  int top = 0;
  int width = 20;
  int height = 10;
  bool resize_left = true;
  bool resize_right = true;
  bool resize_top = true;
  bool resize_down = true;
};

std::unordered_map<int32_t, std::unique_ptr<Slot>> g_slots;

Slot* slot(int32_t id) {
  auto it = g_slots.find(id);
  return it == g_slots.end() ? nullptr : it->second.get();
}

Component comp(int32_t id) {
  Slot* s = slot(id);
  return s ? s->comp : nullptr;
}

Slot* new_slot(int32_t id) {
  auto s = std::make_unique<Slot>();
  s->id = id;
  Slot* raw = s.get();
  if (auto old = slot(id)) old->comp->Detach();
  g_slots[id] = std::move(s);
  return raw;
}

// A component whose rendering and event handling are delegated to jolt.
class JoltNode : public ComponentBase {
 public:
  JoltNode(int32_t id, bool has_handler) : id_(id), has_handler_(has_handler) {}

  Element OnRender() override {
    if (!g_render) return emptyElement();
    ++g_depth;
    Element e = get(g_render(id_));
    --g_depth;
    return e;
  }

  bool OnEvent(Event event) override {
    if (has_handler_ && offer_event(id_, event)) return true;
    return ComponentBase::OnEvent(std::move(event));
  }

 private:
  int32_t id_;
  bool has_handler_;
};

// ResizableSplit and Window write their new size or position straight into the
// refs they were given and offer no callback of their own, so this wraps them
// and fires FJ_ACTION_CHANGE once an event left the watched state elsewhere.
// State pushed from jolt is not a user change: the setters call resync.
class ChangeWatcher : public ComponentBase {
 public:
  ChangeWatcher(int32_t id, Component child, std::function<int64_t()> state)
      : id_(id), state_(std::move(state)) {
    Add(std::move(child));
    last_ = state_();
  }

  void Resync() { last_ = state_(); }

  bool OnEvent(Event event) override {
    const bool handled = ComponentBase::OnEvent(std::move(event));
    const int64_t now = state_();
    if (now != last_) {
      last_ = now;
      fire(id_, FJ_ACTION_CHANGE);
    }
    return handled;
  }

 private:
  int32_t id_;
  std::function<int64_t()> state_;
  int64_t last_ = 0;
};

// Install `child` under a watcher of `state` as the slot's component.
void watch(Slot* s, Component child, std::function<int64_t()> state) {
  auto w = Make<ChangeWatcher>(s->id, std::move(child), std::move(state));
  s->watcher = w.get();
  s->comp = std::move(w);
}

// State the jolt side pushed is the new baseline, not a change to report.
void resync(int32_t id) {
  Slot* s = slot(id);
  if (s && s->watcher) s->watcher->Resync();
}

// The four geometry fields of a floating window, as one comparable value.
int64_t window_state(const Slot* s) {
  int64_t v = s->left;
  v = v * 1000003 + s->top;
  v = v * 1000003 + s->width;
  v = v * 1000003 + s->height;
  return v;
}

bool same_children(const Component& parent, const int32_t* children, int32_t n) {
  if (static_cast<int32_t>(parent->ChildCount()) != n) return false;
  for (int32_t i = 0; i < n; ++i) {
    Component c = comp(children[i]);
    if (!c || parent->ChildAt(static_cast<size_t>(i)) != c) return false;
  }
  return true;
}

Event char_event(const char* utf8) {
  return Event::Character(std::string(utf8 ? utf8 : ""));
}

Event mouse_event(int32_t button, int32_t motion, int32_t x, int32_t y) {
  Mouse m;
  m.button = static_cast<Mouse::Button>(button);
  m.motion = static_cast<Mouse::Motion>(motion);
  m.x = x;
  m.y = y;
  return Event::Mouse("", m);
}

const char* render_screen(Element e, int32_t w, int32_t h, bool ansi) {
  Screen screen = Screen::Create(Dimension::Fixed(w), Dimension::Fixed(h));
  Render(screen, e);
  if (ansi) {
    g_string_result = screen.ToString();
  } else {
    g_string_result.clear();
    for (int y = 0; y < screen.dimy(); ++y) {
      if (y > 0) g_string_result += '\n';
      for (int x = 0; x < screen.dimx(); ++x) {
        const std::string& ch = screen.PixelAt(x, y).character;
        g_string_result += ch.empty() ? " " : ch;
      }
    }
  }
  return g_string_result.c_str();
}

}  // namespace

extern "C" {

// --- callbacks / misc ---------------------------------------------------------
void fj_set_callbacks(fj_render_fn render, fj_action_fn action, fj_event_fn event) {
  g_render = render;
  g_action = action;
  g_event = event;
}

const char* fj_version(void) { return "ftxui-jolt 0.1.0"; }

// --- elements ---------------------------------------------------------------
int32_t fj_text(const char* s) { return push(text(std::string(s ? s : ""))); }
int32_t fj_vtext(const char* s) { return push(vtext(std::string(s ? s : ""))); }

int32_t fj_paragraph(const char* s, int32_t align) {
  const std::string str(s ? s : "");
  switch (align) {
    case 1: return push(paragraphAlignLeft(str));
    case 2: return push(paragraphAlignRight(str));
    case 3: return push(paragraphAlignCenter(str));
    case 4: return push(paragraphAlignJustify(str));
    default: return push(paragraph(str));
  }
}

int32_t fj_separator(int32_t style) {
  if (style < 0) return push(separator());
  return push(separatorStyled(border_style(style)));
}

int32_t fj_separator_char(const char* s) {
  return push(separatorCharacter(std::string(s ? s : " ")));
}

int32_t fj_gauge(double progress, int32_t dir) {
  return push(gaugeDirection(static_cast<float>(progress), direction(dir)));
}

int32_t fj_spinner(int32_t charset, int32_t index) {
  return push(spinner(charset, static_cast<size_t>(std::max(index, 0))));
}

int32_t fj_empty(void) { return push(emptyElement()); }
int32_t fj_filler(void) { return push(filler()); }

int32_t fj_hbox(const int32_t* c, int32_t n) { return push(hbox(gather(c, n))); }
int32_t fj_vbox(const int32_t* c, int32_t n) { return push(vbox(gather(c, n))); }
int32_t fj_dbox(const int32_t* c, int32_t n) { return push(dbox(gather(c, n))); }
int32_t fj_hflow(const int32_t* c, int32_t n) { return push(hflow(gather(c, n))); }
int32_t fj_vflow(const int32_t* c, int32_t n) { return push(vflow(gather(c, n))); }

int32_t fj_flexbox(const int32_t* c, int32_t n, int32_t dir, int32_t wrap, int32_t justify,
                   int32_t align_items, int32_t align_content, int32_t gap_x, int32_t gap_y) {
  FlexboxConfig cfg;
  cfg.direction = static_cast<FlexboxConfig::Direction>(dir);
  cfg.wrap = static_cast<FlexboxConfig::Wrap>(wrap);
  cfg.justify_content = static_cast<FlexboxConfig::JustifyContent>(justify);
  cfg.align_items = static_cast<FlexboxConfig::AlignItems>(align_items);
  cfg.align_content = static_cast<FlexboxConfig::AlignContent>(align_content);
  cfg.gap_x = gap_x;
  cfg.gap_y = gap_y;
  return push(flexbox(gather(c, n), cfg));
}

int32_t fj_gridbox(const int32_t* cells, int32_t cols, int32_t rows) {
  std::vector<Elements> lines;
  lines.reserve(static_cast<size_t>(std::max(rows, 0)));
  for (int32_t r = 0; r < rows; ++r) lines.push_back(gather(cells + r * cols, cols));
  return push(gridbox(std::move(lines)));
}

int32_t fj_table(const int32_t* cells, int32_t cols, int32_t rows, int32_t bstyle,
                 int32_t header, int32_t separators) {
  std::vector<Elements> lines;
  lines.reserve(static_cast<size_t>(std::max(rows, 0)));
  for (int32_t r = 0; r < rows; ++r) lines.push_back(gather(cells + r * cols, cols));
  Table table(std::move(lines));
  if (bstyle >= 0) table.SelectAll().Border(border_style(bstyle));
  const BorderStyle sep = bstyle >= 0 ? border_style(bstyle) : LIGHT;
  if (separators & 1) table.SelectAll().SeparatorVertical(sep);
  if (separators & 2) table.SelectAll().SeparatorHorizontal(sep);
  if (header && rows > 0) {
    table.SelectRow(0).Decorate(bold);
    table.SelectRow(0).SeparatorVertical(sep);
    table.SelectRow(0).BorderBottom(sep);
  }
  return push(table.Render());
}

int32_t fj_border(int32_t child, int32_t style, int32_t color) {
  Element e = get(child);
  if (style < 0 && color <= 0) return push(border(e));
  const BorderStyle bs = style < 0 ? LIGHT : border_style(style);
  if (color <= 0) return push(e | borderStyled(bs));
  return push(e | borderStyled(bs, decode_color(color)));
}

int32_t fj_window(int32_t title, int32_t content, int32_t style) {
  return push(window(get(title), get(content), style < 0 ? ROUNDED : border_style(style)));
}

int32_t fj_style(int32_t child, int32_t style) {
  Element e = get(child);
  switch (style) {
    case 0: return push(bold(e));
    case 1: return push(dim(e));
    case 2: return push(italic(e));
    case 3: return push(inverted(e));
    case 4: return push(underlined(e));
    case 5: return push(underlinedDouble(e));
    case 6: return push(blink(e));
    case 7: return push(strikethrough(e));
    default: return push(e);
  }
}

int32_t fj_color(int32_t child, int32_t c) { return push(color(decode_color(c), get(child))); }
int32_t fj_bgcolor(int32_t child, int32_t c) { return push(bgcolor(decode_color(c), get(child))); }

int32_t fj_flex(int32_t child, int32_t kind) {
  Element e = get(child);
  switch (kind) {
    case 0: return push(flex(e));
    case 1: return push(flex_grow(e));
    case 2: return push(flex_shrink(e));
    case 3: return push(xflex(e));
    case 4: return push(xflex_grow(e));
    case 5: return push(xflex_shrink(e));
    case 6: return push(yflex(e));
    case 7: return push(yflex_grow(e));
    case 8: return push(yflex_shrink(e));
    case 9: return push(notflex(e));
    default: return push(e);
  }
}

int32_t fj_flex_factor(int32_t child, int32_t axis, int32_t grow, int32_t shrink) {
  Element e = get(child);
  switch (axis) {
    case 1: return push(e | xflex_factor(grow, shrink));
    case 2: return push(e | yflex_factor(grow, shrink));
    default: return push(e | flex_factor(grow, shrink));
  }
}

int32_t fj_size(int32_t child, int32_t wh, int32_t constraint, int32_t value) {
  const WidthOrHeight d = wh == 1 ? HEIGHT : WIDTH;
  const Constraint c = constraint == 0 ? LESS_THAN : constraint == 1 ? EQUAL : GREATER_THAN;
  return push(get(child) | size(d, c, value));
}

int32_t fj_frame(int32_t child, int32_t kind) {
  Element e = get(child);
  switch (kind) {
    case 1: return push(xframe(e));
    case 2: return push(yframe(e));
    default: return push(frame(e));
  }
}

int32_t fj_focus(int32_t child, int32_t shape) {
  Element e = get(child);
  switch (shape) {
    case 1: return push(focusCursorBlock(e));
    case 2: return push(focusCursorBlockBlinking(e));
    case 3: return push(focusCursorBar(e));
    case 4: return push(focusCursorBarBlinking(e));
    case 5: return push(focusCursorUnderline(e));
    case 6: return push(focusCursorUnderlineBlinking(e));
    default: return push(focus(e));
  }
}

int32_t fj_align(int32_t child, int32_t kind) {
  Element e = get(child);
  switch (kind) {
    case 1: return push(hcenter(e));
    case 2: return push(vcenter(e));
    case 3: return push(align_right(e));
    default: return push(center(e));
  }
}

int32_t fj_scroll_indicator(int32_t child, int32_t axis) {
  Element e = get(child);
  return push(axis == 1 ? hscroll_indicator(e) : vscroll_indicator(e));
}

int32_t fj_clear_under(int32_t child) { return push(clear_under(get(child))); }

int32_t fj_hyperlink(int32_t child, const char* url) {
  return push(hyperlink(std::string(url ? url : ""), get(child)));
}

int32_t fj_automerge(int32_t child) { return push(automerge(get(child))); }

// --- gradients --------------------------------------------------------------
int32_t fj_gradient_new(double angle) {
  LinearGradient g;
  g.angle = static_cast<float>(angle);
  g_gradients.push_back(std::move(g));
  return static_cast<int32_t>(g_gradients.size());
}

void fj_gradient_stop(int32_t gradient, int32_t color, double position) {
  LinearGradient* g = gradient_at(gradient);
  if (!g) return;
  if (position < 0) {
    g->Stop(decode_color(color));
  } else {
    g->Stop(decode_color(color), static_cast<float>(position));
  }
}

int32_t fj_color_gradient(int32_t child, int32_t gradient) {
  LinearGradient* g = gradient_at(gradient);
  if (!g) return push(get(child));
  return push(color(*g, get(child)));
}

int32_t fj_bgcolor_gradient(int32_t child, int32_t gradient) {
  LinearGradient* g = gradient_at(gradient);
  if (!g) return push(get(child));
  return push(bgcolor(*g, get(child)));
}

// --- canvas -----------------------------------------------------------------
int32_t fj_canvas_new(int32_t width, int32_t height) {
  g_canvases.emplace_back(std::max(width, 0), std::max(height, 0));
  return static_cast<int32_t>(g_canvases.size());
}

void fj_canvas_point(int32_t canvas, int32_t mode, int32_t x, int32_t y,
                     int32_t value, int32_t color) {
  Canvas* c = canvas_at(canvas);
  if (!c) return;
  const bool block = mode == 1;
  if (value == 2) {
    block ? c->DrawBlockToggle(x, y) : c->DrawPointToggle(x, y);
    return;
  }
  const bool on = value != 0;
  if (color > 0) {
    block ? c->DrawBlock(x, y, on, decode_color(color))
          : c->DrawPoint(x, y, on, decode_color(color));
  } else {
    block ? c->DrawBlock(x, y, on) : c->DrawPoint(x, y, on);
  }
}

void fj_canvas_line(int32_t canvas, int32_t mode, int32_t x1, int32_t y1,
                    int32_t x2, int32_t y2, int32_t color) {
  Canvas* c = canvas_at(canvas);
  if (!c) return;
  const bool block = mode == 1;
  if (color > 0) {
    block ? c->DrawBlockLine(x1, y1, x2, y2, decode_color(color))
          : c->DrawPointLine(x1, y1, x2, y2, decode_color(color));
  } else {
    block ? c->DrawBlockLine(x1, y1, x2, y2) : c->DrawPointLine(x1, y1, x2, y2);
  }
}

void fj_canvas_circle(int32_t canvas, int32_t mode, int32_t x, int32_t y,
                      int32_t radius, int32_t filled, int32_t color) {
  Canvas* c = canvas_at(canvas);
  if (!c) return;
  const bool block = mode == 1;
  if (color > 0) {
    const Color col = decode_color(color);
    if (filled) {
      block ? c->DrawBlockCircleFilled(x, y, radius, col)
            : c->DrawPointCircleFilled(x, y, radius, col);
    } else {
      block ? c->DrawBlockCircle(x, y, radius, col)
            : c->DrawPointCircle(x, y, radius, col);
    }
    return;
  }
  if (filled) {
    block ? c->DrawBlockCircleFilled(x, y, radius) : c->DrawPointCircleFilled(x, y, radius);
  } else {
    block ? c->DrawBlockCircle(x, y, radius) : c->DrawPointCircle(x, y, radius);
  }
}

void fj_canvas_ellipse(int32_t canvas, int32_t mode, int32_t x, int32_t y,
                       int32_t rx, int32_t ry, int32_t filled, int32_t color) {
  Canvas* c = canvas_at(canvas);
  if (!c) return;
  const bool block = mode == 1;
  if (color > 0) {
    const Color col = decode_color(color);
    if (filled) {
      block ? c->DrawBlockEllipseFilled(x, y, rx, ry, col)
            : c->DrawPointEllipseFilled(x, y, rx, ry, col);
    } else {
      block ? c->DrawBlockEllipse(x, y, rx, ry, col) : c->DrawPointEllipse(x, y, rx, ry, col);
    }
    return;
  }
  if (filled) {
    block ? c->DrawBlockEllipseFilled(x, y, rx, ry) : c->DrawPointEllipseFilled(x, y, rx, ry);
  } else {
    block ? c->DrawBlockEllipse(x, y, rx, ry) : c->DrawPointEllipse(x, y, rx, ry);
  }
}

void fj_canvas_text(int32_t canvas, int32_t x, int32_t y, const char* s, int32_t color) {
  Canvas* c = canvas_at(canvas);
  if (!c) return;
  const std::string str(s ? s : "");
  if (color > 0) {
    c->DrawText(x, y, str, decode_color(color));
  } else {
    c->DrawText(x, y, str);
  }
}

int32_t fj_canvas_element(int32_t canvas) {
  Canvas* c = canvas_at(canvas);
  if (!c) return push(emptyElement());
  return push(::ftxui::canvas(*c));
}

const char* fj_render_text(int32_t element, int32_t w, int32_t h) {
  const char* out = render_screen(get(element), w, h, false);
  clear_arena_if_outermost();
  return out;
}

const char* fj_render_ansi(int32_t element, int32_t w, int32_t h) {
  const char* out = render_screen(get(element), w, h, true);
  clear_arena_if_outermost();
  return out;
}

int32_t fj_arena_size(void) { return static_cast<int32_t>(g_arena.size()); }

// --- components -------------------------------------------------------------
void fj_button_new(int32_t id, int32_t style) {
  Slot* s = new_slot(id);
  ButtonOption opt;
  switch (style) {
    case 1: opt = ButtonOption::Ascii(); break;
    case 2: opt = ButtonOption::Border(); break;
    case 3: opt = ButtonOption::Animated(); break;
    default: opt = ButtonOption::Simple(); break;
  }
  opt.label = &s->label;
  opt.on_click = [id] { fire(id, FJ_ACTION_CLICK); };
  s->comp = Button(opt);
}

void fj_input_new(int32_t id) {
  Slot* s = new_slot(id);
  InputOption opt;
  opt.content = &s->content;
  opt.placeholder = &s->placeholder;
  opt.password = &s->password;
  opt.multiline = &s->multiline;
  opt.insert = &s->insert;
  opt.cursor_position = &s->cursor_position;
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  opt.on_enter = [id] { fire(id, FJ_ACTION_ENTER); };
  s->comp = Input(opt);
}

void fj_checkbox_new(int32_t id) {
  Slot* s = new_slot(id);
  CheckboxOption opt;
  opt.label = &s->label;
  opt.checked = &s->checked;
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  s->comp = Checkbox(opt);
}

void fj_menu_new(int32_t id, int32_t dir, int32_t style) {
  Slot* s = new_slot(id);
  MenuOption opt;
  const bool horizontal = dir == 2 || dir == 3;
  switch (style) {
    case 1: opt = horizontal ? MenuOption::HorizontalAnimated() : MenuOption::VerticalAnimated(); break;
    case 2: opt = MenuOption::Toggle(); break;
    default: opt = horizontal ? MenuOption::Horizontal() : MenuOption::Vertical(); break;
  }
  opt.direction = direction(dir);
  opt.entries = &s->entries;
  opt.selected = &s->selected;
  opt.focused_entry = &s->focused_entry;
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  opt.on_enter = [id] { fire(id, FJ_ACTION_ENTER); };
  s->comp = Menu(opt);
}

void fj_radiobox_new(int32_t id) {
  Slot* s = new_slot(id);
  RadioboxOption opt;
  opt.entries = &s->entries;
  opt.selected = &s->selected;
  opt.focused_entry = &s->focused_entry;
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  s->comp = Radiobox(opt);
}

void fj_dropdown_new(int32_t id) {
  Slot* s = new_slot(id);
  DropdownOption opt;
  opt.open = &s->show;
  opt.radiobox.entries = &s->entries;
  opt.radiobox.selected = &s->selected;
  opt.radiobox.focused_entry = &s->focused_entry;
  opt.radiobox.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  s->comp = Dropdown(opt);
}

void fj_slider_new(int32_t id, int32_t dir, int32_t color_active, int32_t color_inactive) {
  Slot* s = new_slot(id);
  SliderOption<int> opt;
  opt.value = &s->value;
  opt.min = &s->min;
  opt.max = &s->max;
  opt.increment = &s->increment;
  opt.direction = dir < 0 ? Direction::Right : direction(dir);
  if (color_active > 0) opt.color_active = decode_color(color_active);
  if (color_inactive > 0) opt.color_inactive = decode_color(color_inactive);
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  s->comp = Slider(opt);
}

void fj_container_new(int32_t id, int32_t kind) {
  Slot* s = new_slot(id);
  switch (kind) {
    case 1: s->comp = Container::Horizontal({}, &s->selector); break;
    case 2: s->comp = Container::Tab({}, &s->selector); break;
    case 3: s->comp = Container::Stacked({}); break;
    default: s->comp = Container::Vertical({}, &s->selector); break;
  }
}

void fj_node_new(int32_t id, int32_t has_event_handler) {
  Slot* s = new_slot(id);
  s->comp = Make<JoltNode>(id, has_event_handler != 0);
}

void fj_maybe_new(int32_t id, int32_t child) {
  Component c = comp(child);
  if (!c) return;
  Slot* s = new_slot(id);
  s->comp = Maybe(c, &s->show);
}

void fj_modal_new(int32_t id, int32_t main, int32_t modal) {
  Component m = comp(main);
  Component d = comp(modal);
  if (!m || !d) return;
  Slot* s = new_slot(id);
  s->comp = Modal(m, d, &s->show);
}

// Collapsible is rebuilt here rather than taken from FTXUI so the toggle
// reports through the action callback (FTXUI's has no on_change).
void fj_collapsible_new(int32_t id, int32_t child) {
  Component c = comp(child);
  if (!c) return;
  Slot* s = new_slot(id);
  CheckboxOption opt;
  opt.label = &s->label;
  opt.checked = &s->show;
  opt.transform = [](const EntryState& st) {
    auto prefix = text(st.state ? "▼ " : "▶ ");
    auto t = text(st.label);
    if (st.active) t |= bold;
    if (st.focused) t |= inverted;
    return hbox({prefix, t});
  };
  opt.on_change = [id] { fire(id, FJ_ACTION_CHANGE); };
  s->comp = Container::Vertical({Checkbox(opt), Maybe(c, &s->show)});
}

void fj_resizable_split_new(int32_t id, int32_t main, int32_t back, int32_t dir) {
  Component m = comp(main);
  Component b = comp(back);
  if (!m || !b) return;
  Slot* s = new_slot(id);
  const bool horizontal = dir == 2 || dir == 3;
  s->value = horizontal ? 20 : 10;  // FTXUI's own defaults
  s->max = std::numeric_limits<int>::max();
  ResizableSplitOption opt;
  opt.main = m;
  opt.back = b;
  opt.direction = direction(dir);
  opt.main_size = &s->value;
  opt.min = &s->min;
  opt.max = &s->max;
  watch(s, ResizableSplit(opt), [s] { return static_cast<int64_t>(s->value); });
}

void fj_hoverable_new(int32_t id, int32_t child) {
  Component c = comp(child);
  if (!c) return;
  Slot* s = new_slot(id);
  watch(s, Hoverable(c, &s->checked), [s] { return static_cast<int64_t>(s->checked); });
}

void fj_window_component_new(int32_t id, int32_t inner) {
  Component c = comp(inner);
  if (!c) return;
  Slot* s = new_slot(id);
  WindowOptions opt;
  opt.inner = c;
  opt.title = &s->label;
  opt.left = &s->left;
  opt.top = &s->top;
  opt.width = &s->width;
  opt.height = &s->height;
  opt.resize_left = &s->resize_left;
  opt.resize_right = &s->resize_right;
  opt.resize_top = &s->resize_top;
  opt.resize_down = &s->resize_down;
  watch(s, Window(opt), [s] { return window_state(s); });
}

void fj_window_set_rect(int32_t id, int32_t left, int32_t top, int32_t width, int32_t height) {
  Slot* s = slot(id);
  if (!s) return;
  s->left = left;
  s->top = top;
  s->width = width;
  s->height = height;
  resync(id);
}

int32_t fj_window_get(int32_t id, int32_t which) {
  Slot* s = slot(id);
  if (!s) return 0;
  switch (which) {
    case 1: return s->top;
    case 2: return s->width;
    case 3: return s->height;
    default: return s->left;
  }
}

void fj_window_set_resize(int32_t id, int32_t left, int32_t right, int32_t top, int32_t down) {
  Slot* s = slot(id);
  if (!s) return;
  s->resize_left = left != 0;
  s->resize_right = right != 0;
  s->resize_top = top != 0;
  s->resize_down = down != 0;
}

int32_t fj_component_exists(int32_t id) { return slot(id) ? 1 : 0; }

void fj_component_free(int32_t id) {
  auto it = g_slots.find(id);
  if (it == g_slots.end()) return;
  Component c = it->second->comp;
  if (c) {
    c->Detach();
    c->DetachAllChildren();
  }
  g_slots.erase(it);
}

void fj_set_children(int32_t id, const int32_t* children, int32_t n) {
  Component parent = comp(id);
  if (!parent) return;
  if (same_children(parent, children, n)) return;
  parent->DetachAllChildren();
  for (int32_t i = 0; i < n; ++i) {
    if (Component c = comp(children[i])) parent->Add(c);
  }
}

int32_t fj_child_count(int32_t id) {
  Component c = comp(id);
  return c ? static_cast<int32_t>(c->ChildCount()) : 0;
}

void fj_take_focus(int32_t id) {
  if (Component c = comp(id)) c->TakeFocus();
}

int32_t fj_focused(int32_t id) {
  Component c = comp(id);
  return c && c->Focused() ? 1 : 0;
}

int32_t fj_active(int32_t id) {
  Component c = comp(id);
  return c && c->Active() ? 1 : 0;
}

int32_t fj_focusable(int32_t id) {
  Component c = comp(id);
  return c && c->Focusable() ? 1 : 0;
}

int32_t fj_component_render(int32_t id) {
  Component c = comp(id);
  if (!c) return 0;
  return push(c->Render());
}

const char* fj_component_render_text(int32_t id, int32_t w, int32_t h) {
  Component c = comp(id);
  if (!c) { g_string_result.clear(); return g_string_result.c_str(); }
  ++g_depth;
  Element e = c->Render();
  --g_depth;
  const char* out = render_screen(std::move(e), w, h, false);
  clear_arena_if_outermost();
  return out;
}

// --- slot state -------------------------------------------------------------
void fj_set_label(int32_t id, const char* v) { if (Slot* s = slot(id)) s->label = v ? v : ""; }
void fj_set_content(int32_t id, const char* v) {
  if (Slot* s = slot(id)) {
    s->content = v ? v : "";
    s->cursor_position = std::min(s->cursor_position, static_cast<int>(s->content.size()));
  }
}
const char* fj_get_content(int32_t id) {
  Slot* s = slot(id);
  return s ? s->content.c_str() : "";
}
void fj_set_placeholder(int32_t id, const char* v) { if (Slot* s = slot(id)) s->placeholder = v ? v : ""; }
void fj_entries_clear(int32_t id) { if (Slot* s = slot(id)) s->entries.clear(); }
void fj_entries_add(int32_t id, const char* v) { if (Slot* s = slot(id)) s->entries.emplace_back(v ? v : ""); }
int32_t fj_entries_count(int32_t id) { Slot* s = slot(id); return s ? static_cast<int32_t>(s->entries.size()) : 0; }
void fj_set_selected(int32_t id, int32_t i) {
  if (Slot* s = slot(id)) { s->selected = i; s->focused_entry = i; }
}
int32_t fj_get_selected(int32_t id) { Slot* s = slot(id); return s ? s->selected : 0; }
void fj_set_checked(int32_t id, int32_t b) {
  if (Slot* s = slot(id)) { s->checked = b != 0; resync(id); }
}
int32_t fj_get_checked(int32_t id) { Slot* s = slot(id); return s && s->checked ? 1 : 0; }
void fj_set_show(int32_t id, int32_t b) { if (Slot* s = slot(id)) s->show = b != 0; }
int32_t fj_get_show(int32_t id) { Slot* s = slot(id); return s && s->show ? 1 : 0; }
void fj_set_value(int32_t id, int32_t v) {
  if (Slot* s = slot(id)) { s->value = v; resync(id); }
}
int32_t fj_get_value(int32_t id) { Slot* s = slot(id); return s ? s->value : 0; }
void fj_set_range(int32_t id, int32_t min, int32_t max, int32_t increment) {
  if (Slot* s = slot(id)) { s->min = min; s->max = max; s->increment = increment; }
}
void fj_set_password(int32_t id, int32_t b) { if (Slot* s = slot(id)) s->password = b != 0; }
void fj_set_multiline(int32_t id, int32_t b) { if (Slot* s = slot(id)) s->multiline = b != 0; }
int32_t fj_get_cursor_position(int32_t id) { Slot* s = slot(id); return s ? s->cursor_position : 0; }
void fj_set_cursor_position(int32_t id, int32_t pos) { if (Slot* s = slot(id)) s->cursor_position = pos; }

// --- driving a component directly -------------------------------------------
int32_t fj_send_key(int32_t id, int32_t key) {
  Component c = comp(id);
  Event e;
  if (!c || !event_from_key(key, &e)) return 0;
  return c->OnEvent(e) ? 1 : 0;
}

int32_t fj_send_char(int32_t id, const char* utf8) {
  Component c = comp(id);
  if (!c) return 0;
  return c->OnEvent(char_event(utf8)) ? 1 : 0;
}

int32_t fj_send_mouse(int32_t id, int32_t button, int32_t motion, int32_t x, int32_t y) {
  Component c = comp(id);
  if (!c) return 0;
  return c->OnEvent(mouse_event(button, motion, x, y)) ? 1 : 0;
}

int32_t fj_send_custom(int32_t id) {
  Component c = comp(id);
  if (!c) return 0;
  return c->OnEvent(Event::Custom) ? 1 : 0;
}

// --- app --------------------------------------------------------------------
void* fj_app_new(int32_t mode, int32_t w, int32_t h) {
  switch (mode) {
    case 1: return new App(App::FitComponent());
    case 2: return new App(App::TerminalOutput());
    case 3: return new App(App::FixedSize(w, h));
    case 4: return new App(App::FullscreenAlternateScreen());
    case 5: return new App(App::FullscreenPrimaryScreen());
    default: return new App(App::Fullscreen());
  }
}

void fj_app_free(void* app) { delete static_cast<App*>(app); }

void fj_app_loop(void* app, int32_t root) {
  Component c = comp(root);
  if (!app || !c) return;
  static_cast<App*>(app)->Loop(c);
}

void fj_app_exit(void* app) { if (app) static_cast<App*>(app)->Exit(); }
void fj_app_post_custom(void* app) { if (app) static_cast<App*>(app)->PostEvent(Event::Custom); }

void fj_app_post_key(void* app, int32_t key) {
  Event e;
  if (app && event_from_key(key, &e)) static_cast<App*>(app)->PostEvent(e);
}

void fj_app_post_char(void* app, const char* utf8) {
  if (app) static_cast<App*>(app)->PostEvent(char_event(utf8));
}

void fj_app_request_animation_frame(void* app) {
  if (app) static_cast<App*>(app)->RequestAnimationFrame();
}

void fj_app_track_mouse(void* app, int32_t enable) {
  if (app) static_cast<App*>(app)->TrackMouse(enable != 0);
}

void fj_app_force_handle_ctrl_c(void* app, int32_t force) {
  if (app) static_cast<App*>(app)->ForceHandleCtrlC(force != 0);
}

void fj_app_force_handle_ctrl_z(void* app, int32_t force) {
  if (app) static_cast<App*>(app)->ForceHandleCtrlZ(force != 0);
}

void* fj_app_active(void) { return App::Active(); }

void* fj_loop_new(void* app, int32_t root) {
  Component c = comp(root);
  if (!app || !c) return nullptr;
  return new Loop(static_cast<App*>(app), c);
}

void fj_loop_free(void* loop) { delete static_cast<Loop*>(loop); }
void fj_loop_run_once(void* loop) { if (loop) static_cast<Loop*>(loop)->RunOnce(); }
void fj_loop_run_once_blocking(void* loop) { if (loop) static_cast<Loop*>(loop)->RunOnceBlocking(); }
int32_t fj_loop_has_quitted(void* loop) { return loop && static_cast<Loop*>(loop)->HasQuitted() ? 1 : 0; }

void fj_set_color_support(int32_t depth) {
  switch (depth) {
    case 0: Terminal::SetColorSupport(Terminal::Color::Palette1); break;
    case 1: Terminal::SetColorSupport(Terminal::Color::Palette16); break;
    case 2: Terminal::SetColorSupport(Terminal::Color::Palette256); break;
    default: Terminal::SetColorSupport(Terminal::Color::TrueColor); break;
  }
}

int32_t fj_terminal_width(void) { return Terminal::Size().dimx; }
int32_t fj_terminal_height(void) { return Terminal::Size().dimy; }

}  // extern "C"
