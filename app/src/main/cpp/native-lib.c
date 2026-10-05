#include <string.h>
#include <stdlib.h>
#include <jni.h>
#include <sys/socket.h>

#include "byedpi/error.h"
#include "byedpi/proxy.h"
#include "byedpi/params.h"
#include "main.h"
#include "utils.h"

/* parse_args stores optarg pointers into params; keep argv alive until stop. */
static char **g_argv = NULL;
static int g_argc = 0;

static void free_argv(void) {
    if (!g_argv) return;
    for (int i = 0; i < g_argc; i++) {
        free(g_argv[i]);
    }
    free(g_argv);
    g_argv = NULL;
    g_argc = 0;
}

JNIEXPORT jint JNI_OnLoad(
        JavaVM *vm,
        __attribute__((unused)) void *reserved) {
    extern void dpi_protect_set_vm(JavaVM *vm);
    dpi_protect_set_vm(vm);
    default_params = params;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_youtubevoice_app_dpi_ByeDpiNative_jniCreateSocketWithCommandLine(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jobjectArray args) {
    free_argv();
    reset_params();

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t)argc, sizeof(char *));
    if (!argv) return -1;

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        if (!argv[i]) {
            for (int j = 0; j < i; j++) free(argv[j]);
            free(argv);
            return -1;
        }
    }

    int res = parse_args(argc, argv);
    if (res < 0) {
        uniperror("parse_args");
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        return -1;
    }

    g_argv = argv;
    g_argc = argc;

    int fd = listen_socket(&params.laddr);
    if (fd < 0) {
        uniperror("listen_socket");
        free_argv();
        reset_params();
        return -1;
    }
    LOG(LOG_S, "listen_socket, fd: %d", fd);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_youtubevoice_app_dpi_ByeDpiNative_jniStartProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "start_proxy, fd: %d", fd);
    if (start_event_loop(fd) < 0) {
        uniperror("start_event_loop");
        return get_e();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_youtubevoice_app_dpi_ByeDpiNative_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "stop_proxy, fd: %d", fd);
    int res = shutdown(fd, SHUT_RDWR);
    reset_params();
    free_argv();
    if (res < 0) {
        uniperror("shutdown");
        return get_e();
    }
    return 0;
}
