/* Run on Linux with SDL3 development libraries (no display/device required):
 * gcc -O2 -Wall -Wextra -Werror -o /tmp/bridge-test bridge-test.c
 *     $(pkg-config --cflags --libs sdl3) -lX11 -lXtst -ldl -lpthread
 * SDL_VIDEODRIVER=dummy /tmp/bridge-test
 */
#include "../utbridge/utbridge.c"
#include <assert.h>
int main(void) {
    assert(SDL_Init(SDL_INIT_VIDEO));
    game_window = SDL_CreateWindow("bridge test", 1280, 720, SDL_WINDOW_HIDDEN);
    assert(game_window);
    int channel[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, channel) == 0);
    output_fd = channel[0];
    cursor_visible = 1;
    uint8_t mode[8];
    cursor_mode(0);
    assert(read_full(channel[1], mode, 8) == 0 && mode[0] == MSG_MODE && mode[4] == 0);
    cursor_mode(1);
    assert(read_full(channel[1], mode, 8) == 0 && mode[0] == MSG_MODE && mode[4] == 1);
    output_fd = -1;
    close(channel[0]); close(channel[1]);
    SDL_Event ev;
    while (SDL_PollEvent(&ev)) {}
    push_motion(30, -20);
    float x, y;
    SDL_GetMouseState(&x, &y);
    assert(x == 670 && y == 340);
    SDL_GetRelativeMouseState(&x, &y);
    assert(x == 30 && y == -20);
    SDL_GetRelativeMouseState(&x, &y);
    assert(x == 0 && y == 0);
    push_button(1, 1);
    assert(SDL_GetMouseState(&x, &y) & SDL_BUTTON_LMASK);
    push_button(1, 0);
    assert(!(SDL_GetMouseState(&x, &y) & SDL_BUTTON_LMASK));
    assert(push_navigation_key(0xff52, 1));
    assert(push_navigation_key(0xff52, 0));
    assert(push_navigation_key(0xfe20, 1));
    assert(push_navigation_key(0xfe20, 0));
    handle(NULL, MSG_POSITION, 0, 100 | (200 << 16));
    SDL_GetMouseState(&x, &y);
    assert(x == 100 && y == 200);
    handle(NULL, MSG_TEXT, 0, 0xe9);
    int motion = 0, buttons = 0, keys = 0, text = 0, backTab = 0;
    while (SDL_PollEvent(&ev)) {
        if (ev.type == SDL_EVENT_MOUSE_MOTION) motion++;
        if (ev.type == SDL_EVENT_MOUSE_BUTTON_DOWN || ev.type == SDL_EVENT_MOUSE_BUTTON_UP) buttons++;
        if (ev.type == SDL_EVENT_KEY_DOWN || ev.type == SDL_EVENT_KEY_UP) {
            keys++;
            if (ev.key.key == SDLK_TAB) { assert(ev.key.mod & SDL_KMOD_LSHIFT); backTab++; }
        }
        if (ev.type == SDL_EVENT_TEXT_INPUT) { assert(strcmp(ev.text.text, "\xc3\xa9") == 0); text++; }
    }
    assert(motion == 2 && buttons == 2 && keys == 4 && text == 1 && backTab == 2);
    puts("PASS: motion, state, relative reset, buttons, arrows, Shift+Tab, touch, UTF-8 text");
    SDL_DestroyWindow(game_window);
    SDL_Quit();
    return 0;
}
