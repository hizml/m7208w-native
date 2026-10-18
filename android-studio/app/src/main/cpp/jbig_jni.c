#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include "jbig.h"

typedef struct { unsigned char *d; size_t n, cap; } buf;
static void out_cb(unsigned char *start, size_t len, void *priv) {
    buf *b = priv;
    if (b->n + len > b->cap) { b->cap = (b->n + len) * 2; b->d = realloc(b->d, b->cap); }
    memcpy(b->d + b->n, start, len); b->n += len;
}

/* bitmap: 5120宽(640B/行)×h行的1bpp位图 → JBIG BIE字节 */
JNIEXPORT jbyteArray JNICALL
Java_com_zml_m7208w_PrintEngine_jbigEncode(JNIEnv *env, jobject thiz, jbyteArray bitmap, jint h) {
    (void)thiz;
    jsize n = (*env)->GetArrayLength(env, bitmap);
    unsigned char *rows = (unsigned char*)(*env)->GetByteArrayElements(env, bitmap, NULL);
    buf b = {0};
    struct jbg_enc_state s;
    unsigned char *rp[1] = { rows };
    jbg_enc_init(&s, 5120, h, 1, rp, out_cb, &b);
    jbg_enc_layers(&s, 0);
    jbg_enc_options(&s, 0, 0x340, 128, 8, 0);  /* MX=8: 固件1.3.00实证配方(t5) */
    jbg_enc_out(&s);
    jbg_enc_free(&s);
    (*env)->ReleaseByteArrayElements(env, bitmap, rows, JNI_ABORT);
    jbyteArray ret = (*env)->NewByteArray(env, b.n);
    (*env)->SetByteArrayRegion(env, ret, 0, b.n, (const jbyte*)b.d);
    free(b.d);
    return ret;
}
