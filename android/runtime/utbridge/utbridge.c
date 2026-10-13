/*
 * utbridge - charge via LD_PRELOAD dans le moteur UT2004 (runtime Linux de l'appli Android).
 *
 * - Clavier : recu de l'appli sur une socket TCP locale (port UT_INPUT_PORT) et injecte dans
 *   l'ecran X virtuel (Xvfb) avec l'extension XTest.
 * - Souris : injectee directement dans la file d'evenements SDL3 du moteur (mouvements,
 *   boutons, molette). En mode "souris relative" (jeux de tir), SDL3 ignore les mouvements
 *   simules par XTest.
 *   (La manette est convertie en clavier/souris cote appli.)
 * - Pause : quand l'appli passe en arriere-plan, la boucle du jeu est bloquee a la fin de
 *   l'image en cours (SDL_GL_SwapWindow).
 * - Journal : stderr et, si UT_LOG_DIR est defini, <UT_LOG_DIR>/utbridge.log.
 *
 * Messages de 8 octets, little-endian : uint8 type, uint8 reserve, uint16 code, int32 valeur
 *   type 1 = touche   (code = keysym X11, valeur 1 appuyee / 0 relachee)
 *   type 2 = bouton   (code = bouton souris X11 : 1 gauche, 2 milieu, 3 droit, 4/5 molette)
 *   type 3 = souris   (valeur = dx sur les 16 bits bas, dy sur les 16 bits hauts, signes)
 *   type 4 = pause    (valeur 1 pause / 0 reprise)
 *   type 5 = position (valeur = x sur les 16 bits bas, y sur les 16 bits hauts)
 *   type 6 = texte    (valeur = un point de code Unicode)
 */
#define _GNU_SOURCE
#include <SDL3/SDL.h>
#include <X11/Xlib.h>
#include <X11/extensions/XTest.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define MSG_KEY    1
#define MSG_BUTTON 2
#define MSG_MOTION 3
#define MSG_PAUSE  4
#define MSG_POSITION 5
#define MSG_TEXT 6
#define MSG_MODE 7 /* moteur -> Android : souris absolue / menus */

static int paused;
static pthread_mutex_t pause_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pause_cond = PTHREAD_COND_INITIALIZER;
static FILE *logf;
static FILE *stats_file;
static uint64_t stats_start;
static unsigned stats_frames;
static int output_fd = -1, cursor_visible = 1;
static int gui_available;
static pthread_mutex_t output_lock = PTHREAD_MUTEX_INITIALIZER;

static void send_mode_locked(void)
{
    uint8_t message[8] = {MSG_MODE, 0, 0, 0, cursor_visible, 0, 0, 0};
    size_t sent = 0;
    while (output_fd >= 0 && sent < sizeof(message)) {
        ssize_t count = send(output_fd, message + sent, sizeof(message) - sent, MSG_NOSIGNAL);
        if (count <= 0) break;
        sent += count;
    }
}

static void cursor_mode(int visible)
{
    pthread_mutex_lock(&output_lock);
    if (cursor_visible != visible) {
        cursor_visible = visible;
        send_mode_locked();
    }
    pthread_mutex_unlock(&output_lock);
}

bool SDL_SetWindowRelativeMouseMode(SDL_Window *window, bool enabled)
{
    static bool (*real_mode)(SDL_Window *, bool);
    if (!real_mode) real_mode = dlsym(RTLD_NEXT, "SDL_SetWindowRelativeMouseMode");
    bool result = real_mode(window, enabled);
    if (result && !gui_available) cursor_mode(!enabled);
    return result;
}

/* UT2004 keeps relative mouse capture in its GUI. Ask its own GUIActive method
 * on the render thread, using the exported engine pointer; no object offsets. */
static void sync_engine_gui(void)
{
    static int checked;
    static void **engine;
    static int (*gui_active)(const void *, const void *);
    if (!checked) {
        checked = 1;
        engine = dlsym(RTLD_DEFAULT, "_ZN7UEngine7GEngineE");
        gui_active = dlsym(RTLD_DEFAULT, "_ZNK11UGameEngine9GUIActiveEPK7UClient");
        gui_available = engine && gui_active;
        fprintf(stderr, "utbridge: detection des menus : %s\n", gui_available ? "GUIActive du moteur" : "capture SDL");
    }
    if (gui_available && *engine) cursor_mode(gui_active(*engine, NULL) != 0);
}

/* Fenetre du moteur (memorisee a chaque image) et position absolue de la souris */
static SDL_Window *volatile game_window;
static volatile int window_w = 1280, window_h = 720;
static float mouse_x = -1, mouse_y = -1;
static float relative_x, relative_y;
static SDL_MouseButtonFlags mouse_buttons;
static int mouse_active;
static pthread_mutex_t mouse_lock = PTHREAD_MUTEX_INITIALIZER;

/* SDL_PushEvent does not update SDL's device state. The engine also polls it. */
SDL_MouseButtonFlags SDL_GetMouseState(float *x, float *y)
{
    static SDL_MouseButtonFlags (*real_state)(float *, float *);
    if (!real_state) real_state = dlsym(RTLD_NEXT, "SDL_GetMouseState");
    SDL_MouseButtonFlags buttons = real_state(x, y);
    pthread_mutex_lock(&mouse_lock);
    if (mouse_active) {
        if (x) *x = mouse_x;
        if (y) *y = mouse_y;
        buttons |= mouse_buttons;
    }
    pthread_mutex_unlock(&mouse_lock);
    return buttons;
}

SDL_MouseButtonFlags SDL_GetRelativeMouseState(float *x, float *y)
{
    static SDL_MouseButtonFlags (*real_state)(float *, float *);
    if (!real_state) real_state = dlsym(RTLD_NEXT, "SDL_GetRelativeMouseState");
    SDL_MouseButtonFlags buttons = real_state(x, y);
    pthread_mutex_lock(&mouse_lock);
    if (x) *x += relative_x;
    if (y) *y += relative_y;
    relative_x = relative_y = 0;
    buttons |= mouse_buttons;
    pthread_mutex_unlock(&mouse_lock);
    return buttons;
}

static void logmsg(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    if (logf) {
        va_start(ap, fmt);
        vfprintf(logf, fmt, ap);
        va_end(ap);
        fflush(logf);
    }
}

static void set_paused(int value)
{
    pthread_mutex_lock(&pause_lock);
    paused = value ? 1 : 0;
    pthread_cond_broadcast(&pause_cond);
    pthread_mutex_unlock(&pause_lock);
}

static int read_full(int fd, void *buf, size_t len)
{
    uint8_t *p = buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static SDL_WindowID window_id(void)
{
    SDL_Window *w = game_window;
    return w ? SDL_GetWindowID(w) : 0;
}

static void push_motion(int dx, int dy)
{
    SDL_Event ev;
    pthread_mutex_lock(&mouse_lock);
    if (mouse_x < 0) {
        mouse_x = window_w / 2.0f;
        mouse_y = window_h / 2.0f;
    }
    mouse_x = SDL_clamp(mouse_x + dx, 0, window_w - 1);
    mouse_y = SDL_clamp(mouse_y + dy, 0, window_h - 1);
    mouse_active = 1;
    relative_x += dx;
    relative_y += dy;
    SDL_zero(ev);
    ev.motion.type = SDL_EVENT_MOUSE_MOTION;
    ev.motion.timestamp = SDL_GetTicksNS();
    ev.motion.windowID = window_id();
    ev.motion.which = 1;
    ev.motion.state = mouse_buttons;
    ev.motion.x = mouse_x;
    ev.motion.y = mouse_y;
    ev.motion.xrel = (float)dx;
    ev.motion.yrel = (float)dy;
    pthread_mutex_unlock(&mouse_lock);
    SDL_PushEvent(&ev);
}

static void push_button(int x11_button, int down)
{
    SDL_Event ev;
    if ((x11_button == 4 || x11_button == 5) && !down) return;
    pthread_mutex_lock(&mouse_lock);
    if (mouse_x < 0) {
        mouse_x = window_w / 2.0f;
        mouse_y = window_h / 2.0f;
    }
    SDL_zero(ev);
    if (x11_button == 4 || x11_button == 5) { /* molette : evenement a l'appui seulement */
        ev.wheel.type = SDL_EVENT_MOUSE_WHEEL;
        ev.wheel.timestamp = SDL_GetTicksNS();
        ev.wheel.windowID = window_id();
        ev.wheel.which = 1;
        ev.wheel.y = x11_button == 4 ? 1.0f : -1.0f;
        ev.wheel.direction = SDL_MOUSEWHEEL_NORMAL;
        ev.wheel.mouse_x = mouse_x;
        ev.wheel.mouse_y = mouse_y;
    } else {
        ev.button.type = down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP;
        ev.button.timestamp = SDL_GetTicksNS();
        ev.button.windowID = window_id();
        ev.button.which = 1;
        ev.button.button = x11_button == 1 ? SDL_BUTTON_LEFT
                         : x11_button == 2 ? SDL_BUTTON_MIDDLE : SDL_BUTTON_RIGHT;
        ev.button.down = down ? true : false;
        ev.button.clicks = 1;
        ev.button.x = mouse_x;
        ev.button.y = mouse_y;
        SDL_MouseButtonFlags mask = SDL_BUTTON_MASK(ev.button.button);
        if (down) mouse_buttons |= mask;
        else mouse_buttons &= ~mask;
    }
    mouse_active = 1;
    pthread_mutex_unlock(&mouse_lock);
    SDL_PushEvent(&ev);
}

/* Navigation keys go through the same SDL queue as the mouse, without X focus. */
static void push_sdl_key(SDL_Keycode key, int down, SDL_Keymod modifiers)
{
    SDL_Event ev;
    SDL_zero(ev);
    ev.key.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP;
    ev.key.timestamp = SDL_GetTicksNS();
    ev.key.windowID = window_id();
    ev.key.key = key;
    ev.key.scancode = SDL_GetScancodeFromKey(key, NULL);
    ev.key.mod = modifiers;
    ev.key.down = down != 0;
    SDL_PushEvent(&ev);
}

/* SDL_PushEvent does not own custom text buffers. Keep them alive until the
 * consumer requests its next event, then release only our own allocations. */
struct bridge_text { struct bridge_text *next; char utf8[5]; };
static struct bridge_text *queued_text;
static pthread_mutex_t text_lock = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local struct bridge_text *consumed_text;

/* UT's GUI tracks its own IK_Shift state, rather than SDL event modifiers.
 * Preserve reverse Tab while the viewport consumes that exact SDL event. */
static _Thread_local int reverse_tab_event;
bool SDL_PollEvent(SDL_Event *event)
{
    static bool (*real_poll)(SDL_Event *);
    if (!real_poll) real_poll = dlsym(RTLD_NEXT, "SDL_PollEvent");
    free(consumed_text);
    consumed_text = NULL;
    bool result = real_poll(event);
    if (result && event && event->type == SDL_EVENT_TEXT_INPUT) {
        pthread_mutex_lock(&text_lock);
        struct bridge_text **link = &queued_text;
        while (*link && (*link)->utf8 != event->text.text) link = &(*link)->next;
        if (*link) { consumed_text = *link; *link = (*link)->next; }
        pthread_mutex_unlock(&text_lock);
    }
    reverse_tab_event = result && event &&
        (event->type == SDL_EVENT_KEY_DOWN || event->type == SDL_EVENT_KEY_UP) &&
        event->key.key == SDLK_TAB && (event->key.mod & SDL_KMOD_SHIFT);
    return result;
}

int viewport_input(void *viewport, int key, int action, float delta)
    __asm__("_ZN12USDLViewport15CauseInputEventEi12EInputActionf");
int viewport_input(void *viewport, int key, int action, float delta)
{
    static int (*real_input)(void *, int, int, float);
    if (!real_input) real_input = dlsym(RTLD_NEXT,
        "_ZN12USDLViewport15CauseInputEventEi12EInputActionf");
    if (!real_input) return 0;
    if (key == 9 && reverse_tab_event) {
        real_input(viewport, 16, 1, 0); /* IK_Shift, IST_Press */
        int result = real_input(viewport, key, action, delta);
        real_input(viewport, 16, 3, 0); /* IST_Release */
        return result;
    } else return real_input(viewport, key, action, delta);
}

static int push_navigation_key(uint16_t keysym, int down)
{
    SDL_Keycode key;
    switch (keysym) {
    case 0xff51: key = SDLK_LEFT; break;
    case 0xff52: key = SDLK_UP; break;
    case 0xff53: key = SDLK_RIGHT; break;
    case 0xff54: key = SDLK_DOWN; break;
    case 0xff09: key = SDLK_TAB; break;
    case 0xfe20: key = SDLK_TAB; break; /* ISO_Left_Tab: previous GUI control */
    case 0xff0d: key = SDLK_RETURN; break;
    case 0xff1b: key = SDLK_ESCAPE; break;
    default: return 0;
    }
    SDL_Keymod modifiers = SDL_GetModState();
    if (keysym == 0xfe20) {
        modifiers |= SDL_KMOD_LSHIFT;
    }
    push_sdl_key(key, down, modifiers);
    return 1;
}

static void handle(Display *dpy, uint8_t type, uint16_t code, int32_t value)
{
    switch (type) {
    case MSG_KEY: {
        if (push_navigation_key(code, value)) break;
        KeyCode kc = XKeysymToKeycode(dpy, (KeySym)code);
        if (kc) {
            XTestFakeKeyEvent(dpy, kc, value ? True : False, CurrentTime);
            XFlush(dpy);
        } else {
            logmsg("utbridge: keysym 0x%x sans keycode\n", code);
        }
        break;
    }
    case MSG_BUTTON:
        push_button(code, value);
        break;
    case MSG_MOTION:
        push_motion((int16_t)(value & 0xffff), (int16_t)((uint32_t)value >> 16));
        break;
    case MSG_POSITION: {
        int x = (uint16_t)(value & 0xffff), y = (uint16_t)((uint32_t)value >> 16);
        pthread_mutex_lock(&mouse_lock);
        int dx = x - (mouse_x < 0 ? window_w / 2 : mouse_x);
        int dy = y - (mouse_y < 0 ? window_h / 2 : mouse_y);
        pthread_mutex_unlock(&mouse_lock);
        push_motion(dx, dy);
        break;
    }
    case MSG_TEXT: {
        /* One Unicode scalar per message; retain text until SDL consumes it. */
        uint32_t c = (uint32_t)value;
        char text[5] = {0};
        if (c == 0 || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) break;
        if (c < 0x80) text[0] = c;
        else if (c < 0x800) {
            text[0] = 0xc0 | (c >> 6); text[1] = 0x80 | (c & 63);
        } else if (c < 0x10000) {
            text[0] = 0xe0 | (c >> 12); text[1] = 0x80 | ((c >> 6) & 63); text[2] = 0x80 | (c & 63);
        } else {
            text[0] = 0xf0 | (c >> 18); text[1] = 0x80 | ((c >> 12) & 63);
            text[2] = 0x80 | ((c >> 6) & 63); text[3] = 0x80 | (c & 63);
        }
        SDL_Event ev;
        SDL_zero(ev);
        ev.text.type = SDL_EVENT_TEXT_INPUT;
        ev.text.timestamp = SDL_GetTicksNS();
        ev.text.windowID = window_id();
        struct bridge_text *owned = malloc(sizeof(*owned));
        if (!owned) break;
        memcpy(owned->utf8, text, sizeof(text));
        ev.text.text = owned->utf8;
        pthread_mutex_lock(&text_lock);
        owned->next = queued_text;
        queued_text = owned;
        if (!SDL_PushEvent(&ev)) {
            queued_text = owned->next;
            free(owned);
        }
        pthread_mutex_unlock(&text_lock);
        break;
    }
    case MSG_PAUSE:
        set_paused(value);
        break;
    }
}

static void *input_thread(void *arg)
{
    const char *port_env = getenv("UT_INPUT_PORT");
    int port = port_env ? atoi(port_env) : 0;
    Display *dpy = NULL;
    (void)arg;
    if (port <= 0) return NULL;

    /* Xvfb est demarre avant le moteur, mais on reessaie au cas ou */
    for (int i = 0; i < 100 && !(dpy = XOpenDisplay(NULL)); i++) usleep(100 * 1000);
    if (!dpy) {
        logmsg("utbridge: ecran X introuvable (DISPLAY=%s)\n", getenv("DISPLAY") ? getenv("DISPLAY") : "");
        return NULL;
    }

    for (;;) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons((uint16_t)port);
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (fd >= 0 && connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            int one = 1;
            uint8_t msg[8];
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
            struct timeval timeout = {0, 250000};
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            pthread_mutex_lock(&output_lock);
            output_fd = fd;
            send_mode_locked();
            pthread_mutex_unlock(&output_lock);
            logmsg("utbridge: entrees connectees (port %d)\n", port);
            while (read_full(fd, msg, sizeof(msg)) == 0) {
                uint16_t code = (uint16_t)(msg[2] | (msg[3] << 8));
                int32_t value = (int32_t)((uint32_t)msg[4] | ((uint32_t)msg[5] << 8) |
                                          ((uint32_t)msg[6] << 16) | ((uint32_t)msg[7] << 24));
                handle(dpy, msg[0], code, value);
            }
            logmsg("utbridge: entrees deconnectees\n");
            pthread_mutex_lock(&output_lock);
            output_fd = -1;
            pthread_mutex_unlock(&output_lock);
        }
        if (fd >= 0) close(fd);
        set_paused(0); /* appli deconnectee : ne jamais rester bloque */
        usleep(200 * 1000);
    }
    return NULL;
}

/* Demarre le pont dans le moteur : a l'initialisation de SDL3 (seul le moteur l'appelle). */
static void bridge_start(void)
{
    static int started;
    pthread_t thread;
    const char *dir = getenv("UT_LOG_DIR");
    if (started) return;
    started = 1;
    if (dir) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/utbridge.log", dir);
        logf = fopen(path, "w");
        snprintf(path, sizeof(path), "%s/render-fps.txt", dir);
        stats_file = fopen(path, "w");
    }
    logmsg("utbridge: demarre (port %s)\n", getenv("UT_INPUT_PORT") ? getenv("UT_INPUT_PORT") : "?");
    XInitThreads();
    if (pthread_create(&thread, NULL, input_thread, NULL) == 0) pthread_detach(thread);
}

bool SDL_Init(SDL_InitFlags flags)
{
    static bool (*real_init)(SDL_InitFlags);
    if (!real_init) real_init = (bool (*)(SDL_InitFlags))dlsym(RTLD_NEXT, "SDL_Init");
    bridge_start();
    return real_init(flags);
}

bool SDL_GL_SwapWindow(SDL_Window *window)
{
    static bool (*real_swap)(SDL_Window *);
    bool ret;
    int w, h;
    if (!real_swap) real_swap = (bool (*)(SDL_Window *))dlsym(RTLD_NEXT, "SDL_GL_SwapWindow");
    ret = real_swap(window);
    sync_engine_gui();
    if (stats_file && ret) {
        uint64_t now = SDL_GetTicksNS();
        if (!stats_start) stats_start = now;
        else {
            stats_frames++;
            if (now - stats_start >= 1000000000ULL) {
                rewind(stats_file);
                fprintf(stats_file, "%.1f\n", stats_frames * 1000000000.0 / (now - stats_start));
                fflush(stats_file);
                ftruncate(fileno(stats_file), ftell(stats_file));
                stats_frames = 0;
                stats_start = now;
            }
        }
    }
    if (window != game_window) {
        game_window = window;
        if (SDL_GetWindowSize(window, &w, &h) && w > 0 && h > 0) {
            window_w = w;
            window_h = h;
        }
    }
    pthread_mutex_lock(&pause_lock);
    while (paused) {
        stats_start = 0;
        stats_frames = 0;
        pthread_cond_wait(&pause_cond, &pause_lock);
    }
    pthread_mutex_unlock(&pause_lock);
    return ret;
}
