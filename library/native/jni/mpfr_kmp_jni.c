/* mpfr_kmp_jni — JNI shim for io.github.thekekt.mpfr.
 *
 * Hand-written plumbing; every MPFR dispatch is generated:
 *   mpfr_kmp_ops.h (from buildSrc ShimTables.kt via `:library:generateShim`)
 * carries the X-macro op tables + citations, and is the single home of the
 * `mpfr_*` call expressions and the format-style letters.
 *
 * Canonical Repr format: LE i32/i64/i16 fields (LE host
 * only, checked below), mantissa bytes shared with GMP import/export as
 * (order, size, endianness) = (1, 1, 1) on BOTH directions (byte-compat).
 *
 * Exception-flag isolation: op regions are serialized by one process
 * mutex and run save -> clear -> compute -> restore(0-clear), so per-op deltas
 * are discarded and no thread observes another's flags even on non-TLS builds;
 * `mpfr_buildopt_tls_p()` is exported and asserted from the Kotlin tests.
 *
 * Parse status mapping (see strtofr below): MPFR 4.2.1 returns the
 * usual ternary; validity/`endptr` are translated here into the wrapper's
 * status codes (0 ok, -1 malformed, -2 bad base (pre-checked), -3 exponent-
 * marker garbage); the deviation from raw MPFR status is deliberate.
 */
#include <jni.h>
#include <stdint.h> /* intmax_t/uintmax_t before mpfr.h (mpfr.h L195-210) — enables the _sj/_uj block */
#include <mpfr.h>
#include <gmp.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "mpfr_kmp_ops.h"

#ifndef _MPFR_H_HAVE_INTMAX_T
#error "this shim requires the vendored MPFR built with intmax_t support"
#endif

#if defined(__BYTE_ORDER__) && defined(__ORDER_BIG_ENDIAN__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#error "mpfr_kmp requires a little-endian host for the canonical Repr header"
#endif

#define KIND_ZERO 0
#define KIND_NAN 1
#define KIND_INF 2
#define KIND_REG 3
#define HDR 16

static pthread_mutex_t kmp_lock = PTHREAD_MUTEX_INITIALIZER;

/* one serialized region: flags snapshot, op body, flags restore. Use with a
   block: KMP_REGION_BEGIN { ... } KMP_REGION_END; */
#define KMP_REGION_BEGIN()                        \
    do {                                          \
        pthread_mutex_lock(&kmp_lock);            \
    } while (0)

#define KMP_FLAGS_SAVED_DECL() unsigned int kmp_saved_ = mpfr_flags_save(); mpfr_clear_flags()

#define KMP_REGION_END()                          \
    do {                                          \
        /* restore every flag bit to its pre-op state (manual 3919-3922:
           second arg = mask of bits to restore — must be ALL here);
           the op's delta, if any, is thereby discarded. */ \
        mpfr_flags_restore(kmp_saved_, MPFR_FLAGS_ALL); \
        pthread_mutex_unlock(&kmp_lock);          \
    } while (0)

static void r32(const uint8_t* p, int32_t* o) {
    *o = (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}
static void r64(const uint8_t* p, int64_t* o) {
    uint64_t lo = (uint64_t)p[0] | ((uint64_t)p[1] << 8) | ((uint64_t)p[2] << 16) | ((uint64_t)p[3] << 24);
    uint64_t hi = (uint64_t)p[4] | ((uint64_t)p[5] << 8) | ((uint64_t)p[6] << 16) | ((uint64_t)p[7] << 24);
    *o = (int64_t)((hi << 32) | lo);
}
static void w32(uint8_t* p, int32_t v) {
    uint64_t u = (uint32_t)v;
    p[0] = (uint8_t)u; p[1] = (uint8_t)(u >> 8); p[2] = (uint8_t)(u >> 16); p[3] = (uint8_t)(u >> 24);
}
static void w64(uint8_t* p, int64_t v) {
    w32(p, (int32_t)(uint32_t)v);
    w32(p + 4, (int32_t)(uint32_t)((uint64_t)v >> 32));
}
static void w16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static uint16_t rd16(const uint8_t* p) { return (uint16_t)((uint16_t)p[14] | ((uint16_t)p[15] << 8)); }
static int8_t rd_sign(const uint8_t* p) { return p[1] ? -1 : 1; }

/* returns 0 on canonical input, or -1 with a pending Java exception */
static int kmp_validate(JNIEnv* env, const jbyte* p, jsize n) {
    uint16_t mlen;
    int32_t pr;
    int i;
    if (p == NULL || n < HDR) return -1;
    if (p[0] < KIND_ZERO || p[0] > KIND_REG) goto bad;
    r32((const uint8_t*)p + 2, &pr);
    if (pr < 1 || pr > 2147483646) goto bad; /* MpfrPrecision v1 domain INT_MAX-1 */
    mlen = rd16((const uint8_t*)p);
    if ((jsize)HDR + (jsize)mlen != n) goto bad;
    if (p[0] != KIND_REG) {
        for (i = 6; i < HDR; i++) if (p[i] != 0) goto bad; /* exp/len zero for specials */
    }
    if (p[0] == KIND_NAN && p[1] != 0) goto bad; /* NaN carries no sign */
    return 0;
bad:
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
                     "non-canonical Repr byte array reached the mpfr_kmp shim");
    return -1;
}

/* load + validate one jbyteArray (NULL result leaves any pending exception) */
static jbyte* kmp_load(JNIEnv* env, jbyteArray arr, jsize* pn) {
    jbyte* p;
    if (arr == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "null Repr");
        return NULL;
    }
    p = (jbyte*)(*env)->GetByteArrayElements(env, arr, NULL);
    if (p == NULL) return NULL; /* OOM pending */
    *pn = (*env)->GetArrayLength(env, arr);
    if (kmp_validate(env, p, *pn) < 0) {
        (*env)->ReleaseByteArrayElements(env, arr, p, JNI_ABORT);
        return NULL;
    }
    return p;
}
static void kmp_unload(JNIEnv* env, jbyteArray arr, jbyte* p) {
    if (p) (*env)->ReleaseByteArrayElements(env, arr, p, JNI_ABORT);
}

/* initialize + decode (v un-initialized in; header prec drives init2) */
static void kmp_init_decode(mpfr_t v, const jbyte* p) {
    int32_t pr;
    uint8_t kind = (uint8_t)p[0];
    r32((const uint8_t*)p + 2, &pr);
    mpfr_init2(v, (mpfr_prec_t)pr);
    if (kind == KIND_REG) {
        mpz_t z;
        int64_t exp;
        r64((const uint8_t*)p + 6, &exp);
        mpz_init(z);
        mpz_import(z, (size_t)rd16((const uint8_t*)p), 1, 1, 1, 0, (const uint8_t*)p + HDR);
        if (p[1]) mpz_neg(z, z); /* the sign byte carries the value sign; import is magnitude-only */
        mpfr_set_z_2exp(v, z, (mpfr_exp_t)exp, MPFR_RNDN);
        mpz_clear(z);
    } else if (kind == KIND_ZERO) mpfr_set_zero(v, (int)rd_sign(p));
    else if (kind == KIND_INF) mpfr_set_inf(v, (int)rd_sign(p));
    else mpfr_set_nan(v);
}

static jbyteArray kmp_encode(JNIEnv* env, mpfr_srcptr v) {
    uint8_t kind;
    uint8_t sign = (uint8_t)(mpfr_signbit(v) ? 1 : 0);
    size_t cnt = 0;
    int64_t exp = 0;
    uint8_t* buf = NULL;
    jbyteArray out;
    jbyte* jbuf;

    if (mpfr_nan_p(v)) { kind = KIND_NAN; sign = 0; }
    else if (mpfr_inf_p(v)) kind = KIND_INF;
    else if (mpfr_zero_p(v)) kind = KIND_ZERO;
    else {
        /* canonical form: the exported mantissa is LEFT-ALIGNED inside its
           byte array, so byte[0] always carries the MSB. mpz_export(size=1)
           pads the top byte with leading zeros (bits not a multiple of 8),
           so we shift the significand up and compensate the exponent:
           x = m · 2^e == (m << pad) · 2^(e - pad). */
        mpz_t z;
        mpfr_exp_t e;
        size_t bitlen = 0, probe = 0, cntl = 0;
        size_t pad;
        kind = KIND_REG;
        mpz_init(z);
        e = mpfr_get_z_2exp(z, v); /* z pre-initialized; body uses mpz_realloc2 (src/get_z_2exp.c) */
        bitlen = mpz_sizeinbase(z, 2); /* = mpfr prec for a normalized MPFR significand */
        cntl = (bitlen + 7) / 8;
        pad = cntl * 8 - bitlen;
        if (pad > 0) mpz_mul_2exp(z, z, (unsigned long)pad); /* align in place; cleared below */
        if (cntl > 0xFFFF) {
            mpz_clear(z);
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/InternalError"), "mantissa exceeds i16 length");
            return NULL;
        }
        if (cntl > 0) {
            buf = (uint8_t*)malloc(cntl);
            if (buf == NULL) {
                mpz_clear(z);
                (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "repr encode scratch");
                return NULL;
            }
            probe = 0;
            mpz_export(buf, &probe, 1, 1, 1, 0, z);
            cnt = probe;
        }
        exp = (int64_t)e - (int64_t)pad;
        mpz_clear(z);
    }

    if (cnt > 0xFFFFu) {
        free(buf);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                         "mantissa exceeds the v1 i16 length field");
        return NULL;
    }
    out = (*env)->NewByteArray(env, (jsize)(HDR + cnt));
    if (out == NULL) { free(buf); return NULL; }
    jbuf = (jbyte*)(*env)->GetByteArrayElements(env, out, NULL);
    if (jbuf == NULL) { free(buf); return NULL; }
    jbuf[0] = (jbyte)kind;
    jbuf[1] = (jbyte)sign;
    w32((uint8_t*)jbuf + 2, (int32_t)mpfr_get_prec(v));
    w64((uint8_t*)jbuf + 6, exp);
    w16((uint8_t*)jbuf + 14, (uint16_t)cnt);
    if (cnt) memcpy(jbuf + HDR, buf, cnt);
    (*env)->ReleaseByteArrayElements(env, out, jbuf, 0);
    free(buf);
    return out;
}

/* ------------------------------------------------------------ op families -- */

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_binaryOp(
    JNIEnv* env, jobject self, jbyteArray a, jbyteArray b, jint prec, jint rnd, jint op) {
    jsize na = 0, nb = 0;
    jbyte* pa;
    jbyte* pb;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    pb = kmp_load(env, b, &nb);
    if (!pb) { kmp_unload(env, a, pa); return NULL; }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, y, rop;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        kmp_init_decode(y, pb);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_BINARY_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown binary opcode");
        mpfr_clear(rop);
        mpfr_clear(x);
        mpfr_clear(y);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    kmp_unload(env, b, pb);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_binary3Op(
    JNIEnv* env, jobject self, jbyteArray a, jbyteArray b, jbyteArray c, jint prec, jint rnd, jint op) {
    jsize na = 0, nb = 0, nc = 0;
    jbyte *pa = NULL, *pb = NULL, *pc = NULL;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) goto done;
    pb = kmp_load(env, b, &nb);
    if (!pb) goto done;
    pc = kmp_load(env, c, &nc);
    if (!pc) goto done;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, y, z, rop;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        kmp_init_decode(y, pb);
        kmp_init_decode(z, pc);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_FUSED_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown fused opcode");
        mpfr_clear(rop);
        mpfr_clear(x);
        mpfr_clear(y);
        mpfr_clear(z);
    }
    KMP_REGION_END();
done:
    kmp_unload(env, a, pa);
    kmp_unload(env, b, pb);
    kmp_unload(env, c, pc);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_unaryOp(
    JNIEnv* env, jobject self, jbyteArray a, jint prec, jint rnd, jint op) {
    jsize na = 0;
    jbyte* pa;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, rop;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        /* NEXTUP/NEXTDOWN and the integer-rounding cases act on rop — x is
           pre-copied here (mpfr_set at matching precision is exact). */
        mpfr_set(rop, x, MPFR_RNDN);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_UNARY_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown unary opcode");
        mpfr_clear(rop);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_scalarOp(
    JNIEnv* env, jobject self, jbyteArray a, jlong k, jint prec, jint rnd, jint op) {
    jsize na = 0;
    jbyte* pa;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        /* scalar crossing: exact intmax scratch (prec 65 covers 64-bit uintmax),
           then the binary call uses it as an operand — single rounding to `prec`. */
        mpfr_t x, rop, tmp;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_init2(tmp, 65);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_SCALAR_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown scalar opcode");
        mpfr_clear(tmp);
        mpfr_clear(rop);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_doubleOp(
    JNIEnv* env, jobject self, jbyteArray a, jdouble d, jint prec, jint rnd, jint op) {
    jsize na = 0;
    jbyte* pa;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, rop, tmp;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_init2(tmp, 53); /* double significand exact at 53 */
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_DOUBLE_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown double opcode");
        mpfr_clear(tmp);
        mpfr_clear(rop);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

static jobjectArray kmp_wrap_pair(JNIEnv* env, mpfr_srcptr a, mpfr_srcptr b) {
    jobjectArray out = NULL;
    jbyteArray e1 = kmp_encode(env, a);
    jbyteArray e2 = e1 ? kmp_encode(env, b) : NULL;
    jclass ba = e2 ? (*env)->FindClass(env, "[B") : NULL;
    if (ba) {
        out = (*env)->NewObjectArray(env, 2, ba, NULL);
        if (out) {
            (*env)->SetObjectArrayElement(env, out, 0, e1);
            (*env)->SetObjectArrayElement(env, out, 1, e2);
        }
    }
    if (e1) (*env)->DeleteLocalRef(env, e1);
    if (e2) (*env)->DeleteLocalRef(env, e2);
    return out;
}

JNIEXPORT jobjectArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_pairOp(
    JNIEnv* env, jobject self, jbyteArray a, jint prec, jint rnd, jint op) {
    jsize na = 0;
    jbyte* pa;
    jobjectArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, rop, rop2;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        kmp_init_decode(x, pa);
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_init2(rop2, (mpfr_prec_t)prec);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_PAIR_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_wrap_pair(env, rop, rop2);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown pair opcode");
        mpfr_clear(rop2);
        mpfr_clear(rop);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_constOp(
    JNIEnv* env, jobject self, jint prec, jint rnd, jint op) {
    jbyteArray out = NULL;
    (void)self;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop, tmp;
        mpfr_rnd_t r = (mpfr_rnd_t)rnd;
        int badop = 0;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_init2(tmp, 53);
        switch (op) {
#define KMP_CASE(name, id, call) case id: call; break;
            KMP_CONST_OPS(KMP_CASE)
#undef KMP_CASE
            default: badop = 1; break;
        }
        if (!badop) out = kmp_encode(env, rop);
        else (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "unknown const opcode");
        mpfr_clear(tmp);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_factorialOp(
    JNIEnv* env, jobject self, jlong n, jint prec, jint rnd) {
    jbyteArray out;
    (void)self;
    (void)rnd; /* mpfr_fac_ui is exact */
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_fac_ui(rop, (unsigned long)n, MPFR_RNDN);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

/* returns *o_backing (mpfr_t array) + *o_tab (array of mpfr_ptr into it); mpfr_sum/mpfr_dot
   consume POINTER TABLES, not struct arrays — never cast the backing array directly. */
static int kmp_decode_list(JNIEnv* env, jobjectArray arr, mpfr_t** o_backing, mpfr_ptr** o_tab, jsize* o_n) {
    jsize n = (*env)->GetArrayLength(env, arr);
    mpfr_t* list = (mpfr_t*)malloc(sizeof(mpfr_t) * (size_t)(n > 0 ? n : 1));
    mpfr_ptr* tab;
    jsize i;
    *o_backing = NULL;
    *o_tab = NULL;
    *o_n = 0;
    if (list == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "mpfr list");
        return -1;
    }
    tab = (mpfr_ptr*)malloc(sizeof(mpfr_ptr) * (size_t)(n > 0 ? n : 1));
    if (tab == NULL) {
        free(list);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "mpfr list tab");
        return -1;
    }
    for (i = 0; i < n; i++) tab[i] = list[i]; /* mpfr_t decays to mpfr_ptr slot */
    for (i = 0; i < n; i++) mpfr_init2(list[i], MPFR_PREC_MIN); /* every slot valid for kmp_free_list */
    *o_backing = list;
    *o_tab = tab;
    *o_n = n;
    for (i = 0; i < n; i++) {
        jbyteArray el = (jbyteArray)(*env)->GetObjectArrayElement(env, arr, i);
        jbyte* p;
        jsize len = 0;
        if (el == NULL) {
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "null list element");
            return -1;
        }
        p = kmp_load(env, el, &len);
        if (p == NULL) return -1; /* validation exception pending */
        mpfr_clear(list[i]); /* drop the MIN_PREC init before kmp_init_decode re-inits at its width */
        kmp_init_decode(list[i], p);
        kmp_unload(env, el, p);
        (*env)->DeleteLocalRef(env, el);
    }
    return 0;
}

static void kmp_free_list(mpfr_t* backing, mpfr_ptr* tab, jsize n) {
    jsize i;
    if (backing != NULL) {
        for (i = 0; i < n; i++) mpfr_clear(backing[i]);
        free(backing);
    }
    free(tab);
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_sumOp(
    JNIEnv* env, jobject self, jobjectArray items, jint prec, jint rnd) {
    mpfr_t* backing = NULL;
    mpfr_ptr* tab = NULL;
    jsize n = 0;
    jbyteArray out = NULL;
    (void)self;
    if (kmp_decode_list(env, items, &backing, &tab, &n) < 0) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_sum(rop, (const mpfr_ptr *)tab, (unsigned long)n, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    kmp_free_list(backing, tab, n);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_dotOp(
    JNIEnv* env, jobject self, jobjectArray a, jobjectArray b, jint prec, jint rnd) {
    mpfr_t *ba = NULL, *bb = NULL;
    mpfr_ptr *ta = NULL, *tb = NULL;
    jsize na = 0, nb = 0;
    jbyteArray out = NULL;
    (void)self;
    if (kmp_decode_list(env, a, &ba, &ta, &na) < 0) return NULL;
    if (kmp_decode_list(env, b, &bb, &tb, &nb) < 0) {
        kmp_free_list(ba, ta, na);
        return NULL;
    }
    if (na != nb) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "dot: unequal list sizes");
        kmp_free_list(ba, ta, na);
        kmp_free_list(bb, tb, nb);
        return NULL;
    }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_dot(rop, (const mpfr_ptr *)ta, (const mpfr_ptr *)tb, (unsigned long)na, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    kmp_free_list(ba, ta, na);
    kmp_free_list(bb, tb, nb);
    return out;
}

JNIEXPORT jint JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_compareIntOp(
    JNIEnv* env, jobject self, jbyteArray a, jbyteArray b) {
    jsize na = 0, nb = 0;
    jbyte *pa = NULL, *pb = NULL;
    jint result = 0;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return 0;
    pb = kmp_load(env, b, &nb);
    if (!pb) { kmp_unload(env, a, pa); return 0; }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, y;
        int c;
        kmp_init_decode(x, pa);
        kmp_init_decode(y, pb);
        c = mpfr_cmp(x, y);
        result = c > 0 ? 1 : (c < 0 ? -1 : 0);
        mpfr_clear(x);
        mpfr_clear(y);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    kmp_unload(env, b, pb);
    return result;
}

JNIEXPORT jboolean JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_cmpPredicateOp(
    JNIEnv* env, jobject self, jbyteArray a, jbyteArray b, jint op) {
    jsize na = 0, nb = 0;
    jbyte *pa = NULL, *pb = NULL;
    int hit = 0;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return JNI_FALSE;
    pb = kmp_load(env, b, &nb);
    if (!pb) { kmp_unload(env, a, pa); return JNI_FALSE; }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, y;
        kmp_init_decode(x, pa);
        kmp_init_decode(y, pb);
        switch (op) {
#define KMP_CASE(name, id, call) case id: hit = (call) ? 1 : 0; break;
            KMP_PREDICATE_OPS(KMP_CASE)
#undef KMP_CASE
            default: break;
        }
        mpfr_clear(x);
        mpfr_clear(y);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    kmp_unload(env, b, pb);
    return hit ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_eqUlpsOp(
    JNIEnv* env, jobject self, jbyteArray a, jbyteArray b, jint maxUlps) {
    jsize na = 0, nb = 0;
    jbyte *pa = NULL, *pb = NULL;
    int hit = 0;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return JNI_FALSE;
    pb = kmp_load(env, b, &nb);
    if (!pb) { kmp_unload(env, a, pa); return JNI_FALSE; }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x, y;
        kmp_init_decode(x, pa);
        kmp_init_decode(y, pb);
        hit = mpfr_eq(x, y, (unsigned long)maxUlps) ? 1 : 0; /* mpfr.h; domain enforced Kotlin-side */
        mpfr_clear(x);
        mpfr_clear(y);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    kmp_unload(env, b, pb);
    return hit ? JNI_TRUE : JNI_FALSE;
}

/* ----------------------------------------------------------- conversions --- */

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_ofDoubleOp(
    JNIEnv* env, jobject self, jdouble d, jint prec, jint rnd) {
    jbyteArray out;
    (void)self;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_set_d(rop, d, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_ofFloatOp(
    JNIEnv* env, jobject self, jfloat f, jint prec) {
    jbyteArray out;
    (void)self;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_set_flt(rop, f, MPFR_RNDN); /* exact at any prec >= 24 */
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_ofSintmaxOp(
    JNIEnv* env, jobject self, jlong k, jint prec, jint rnd) {
    jbyteArray out;
    (void)self;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_set_sj(rop, (intmax_t)k, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_ofUintmaxOp(
    JNIEnv* env, jobject self, jlong k, jint prec, jint rnd) {
    jbyteArray out;
    (void)self;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_set_uj(rop, (uintmax_t)(uint64_t)k, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_fromMpzOp(
    JNIEnv* env, jobject self, jint signum, jbyteArray mag, jint prec, jint rnd) {
    jbyteArray out = NULL;
    jbyte* pm = NULL;
    (void)self;
    if (mag == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/NullPointerException"), "magnitude");
        return NULL;
    }
    pm = (jbyte*)(*env)->GetByteArrayElements(env, mag, NULL);
    if (pm == NULL) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        jsize n = (*env)->GetArrayLength(env, mag);
        mpz_t z;
        mpfr_t rop;
        mpz_init(z);
        if (n > 0) mpz_import(z, (size_t)n, 1, 1, 1, 0, (const uint8_t*)pm);
        if (signum < 0) mpz_neg(z, z); /* mpz_import ignores sign (get_q pattern) */
        mpfr_init2(rop, (mpfr_prec_t)prec);
        mpfr_set_z(rop, z, (mpfr_rnd_t)rnd);
        out = kmp_encode(env, rop);
        mpfr_clear(rop);
        mpz_clear(z);
    }
    KMP_REGION_END();
    (*env)->ReleaseByteArrayElements(env, mag, pm, JNI_ABORT);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_toMpzOp(
    JNIEnv* env, jobject self, jbyteArray a, jint rnd, jintArray signOut) {
    jsize na = 0;
    jbyte* pa;
    jbyteArray out = NULL;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        mpz_t z;
        int sgn, probe = 0;
        size_t cnt = 0;
        kmp_init_decode(x, pa);
        mpz_init(z);
        mpfr_get_z(z, x, (mpfr_rnd_t)rnd);
        if (mpfr_nan_p(x) || mpfr_inf_p(x)) sgn = 0; /* get_z doc: erange + z=0 */
        else sgn = mpz_sgn(z) < 0 ? -1 : (mpz_sgn(z) > 0 ? 1 : 0);
        if (sgn != 0) {
            mpz_export(NULL, &cnt, 1, 1, 1, 0, z);
            if (cnt) {
                jbyte* po;
                out = (*env)->NewByteArray(env, (jsize)cnt);
                if (out) {
                    po = (jbyte*)(*env)->GetByteArrayElements(env, out, NULL);
                    if (po) { mpz_export(po, NULL, 1, 1, 1, 0, z); (*env)->ReleaseByteArrayElements(env, out, po, 0); }
                    else out = NULL;
                }
            }
        } else if (sgn == 0) out = (*env)->NewByteArray(env, 0);
        {
            jint* ps = (jint*)(*env)->GetPrimitiveArrayCritical(env, signOut, NULL);
            if (ps) { ps[0] = (jint)sgn; (*env)->ReleasePrimitiveArrayCritical(env, signOut, ps, 0); }
        }
        mpz_clear(z);
        mpfr_clear(x);
        (void)probe;
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

JNIEXPORT jdouble JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_toDoubleOp(
    JNIEnv* env, jobject self, jbyteArray a, jint rnd) {
    jsize na = 0;
    jbyte* pa;
    double result = 0.0;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return 0.0;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        kmp_init_decode(x, pa);
        result = mpfr_get_d(x, (mpfr_rnd_t)rnd);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return result;
}

JNIEXPORT jfloat JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_toFloatOp(
    JNIEnv* env, jobject self, jbyteArray a, jint rnd) {
    jsize na = 0;
    jbyte* pa;
    float result = 0.0f;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return 0.0f;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        kmp_init_decode(x, pa);
        result = mpfr_get_flt(x, (mpfr_rnd_t)rnd);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return result;
}

JNIEXPORT jlong JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_toSintmaxOp(
    JNIEnv* env, jobject self, jbyteArray a, jint rnd) {
    jsize na = 0;
    jbyte* pa;
    intmax_t result = 0;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return 0;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        kmp_init_decode(x, pa);
        result = mpfr_get_sj(x, (mpfr_rnd_t)rnd); /* fits-gated Kotlin path */
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return (jlong)result;
}

JNIEXPORT jboolean JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_fitsSintmaxOp(
    JNIEnv* env, jobject self, jbyteArray a, jint rnd) {
    jsize na = 0;
    jbyte* pa;
    int ok;
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return JNI_FALSE;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        kmp_init_decode(x, pa);
        ok = mpfr_fits_intmax_p(x, (mpfr_rnd_t)rnd); /* name in 4.2.1 (intmax naming) */
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* -------------------------------------------------------------- strings ----- */

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_getStrOp(
    JNIEnv* env, jobject self, jbyteArray a, jint base, jint digits, jint rnd, jlongArray header) {
    jsize na = 0;
    jbyte* pa;
    jbyteArray out = NULL;
    /* (void)self;
       NOTE: mantissa embedded '-' is split from the digit bytes; header[0] sign,
       header[1] exponent e with value = sign · 0.digits · base^e (MPFR manual I/O formats). */
    (void)self;
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    if (base < 2 || base > 62) {
        kmp_unload(env, a, pa);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "get_str base must be 2..62");
        return NULL;
    }
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        char* s = NULL;
        mpfr_exp_t e = 0;
        size_t len;
        const char* digits_ptr;
        int neg;
        kmp_init_decode(x, pa);
        s = mpfr_get_str(NULL, &e, base, (size_t)(digits < 0 ? 0 : digits), x, (mpfr_rnd_t)rnd);
        if (s == NULL) goto gend; /* base out of range per manual: no-op, NULL */
        neg = mpfr_sgn(x) < 0;    /* value-derived; skip a leading '-' if MPFR embedded it */
        digits_ptr = (s[0] == '-') ? s + 1 : s;
        len = strlen(digits_ptr);
        out = (*env)->NewByteArray(env, (jsize)len);
        if (out) {
            jbyte* po = (jbyte*)(*env)->GetByteArrayElements(env, out, NULL);
            if (po) {
                int i;
                for (i = 0; i < (int)len; i++) po[i] = (jbyte)digits_ptr[i];
                (*env)->ReleaseByteArrayElements(env, out, po, 0);
                {
                    jlong* ph = (jlong*)(*env)->GetPrimitiveArrayCritical(env, header, NULL);
                    if (ph) {
                        ph[0] = neg ? -1 : 1;
                        ph[1] = (jlong)e;
                        (*env)->ReleasePrimitiveArrayCritical(env, header, ph, 0);
                    } else {
                        (*env)->DeleteLocalRef(env, out);
                        out = NULL;
                    }
                }
            } else {
                (*env)->DeleteLocalRef(env, out);
                out = NULL;
            }
        }
    gend:
        if (s) mpfr_free_str(s);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_strtofrOp(
    JNIEnv* env, jobject self, jstring s, jint prec, jint base, jint rnd, jintArray status) {
    const char* cs;
    jbyteArray out = NULL;
    (void)self;
    cs = (*env)->GetStringUTFChars(env, s, NULL);
    if (cs == NULL) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t rop;
        char* ep = NULL;
        int rc;
        int st = -1;
        mpfr_init2(rop, (mpfr_prec_t)prec);
        /* 4.2.1 returns the usual ternary; validity/exponent-marker translate into
           the wrapper's codes: -1 no valid start, -2 base out of range
           (pre-guard for robustness), -3 valid prefix + exponent-marker garbage. */
        if (!(base == 0 || (base >= 2 && base <= 62))) {
            st = -2;
        } else {
            rc = mpfr_strtofr(rop, cs, &ep, base, (mpfr_rnd_t)rnd);
            (void)rc;
            if (ep != NULL && (const char*)ep > cs) {
                st = 0;
                /* trailing garbage after the consumed subject:
                   MPFR parses the longest valid prefix; reject with a strict parse. */
                while (*ep == ' ' || *ep == '\t') ep++;
                if (*ep != '\0') {
                    char c0 = *ep;
                    st = ((c0 == 'e' || c0 == 'E' || c0 == 'p' || c0 == 'P')) ? -3 : -1;
                }
            } else {
                st = -1;
            }
        }
        if (st == 0) out = kmp_encode(env, rop);
        {
            jint* ps = (jint*)(*env)->GetPrimitiveArrayCritical(env, status, NULL);
            if (ps) { ps[0] = (jint)st; (*env)->ReleasePrimitiveArrayCritical(env, status, ps, 0); }
        }
        mpfr_clear(rop);
    }
    KMP_REGION_END();
    (*env)->ReleaseStringUTFChars(env, s, cs);
    return out;
}

JNIEXPORT jstring JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_formatOp(
    JNIEnv* env, jobject self, jbyteArray a, jint style, jint digits, jint rnd) {
    jsize na = 0;
    jbyte* pa;
    jstring out = NULL;
    char letter;
    /* style letters come from the generated ops header */
#define X(c) c,
    static const char kmp_letters[] = { KMP_FORMAT_LETTERS };
#undef X
    (void)self;
    if (style < 0 || style >= (jint)(sizeof kmp_letters / sizeof kmp_letters[0])) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "bad format style");
        return NULL;
    }
    letter = kmp_letters[style];
    pa = kmp_load(env, a, &na);
    if (!pa) return NULL;
    KMP_REGION_BEGIN();
    KMP_FLAGS_SAVED_DECL();
    {
        mpfr_t x;
        char fmt[16];
        char* buf = NULL;
        int need;
        kmp_init_decode(x, pa);
        if (digits > 0) {
            snprintf(fmt, sizeof fmt, "%%.*R%c", letter);
            need = mpfr_asprintf(&buf, fmt, (int)digits, x);
        } else {
            snprintf(fmt, sizeof fmt, "%%R%c", letter);
            need = mpfr_asprintf(&buf, fmt, x);
        }
        if (need > 0 && buf) {
            out = (*env)->NewStringUTF(env, buf);
            (void)out; /* encoding: MPFR %R output is ASCII */
        } else if (need < 0) {
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "mpfr_asprintf failed");
        }
        if (buf) mpfr_free_str(buf);
        mpfr_clear(x);
    }
    KMP_REGION_END();
    kmp_unload(env, a, pa);
    return out;
}

/* ----------------------------------------------------------------- flags --- */

JNIEXPORT jint JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_flagsGetOp(JNIEnv* env, jobject self) {
    jint v;
    (void)env;
    (void)self;
    pthread_mutex_lock(&kmp_lock);
    v = (jint)mpfr_flags_test(MPFR_FLAGS_ALL);
    pthread_mutex_unlock(&kmp_lock);
    return v;
}

JNIEXPORT jint JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_flagsSaveOp(JNIEnv* env, jobject self) {
    jint v;
    (void)env;
    (void)self;
    pthread_mutex_lock(&kmp_lock);
    v = (jint)mpfr_flags_save();
    pthread_mutex_unlock(&kmp_lock);
    return v;
}

JNIEXPORT void JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_flagsClearOp(JNIEnv* env, jobject self, jint mask) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&kmp_lock);
    mpfr_flags_clear((mpfr_flags_t)(unsigned)mask);
    pthread_mutex_unlock(&kmp_lock);
}

JNIEXPORT void JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_flagsSetOp(JNIEnv* env, jobject self, jint mask) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&kmp_lock);
    mpfr_flags_set((mpfr_flags_t)(unsigned)mask);
    pthread_mutex_unlock(&kmp_lock);
}

JNIEXPORT void JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_flagsRestoreOp(
    JNIEnv* env, jobject self, jint saved, jint alsoClear) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&kmp_lock);
    mpfr_flags_restore((mpfr_flags_t)(unsigned)saved, (mpfr_flags_t)(unsigned)alsoClear);
    pthread_mutex_unlock(&kmp_lock);
}

JNIEXPORT jboolean JNICALL Java_io_github_thekekt_mpfr_internal_MpfrJni_buildoptTlsOp(JNIEnv* env, jobject self) {
    (void)env;
    (void)self;
    return mpfr_buildopt_tls_p() ? JNI_TRUE : JNI_FALSE;
}
