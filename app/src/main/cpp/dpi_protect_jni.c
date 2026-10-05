#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

#define LOG_TAG "DpiProtectNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = NULL;
static jobject g_service = NULL;
static jmethodID g_protect_mid = NULL;
static volatile int g_running = 0;
static int g_server_fd = -1;
static pthread_t g_thread;

static int call_protect(JNIEnv *env, int fd)
{
    if (!g_service || !g_protect_mid) return 0;
    return (*env)->CallBooleanMethod(env, g_service, g_protect_mid, fd) ? 1 : 0;
}

static void handle_client(JNIEnv *env, int client_fd)
{
    char ctrl[CMSG_SPACE(sizeof(int))];
    char data = 0;
    struct iovec iov = { .iov_base = &data, .iov_len = 1 };
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl;
    msg.msg_controllen = sizeof(ctrl);

    ssize_t n = recvmsg(client_fd, &msg, 0);
    if (n < 1) {
        LOGE("recvmsg failed: %s", strerror(errno));
        close(client_fd);
        return;
    }

    int ok = 0;
    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL;
         cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS) {
            int passed_fd = *((int *)CMSG_DATA(cmsg));
            ok = call_protect(env, passed_fd);
            LOGI("protect fd=%d -> %s", passed_fd, ok ? "ok" : "FAIL");
            close(passed_fd);
            break;
        }
    }

    if (ok) {
        char zero = 0;
        (void)send(client_fd, &zero, 1, 0);
    }
    close(client_fd);
}

static void *protect_thread(void *arg)
{
    (void)arg;
    JNIEnv *env = NULL;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
        LOGE("AttachCurrentThread failed");
        return NULL;
    }

    while (g_running) {
        int client = accept(g_server_fd, NULL, NULL);
        if (client < 0) {
            if (!g_running) break;
            if (errno == EINTR) continue;
            LOGE("accept failed: %s", strerror(errno));
            break;
        }
        handle_client(env, client);
    }

    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_youtubevoice_app_dpi_DpiProtectNative_nativeStart(
        JNIEnv *env, jclass clazz, jobject service, jstring path_js)
{
    (void)clazz;
    if (!g_vm) {
        (*env)->GetJavaVM(env, &g_vm);
    }
    if (g_running) return JNI_TRUE;

    const char *path = (*env)->GetStringUTFChars(env, path_js, NULL);
    if (!path) return JNI_FALSE;

    unlink(path);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path_js, path);
        return JNI_FALSE;
    }

    struct sockaddr_un sa;
    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    strncpy(sa.sun_path, path, sizeof(sa.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
        LOGE("bind(%s): %s", path, strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path_js, path);
        return JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, path_js, path);

    if (listen(fd, 64) < 0) {
        LOGE("listen: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    jclass svc_cls = (*env)->GetObjectClass(env, service);
    jmethodID mid = (*env)->GetMethodID(env, svc_cls, "protect", "(I)Z");
    if (!mid) {
        LOGE("VpnService.protect(I)Z not found");
        close(fd);
        return JNI_FALSE;
    }

    if (g_service) (*env)->DeleteGlobalRef(env, g_service);
    g_service = (*env)->NewGlobalRef(env, service);
    g_protect_mid = mid;
    g_server_fd = fd;
    g_running = 1;

    if (pthread_create(&g_thread, NULL, protect_thread, NULL) != 0) {
        LOGE("pthread_create failed");
        g_running = 0;
        close(fd);
        g_server_fd = -1;
        return JNI_FALSE;
    }

    LOGI("protect server listening");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_youtubevoice_app_dpi_DpiProtectNative_nativeStop(
        JNIEnv *env, jclass clazz)
{
    (void)clazz;
    if (!g_running) return;
    g_running = 0;
    if (g_server_fd >= 0) {
        shutdown(g_server_fd, SHUT_RDWR);
        close(g_server_fd);
        g_server_fd = -1;
    }
    pthread_join(g_thread, NULL);
    if (g_service) {
        (*env)->DeleteGlobalRef(env, g_service);
        g_service = NULL;
    }
    g_protect_mid = NULL;
    LOGI("protect server stopped");
}

JNIEXPORT jint JNICALL JNI_OnLoad_dpi_protect(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* Called from byedpi's existing JNI_OnLoad chain if linked into same .so —
 * else register via System.loadLibrary("byedpi") side — we put symbols in byedpi.so
 * and store VM from first nativeStart call. */
void dpi_protect_set_vm(JavaVM *vm)
{
    g_vm = vm;
}
