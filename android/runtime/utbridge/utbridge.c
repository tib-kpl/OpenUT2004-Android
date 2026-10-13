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

static int paused;
static pthread_mutex_t pause_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pause_cond = PTHREAD_COND_INITIALIZER;
static FILE *logf;

/* Fenetre du moteur (memorisee a chaque image) et position absolue de la souris */
static SDL_Window *volatile game_window;
static volatile int window_w = 1280, window_h = 720;
static float mouse_x = -1, mouse_y = -1;

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
    if (mouse_x < 0) {
        mouse_x = window_w / 2.0f;
        mouse_y = window_h / 2.0f;
    }
    mouse_x = SDL_clamp(mouse_x + dx, 0, window_w - 1);
    mouse_y = SDL_clamp(mouse_y + dy, 0, window_h - 1);
    SDL_zero(ev);
    ev.motion.type = SDL_EVENT_MOUSE_MOTION;
    ev.motion.timestamp = SDL_GetTicksNS();
    ev.motion.windowID = window_id();
    ev.motion.which = 1;
    ev.motion.x = mouse_x;
    ev.motion.y = mouse_y;
    ev.motion.xrel = (float)dx;
    ev.motion.yrel = (float)dy;
    SDL_PushEvent(&ev);
}

static void push_button(int x11_button, int down)
{
    SDL_Event ev;
    if (mouse_x < 0) {
        mouse_x = window_w / 2.0f;
        mouse_y = window_h / 2.0f;
    }
    SDL_zero(ev);
    if (x11_button == 4 || x11_button == 5) { /* molette : evenement a l'appui seulement */
        if (!down) return;
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
    }
    SDL_PushEvent(&ev);
}

static void handle(Display *dpy, uint8_t type, uint16_t code, int32_t value)
{
    switch (type) {
    case MSG_KEY: {
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
            logmsg("utbridge: entrees connectees (port %d)\n", port);
            while (read_full(fd, msg, sizeof(msg)) == 0) {
                uint16_t code = (uint16_t)(msg[2] | (msg[3] << 8));
                int32_t value = (int32_t)((uint32_t)msg[4] | ((uint32_t)msg[5] << 8) |
                                          ((uint32_t)msg[6] << 16) | ((uint32_t)msg[7] << 24));
                handle(dpy, msg[0], code, value);
            }
            logmsg("utbridge: entrees deconnectees\n");
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
    if (window != game_window) {
        game_window = window;
        if (SDL_GetWindowSize(window, &w, &h) && w > 0 && h > 0) {
            window_w = w;
            window_h = h;
        }
    }
    pthread_mutex_lock(&pause_lock);
    while (paused) pthread_cond_wait(&pause_cond, &pause_lock);
    pthread_mutex_unlock(&pause_lock);
    return ret;
}
