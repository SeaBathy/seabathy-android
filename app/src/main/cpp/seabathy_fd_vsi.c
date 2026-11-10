/*
 * A tiny read-only GDAL virtual file system, "/vsiseabathyfd/", that reads a
 * file straight from an already-open file descriptor.
 *
 * Why: the app has no storage permission (Google Play restricts it). A survey
 * picked with the system file picker comes as a document the app can open and
 * read, but not reopen by its path, and GDAL only opens files by path. Java
 * keeps the picked document open (DescriptorPaths.kt) and hands GDAL the path
 * "/vsiseabathyfd/<fd>/survey.tif"; this plugin serves GDAL's reads from that
 * descriptor with pread(), so multi-gigabyte surveys are read in place, never
 * copied.
 *
 * GDAL is reached at runtime with dlsym (it is already loaded by its Java
 * bindings), so this builds without GDAL's headers. The callback struct below
 * is GDAL 3.7's VSIFilesystemPluginCallbacksStruct (port/cpl_vsi.h); it is
 * allocated by GDAL itself (VSIAllocFilesystemPluginCallbacksStruct), so only
 * the fields up to the ones set here have to match.
 */
#define _FILE_OFFSET_BITS 64
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef unsigned long long vsi_l_offset;
typedef struct stat64 VSIStatBufL;

typedef struct {
    void *pUserData;
    int (*stat)(void *, const char *, VSIStatBufL *, int);
    int (*unlink)(void *, const char *);
    int (*rename)(void *, const char *, const char *);
    int (*mkdir)(void *, const char *, long);
    int (*rmdir)(void *, const char *);
    char **(*read_dir)(void *, const char *, int);
    void *(*open)(void *, const char *, const char *);
    vsi_l_offset (*tell)(void *);
    int (*seek)(void *, vsi_l_offset, int);
    size_t (*read)(void *, void *, size_t, size_t);
    int (*read_multi_range)(void *, int, void **, const vsi_l_offset *, const size_t *);
    int (*get_range_status)(void *, vsi_l_offset, vsi_l_offset);
    int (*eof)(void *);
    size_t (*write)(void *, const void *, size_t, size_t);
    int (*flush)(void *);
    int (*truncate)(void *, vsi_l_offset);
    int (*close)(void *);
    size_t nBufferSize;
    size_t nCacheSize;
    /* later members (sibling_files, advise_read, ...) are left as GDAL allocated them */
} SeaBathyPluginCallbacks;

#define PREFIX "/vsiseabathyfd/"
#define FILE_NAME "survey.tif"

typedef struct {
    int fd;
    vsi_l_offset pos;
    vsi_l_offset size;
    int at_eof;
} Handle;

/* "<fd>/survey.tif" (GDAL passes the path with the prefix stripped; the full
 * "/vsiseabathyfd/<fd>/survey.tif" is accepted too) -> fd, or -1 for anything
 * else (GDAL also asks about sidecars such as survey.tif.aux.xml, which don't
 * exist here). */
static int parse_fd(const char *path) {
    if (!path) return -1;
    size_t prefix = strlen(PREFIX);
    const char *p = strncmp(path, PREFIX, prefix) == 0 ? path + prefix : path;
    char *end = NULL;
    errno = 0;
    long fd = strtol(p, &end, 10);
    if (errno || end == p || *end != '/' || fd < 0 || fd > 1000000) return -1;
    if (strcmp(end + 1, FILE_NAME) != 0) return -1;
    return (int) fd;
}

static int fd_size(int fd, vsi_l_offset *size) {
    struct stat64 st;
    if (fstat64(fd, &st) != 0) return -1;
    *size = (vsi_l_offset) st.st_size;
    return 0;
}

static int cb_stat(void *user, const char *path, VSIStatBufL *buf, int flags) {
    (void) user; (void) flags;
    int fd = parse_fd(path);
    if (fd < 0) return -1;
    vsi_l_offset size;
    if (fd_size(fd, &size) != 0) return -1;
    memset(buf, 0, sizeof(*buf));
    buf->st_size = (off64_t) size;
    buf->st_mode = S_IFREG | 0444;
    return 0;
}

static void *cb_open(void *user, const char *path, const char *access) {
    (void) user;
    if (!access || strchr(access, 'w') || strchr(access, 'a') || strchr(access, '+')) return NULL;
    int fd = parse_fd(path);
    if (fd < 0) return NULL;
    Handle *h = calloc(1, sizeof(Handle));
    if (!h) return NULL;
    h->fd = fd;
    if (fd_size(fd, &h->size) != 0) { free(h); return NULL; }
    return h;
}

static vsi_l_offset cb_tell(void *file) { return ((Handle *) file)->pos; }

static int cb_seek(void *file, vsi_l_offset offset, int whence) {
    Handle *h = file;
    if (whence == SEEK_SET) h->pos = offset;
    else if (whence == SEEK_CUR) h->pos += offset;
    else if (whence == SEEK_END) h->pos = h->size + offset;
    else return -1;
    h->at_eof = 0;
    return 0;
}

static size_t cb_read(void *file, void *buffer, size_t size, size_t count) {
    Handle *h = file;
    if (size == 0 || count == 0) return 0;
    size_t wanted = size * count, done = 0;
    while (done < wanted) {
        ssize_t n = pread64(h->fd, (char *) buffer + done, wanted - done, (off64_t) (h->pos + done));
        if (n < 0) { if (errno == EINTR) continue; break; }
        if (n == 0) break;
        done += (size_t) n;
    }
    h->pos += done;
    if (done < wanted) h->at_eof = 1;
    return done / size;
}

static int cb_eof(void *file) { return ((Handle *) file)->at_eof; }

static int cb_close(void *file) { free(file); return 0; }   /* the descriptor belongs to Java */

typedef SeaBathyPluginCallbacks *(*AllocFn)(void);
typedef int (*InstallFn)(const char *, const SeaBathyPluginCallbacks *);
typedef void (*FreeFn)(SeaBathyPluginCallbacks *);

static int installed = 0;

JNIEXPORT jboolean JNICALL
Java_au_opendepth_app_gdal_FdFileSystem_nativeInstall(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    if (installed) return JNI_TRUE;
    void *gdal = dlopen("libgdal.so", RTLD_NOW | RTLD_NOLOAD);
    if (!gdal) gdal = dlopen("libgdal.so", RTLD_NOW);
    if (!gdal) return JNI_FALSE;
    AllocFn alloc = (AllocFn) dlsym(gdal, "VSIAllocFilesystemPluginCallbacksStruct");
    InstallFn install = (InstallFn) dlsym(gdal, "VSIInstallPluginHandler");
    FreeFn release = (FreeFn) dlsym(gdal, "VSIFreeFilesystemPluginCallbacksStruct");
    if (!alloc || !install) return JNI_FALSE;
    SeaBathyPluginCallbacks *cb = alloc();
    if (!cb) return JNI_FALSE;
    cb->stat = cb_stat;
    cb->open = cb_open;
    cb->tell = cb_tell;
    cb->seek = cb_seek;
    cb->read = cb_read;
    cb->eof = cb_eof;
    cb->close = cb_close;
    cb->nBufferSize = 64 * 1024;           /* GDAL read-ahead: fewer small preads */
    cb->nCacheSize = 16 * 1024 * 1024;
    int rc = install(PREFIX, cb);          /* GDAL copies the struct */
    if (release) release(cb);
    installed = (rc == 0);
    return installed ? JNI_TRUE : JNI_FALSE;
}
