/*
 * utbridge - charge via LD_PRELOAD dans le moteur UT2004 (runtime Linux de l'appli Android).
 *
 * - Entrees : recoit de l'appli des evenements clavier/souris sur une socket TCP locale
 *   (port UT_INPUT_PORT) et les injecte dans l'ecran X virtuel (Xvfb) avec l'extension
 *   XTest. Le moteur les recoit comme un vrai clavier et une vraie souris, en jeu comme
 *   dans les menus (la manette est convertie en clavier/souris cote appli).
 * - Pause : quand l'appli passe en arriere-plan, la boucle du jeu est bloquee a la fin de
 *   l'image en cours (SDL_GL_SwapWindow).
 * - Journal : stderr et, si UT_LOG_DIR est defini, <UT_LOG_DIR>/utbridge.log.
 *
 * Messages de 8 octets, little-endian : uint8 type, uint8 reserve, uint16 code, int32 valeur
 *   type 1 = touche   (code = keysym X11, valeur 1 appuyee / 0 relachee)
 *   type 2 = bouton   (code = bouton souris X11 1..5, valeur 1/0)
 *   type 3 = souris   (valeur = dx sur les 16 bits bas, dy sur les 16 bits hauts, signes)
 *   type 4 = pause    (valeur 1 pause / 0 reprise)
 */
#define _GNU_SOURCE
#include <X11/Xlib.h>
#include <X11/extensions/XTest.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
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

static void handle(Display *dpy, uint8_t type, uint16_t code, int32_t value)
{
    static int count;
    if (count < 50 && type != MSG_MOTION) {
        count++;
        logmsg("utbridge: evenement type=%u code=0x%x valeur=%d\n", type, code, value);
    }
    switch (type) {
    case MSG_KEY: {
        KeyCode kc = XKeysymToKeycode(dpy, (KeySym)code);
        if (kc) XTestFakeKeyEvent(dpy, kc, value ? True : False, CurrentTime);
        else logmsg("utbridge: keysym 0x%x sans keycode\n", code);
        break;
    }
    case MSG_BUTTON:
        XTestFakeButtonEvent(dpy, code, value ? True : False, CurrentTime);
        break;
    case MSG_MOTION:
        XTestFakeRelativeMotionEvent(dpy, (int16_t)(value & 0xffff), (int16_t)((uint32_t)value >> 16), CurrentTime);
        break;
    case MSG_PAUSE:
        set_paused(value);
        return;
    }
    XFlush(dpy);
}

static void *input_thread(void *arg)
{
    const char *port_env = getenv("UT_INPUT_PORT");
    int port = port_env ? atoi(port_env) : 0;
    Display *dpy = NULL;
    int major, minor, event, error;
    (void)arg;
    if (port <= 0) return NULL;

    /* Xvfb est demarre avant le moteur, mais on reessaie au cas ou */
    for (int i = 0; i < 100 && !(dpy = XOpenDisplay(NULL)); i++) usleep(100 * 1000);
    if (!dpy) {
        logmsg("utbridge: ecran X introuvable (DISPLAY=%s)\n", getenv("DISPLAY") ? getenv("DISPLAY") : "");
        return NULL;
    }
    if (!XTestQueryExtension(dpy, &event, &error, &major, &minor)) {
        logmsg("utbridge: extension XTest absente\n");
    } else {
        logmsg("utbridge: XTest %d.%d\n", major, minor);
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

__attribute__((constructor)) static void utbridge_init(void)
{
    char exe[4096];
    pthread_t thread;
    const char *dir = getenv("UT_LOG_DIR");
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) return;
    exe[n] = '\0';
    if (!strstr(exe, "ut2004-bin")) return; /* uniquement le moteur (pas Xvfb, sh...) */
    if (dir) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/utbridge.log", dir);
        logf = fopen(path, "w");
    }
    logmsg("utbridge: charge dans %s (port %s)\n", exe, getenv("UT_INPUT_PORT") ? getenv("UT_INPUT_PORT") : "?");
    XInitThreads();
    if (pthread_create(&thread, NULL, input_thread, NULL) == 0) pthread_detach(thread);
}

/* SDL3 : bool SDL_GL_SwapWindow(SDL_Window *) */
bool SDL_GL_SwapWindow(void *window)
{
    static bool (*real_swap)(void *);
    bool ret;
    if (!real_swap) real_swap = (bool (*)(void *))dlsym(RTLD_NEXT, "SDL_GL_SwapWindow");
    ret = real_swap(window);
    pthread_mutex_lock(&pause_lock);
    while (paused) pthread_cond_wait(&pause_cond, &pause_lock);
    pthread_mutex_unlock(&pause_lock);
    return ret;
}
