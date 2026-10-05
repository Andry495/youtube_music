#ifndef YOUTUBE_VOICE_MAIN_H
#define YOUTUBE_VOICE_MAIN_H

/* Upstream ByeDPI exposes these from main.c / proxy.c */
int parse_args(int argc, char **argv);
void clear_params(char *line, char **argv);

#endif
