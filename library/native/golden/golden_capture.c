/* Reference-vector capture harness.
 *
 * Calls the pinned MPFR library (third_party/mpfr/install) directly, mirroring
 * the exact call sequences of the JNI shim families (see library/native/jni/
 * mpfr_kmp_jni.c), and freezes input/outcome pairs plus the exception-flag
 * delta of every operation into TSV fixtures under
 * library/src/jvmTest/resources/golden/.
 *
 * Properties:
 *   - deterministic: fixed matrix, C locale, no timestamps, no randomness;
 *   - self-checked: every emitted literal is re-parsed and compared bit for bit
 *     at the same precision (a literal that does not round-trip aborts);
 *   - flag provenance: every operation runs inside save/clear/restore with the
 *     delta recorded (the wrapper discards the delta by design).
 *
 * Build (Linux/WSL, from the repository root):
 *   gcc -O2 -Wall -I third_party/mpfr/install/include \
 *       library/native/golden/golden_capture.c \
 *       -o /tmp/golden_capture -L third_party/mpfr/install/lib -lmpfr -lgmp
 * Run:
 *   LD_LIBRARY_PATH=third_party/mpfr/install/lib /tmp/golden_capture <outdir>
 *
 * The output directory must exist; the harness writes the family files and
 * manifest.tsv (schema/version/row counts/SHA-256 of its own output).
 * See README.md next to this file for the regeneration procedure.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define MPFR_USE_INTMAX_T 1

#include <locale.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <gmp.h>
#include <mpfr.h>

#include "../jni/mpfr_kmp_ops.h"

#define BUFSZ 1300

/* --------------------------------------------------------------- SHA-256 --- */
/* Compact self-contained implementation: no external crypto dependency, so the
 * harness behaves identically under every toolchain (see README). */

typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    unsigned char buf[64];
    size_t buflen;
} sha256_ctx;

static const uint32_t sha256_k[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
};

static uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256_init(sha256_ctx* c) {
    c->state[0] = 0x6a09e667u; c->state[1] = 0xbb67ae85u;
    c->state[2] = 0x3c6ef372u; c->state[3] = 0xa54ff53au;
    c->state[4] = 0x510e527fu; c->state[5] = 0x9b05688cu;
    c->state[6] = 0x1f83d9abu; c->state[7] = 0x5be0cd19u;
    c->bitlen = 0;
    c->buflen = 0;
}

static void sha256_block(sha256_ctx* c, const unsigned char* p) {
    uint32_t w[64];
    uint32_t a, b, cc, d, e, f, g, h;
    int i;
    for (i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    for (i = 16; i < 64; i++) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = c->state[0]; b = c->state[1]; cc = c->state[2]; d = c->state[3];
    e = c->state[4]; f = c->state[5]; g = c->state[6]; h = c->state[7];
    for (i = 0; i < 64; i++) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + S1 + ch + sha256_k[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->state[0] += a; c->state[1] += b; c->state[2] += cc; c->state[3] += d;
    c->state[4] += e; c->state[5] += f; c->state[6] += g; c->state[7] += h;
}

static void sha256_update(sha256_ctx* c, const void* data, size_t len) {
    const unsigned char* p = (const unsigned char*)data;
    c->bitlen += (uint64_t)len * 8u;
    while (len > 0) {
        size_t take = 64 - c->buflen;
        if (take > len) take = len;
        memcpy(c->buf + c->buflen, p, take);
        c->buflen += take;
        p += take;
        len -= take;
        if (c->buflen == 64) {
            sha256_block(c, c->buf);
            c->buflen = 0;
        }
    }
}

static void sha256_final(sha256_ctx* c, unsigned char outdig[32]) {
    uint64_t bits = c->bitlen;
    unsigned char pad = 0x80;
    unsigned char zero = 0x00;
    unsigned char lenb[8];
    int i;
    sha256_update(c, &pad, 1);
    while (c->buflen != 56) sha256_update(c, &zero, 1);
    for (i = 0; i < 8; i++) lenb[i] = (unsigned char)(bits >> (56 - 8 * i));
    sha256_update(c, lenb, 8);
    for (i = 0; i < 8; i++) {
        outdig[i * 4] = (unsigned char)(c->state[i] >> 24);
        outdig[i * 4 + 1] = (unsigned char)(c->state[i] >> 16);
        outdig[i * 4 + 2] = (unsigned char)(c->state[i] >> 8);
        outdig[i * 4 + 3] = (unsigned char)(c->state[i]);
    }
}

static void sha256_hex(const unsigned char digest[32], char hexout[65]) {
    static const char* hexd = "0123456789abcdef";
    int i;
    for (i = 0; i < 32; i++) {
        hexout[i * 2] = hexd[digest[i] >> 4];
        hexout[i * 2 + 1] = hexd[digest[i] & 0xf];
    }
    hexout[64] = '\0';
}

/* ----------------------------------------------------------- output files --- */

enum {
    F_BIN = 0, F_FUSED, F_UNARY, F_SCALAR, F_DOUBLE, F_PAIR, F_CONST,
    F_PRED, F_CMP, F_DOTSUM, F_FACT, F_CONV, F_STR,
    F_COUNT
};

static const char* const family_prefix[F_COUNT] = {
    "BIN", "FUS", "UN", "SCA", "DBL", "PAIR", "CST",
    "PRED", "CMP", "DOTSUM", "FAC", "CONV", "STR"
};

static const char* const family_file[F_COUNT] = {
    "ops-binary.tsv", "ops-fused.tsv", "ops-unary.tsv", "ops-scalar.tsv",
    "ops-double.tsv", "ops-pair.tsv", "consts.tsv", "predicates.tsv",
    "cmp.tsv", "dot-sum.tsv", "factorial.tsv", "conversions.tsv", "strings.tsv"
};

static const char* const family_header[F_COUNT] = {
    "row_id\top\tcategory\tprec_a\tprec_b\tprec_out\trnd\ta\tb\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_a\tprec_b\tprec_c\tprec_out\trnd\ta\tb\tc\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_in\tprec_out\trnd\tx\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_in\tprec_out\trnd\tx\tk\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_in\tprec_out\trnd\tx\td_bits\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_in\tprec_out\trnd\tx\tkind\tvalue\tvalue2\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_out\trnd\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_a\tprec_b\ta\tb\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_a\tprec_b\ta\tb\tk\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec_out\trnd\ta\tb\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tn\tprec_out\trnd\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec\trnd\tinput\tkind\tvalue\tinexact\tflags\tnote",
    "row_id\top\tcategory\tprec\tbase\tdigits\tstyle\trnd\tinput\tkind\tvalue\tvalue2\tinexact\tflags\tnote"
};

typedef struct {
    FILE* f;
    sha256_ctx sha;
    long rows;
} OutFile;

static OutFile out[F_COUNT];
static const char* out_dir = ".";

static void of_open(int idx) {
    char path[1024];
    int n;
    char line[1024];
    snprintf(path, sizeof path, "%s/%s", out_dir, family_file[idx]);
    out[idx].f = fopen(path, "wb");
    if (!out[idx].f) {
        fprintf(stderr, "cannot open %s\n", path);
        exit(2);
    }
    setvbuf(out[idx].f, NULL, _IONBF, 0); /* unbuffered: partial files show exact progress */
    sha256_init(&out[idx].sha);
    out[idx].rows = 0;
    n = snprintf(line, sizeof line, "%s\n", family_header[idx]);
    fwrite(line, 1, (size_t)n, out[idx].f);
    sha256_update(&out[idx].sha, line, (size_t)n);
}

static void of_row(int idx, const char* fmt, ...) {
    char line[8192];
    int n;
    va_list ap;
    va_start(ap, fmt);
    n = vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= sizeof line) {
        fprintf(stderr, "row buffer overflow in family %s\n", family_file[idx]);
        exit(2);
    }
    fwrite(line, 1, (size_t)n, out[idx].f);
    fputc('\n', out[idx].f);
    sha256_update(&out[idx].sha, line, (size_t)n);
    sha256_update(&out[idx].sha, "\n", 1);
    out[idx].rows++;
}

static long seq[F_COUNT];

/* Row id: <FAMILY>-<six-digit sequence>, stable in generation order. */
static void row_id(int idx, char* buf, size_t buflen) {
    snprintf(buf, buflen, "%s-%06ld", family_prefix[idx], ++seq[idx]);
}

static void of_close_all(void) {
    char path[1024];
    FILE* m;
    int i;
    long total = 0;
    for (i = 0; i < F_COUNT; i++) {
        if (fclose(out[i].f) != 0) {
            fprintf(stderr, "cannot close family %s\n", family_file[i]);
            exit(2);
        }
    }
    snprintf(path, sizeof path, "%s/manifest.tsv", out_dir);
    m = fopen(path, "wb");
    if (!m) {
        fprintf(stderr, "cannot open %s\n", path);
        exit(2);
    }
    fputs("key\ta\tb\tc\n", m);
    fputs("schema_version\t1\t\t\n", m);
    fprintf(m, "mpfr_version\t%s\t\t\n", mpfr_get_version());
    for (i = 0; i < F_COUNT; i++) {
        unsigned char digest[32];
        char hex[65];
        sha256_ctx c = out[i].sha;
        sha256_final(&c, digest);
        sha256_hex(digest, hex);
        fprintf(m, "file\t%s\t%ld\t%s\n", family_file[i], out[i].rows, hex);
        total += out[i].rows;
    }
    if (fclose(m) != 0) {
        fprintf(stderr, "cannot close manifest\n");
        exit(2);
    }
    for (i = 0; i < F_COUNT; i++)
        printf("%-16s %6ld rows\n", family_file[i], out[i].rows);
    printf("%-16s %6ld rows\n", "TOTAL", total);
}

/* ----------------------------------------------------------- value helpers -- */

/* Canonical textual token of a value: hex float (C99 style) or special tag. */
static void token_of(mpfr_srcptr v, char outbuf[BUFSZ]) {
    if (mpfr_nan_p(v)) { strcpy(outbuf, "@nan@"); return; }
    if (mpfr_inf_p(v)) { strcpy(outbuf, mpfr_signbit(v) ? "@inf-" : "@inf+"); return; }
    if (mpfr_zero_p(v)) { strcpy(outbuf, mpfr_signbit(v) ? "@zero-" : "@zero+"); return; }
    {
        mpfr_exp_t e = 0;
        /* One leading-bit hex digit plus full nibbles: exact for every p-bit
         * significand (mpfr_get_str pads the tail). */
        size_t n = 1 + (size_t)((mpfr_get_prec(v) + 2) / 4);
        const char* digits;
        char* s;
        long long pexp;
        int neg;
        if (n < 1) n = 1;
        s = mpfr_get_str(NULL, &e, 16, n, v, MPFR_RNDN);
        if (s == NULL) {
            fprintf(stderr, "mpfr_get_str failed\n");
            exit(2);
        }
        neg = mpfr_sgn(v) < 0;
        digits = (s[0] == '-') ? s + 1 : s;
        pexp = 4LL * ((long long)e - 1LL);
        if (digits[1] == '\0')
            snprintf(outbuf, BUFSZ, "%s0x%c.0p%lld", neg ? "-" : "", digits[0], pexp);
        else
            snprintf(outbuf, BUFSZ, "%s0x%c.%sp%lld", neg ? "-" : "", digits[0], digits + 1, pexp);
        mpfr_free_str(s);
    }
}

/* Self-check: the emitted literal must re-parse to the very same value at the
 * very same precision (guards the whole fixture format). */
static void check_literal(const char* lit, mpfr_srcptr v, const char* ctx) {
    mpfr_t t;
    int rc;
    int ok;
    if (lit[0] == '@') return;
    mpfr_init2(t, mpfr_get_prec(v));
    rc = mpfr_set_str(t, lit, 0, MPFR_RNDN);
    ok = (rc == 0) && mpfr_get_prec(t) == mpfr_get_prec(v) && mpfr_equal_p(t, v);
    mpfr_clear(t);
    if (!ok) {
        fprintf(stderr, "self-check failed for %s: %s\n", ctx, lit);
        exit(3);
    }
}

/* Seeds accepted by the matrix tables: special tags or decimal strings. */
static void set_seed(mpfr_ptr x, const char* s) {
    if (strcmp(s, "@nan@") == 0) { mpfr_set_nan(x); return; }
    if (strcmp(s, "@inf+") == 0) { mpfr_set_inf(x, 1); return; }
    if (strcmp(s, "@inf-") == 0) { mpfr_set_inf(x, -1); return; }
    if (strcmp(s, "@zero+") == 0) { mpfr_set_zero(x, 1); return; }
    if (strcmp(s, "@zero-") == 0) { mpfr_set_zero(x, -1); return; }
    if (strcmp(s, "@maxfin@") == 0) {
        /* (2 - 2^(1-p)) * 2^(emax-1) built with a 53-bit significand: exact at
         * any precision >= 53. */
        mpfr_set_ui_2exp(x, (1UL << 53) - 1, mpfr_get_emax() - 53, MPFR_RNDN);
        return;
    }
    if (strcmp(s, "@tiniest@") == 0) {
        mpfr_set_ui_2exp(x, 1, mpfr_get_emin() - 1, MPFR_RNDN);
        return;
    }
    if (strcmp(s, "@eminpow2@") == 0) {
        mpfr_set_ui_2exp(x, 1, mpfr_get_emin(), MPFR_RNDN);
        return;
    }
    if (mpfr_set_str(x, s, 10, MPFR_RNDN) != 0) {
        fprintf(stderr, "bad seed: %s\n", s);
        exit(2);
    }
}

static const char* rnd_name(mpfr_rnd_t r) {
    switch (r) {
        case MPFR_RNDN: return "N";
        case MPFR_RNDZ: return "Z";
        case MPFR_RNDU: return "U";
        case MPFR_RNDD: return "D";
        case MPFR_RNDA: return "A";
        default: return "?";
    }
}

static mpfr_flags_t flags_begin(void) {
    mpfr_flags_t saved = mpfr_flags_save();
    mpfr_clear_flags();
    return saved;
}

static mpfr_flags_t flags_end(mpfr_flags_t saved) {
    mpfr_flags_t delta = mpfr_flags_test(MPFR_FLAGS_ALL);
    mpfr_flags_restore(saved, MPFR_FLAGS_ALL);
    return delta;
}

/* ------------------------------------------------- family function tables --- */

typedef void (*bin_fn)(mpfr_t, mpfr_srcptr, mpfr_srcptr, mpfr_rnd_t);
typedef void (*fused_fn)(mpfr_t, mpfr_srcptr, mpfr_srcptr, mpfr_srcptr, mpfr_rnd_t);
typedef void (*unary_fn)(mpfr_t, mpfr_srcptr, mpfr_rnd_t);
typedef void (*scalar_fn)(mpfr_t, mpfr_srcptr, long long, mpfr_rnd_t);
typedef void (*double_fn)(mpfr_t, mpfr_srcptr, double, mpfr_rnd_t);
typedef void (*pair_fn)(mpfr_t, mpfr_t, mpfr_srcptr, mpfr_rnd_t);
typedef void (*const_fn)(mpfr_t, mpfr_rnd_t);

#define K(name, id, call) \
    static void bin_##name(mpfr_t rop, mpfr_srcptr x, mpfr_srcptr y, mpfr_rnd_t r) { call; }
KMP_BINARY_OPS(K)
#undef K

#define K(name, id, call) \
    static void fused_##name(mpfr_t rop, mpfr_srcptr x, mpfr_srcptr y, mpfr_srcptr z, mpfr_rnd_t r) { call; }
KMP_FUSED_OPS(K)
#undef K

#define K(name, id, call) \
    static void unary_##name(mpfr_t rop, mpfr_srcptr x, mpfr_rnd_t r) { call; }
KMP_UNARY_OPS(K)
#undef K

#define K(name, id, call) \
    static void scalar_##name(mpfr_t rop, mpfr_srcptr x, long long k, mpfr_rnd_t r) { \
        mpfr_t tmp; \
        mpfr_init2(tmp, 65); \
        call; \
        mpfr_clear(tmp); \
    }
KMP_SCALAR_OPS(K)
#undef K

#define K(name, id, call) \
    static void double_##name(mpfr_t rop, mpfr_srcptr x, double d, mpfr_rnd_t r) { \
        mpfr_t tmp; \
        mpfr_init2(tmp, 53); \
        call; \
        mpfr_clear(tmp); \
    }
KMP_DOUBLE_OPS(K)
#undef K

#define K(name, id, call) \
    static void pair_##name(mpfr_t rop, mpfr_t rop2, mpfr_srcptr x, mpfr_rnd_t r) { call; }
KMP_PAIR_OPS(K)
#undef K

#define K(name, id, call) \
    static void const_##name(mpfr_t rop, mpfr_rnd_t r) { \
        mpfr_t tmp; \
        mpfr_init2(tmp, 53); \
        call; \
        mpfr_clear(tmp); \
    }
KMP_CONST_OPS(K)
#undef K

#define K(name, id, call) \
    static int pred_##name(mpfr_srcptr x, mpfr_srcptr y) { return (call) ? 1 : 0; }
KMP_PREDICATE_OPS(K)
#undef K

/* ----------------------------------------------------------------- blocks --- */

/* Core sweep precision and rounding sets. */
static const int core_precs[6] = {2, 24, 53, 64, 113, 256};
static const mpfr_rnd_t core_rnds[5] = {MPFR_RNDN, MPFR_RNDZ, MPFR_RNDU, MPFR_RNDD, MPFR_RNDA};
/* Special-value sweep settings: (53, N) and (64, Z). */
static const int spec_pass_prec[2] = {53, 64};

static void block_binary_core(void) {
    static const struct { const char* op; bin_fn fn; const char* x; const char* y; } t[] = {
        {"ADD", bin_ADD, "1.5", "0.1"}, {"ADD", bin_ADD, "-2.5", "0.7"}, {"ADD", bin_ADD, "0.3", "-1.1"},
        {"SUB", bin_SUB, "1.5", "0.1"}, {"SUB", bin_SUB, "-2.5", "0.7"}, {"SUB", bin_SUB, "0.3", "-1.1"},
        {"MUL", bin_MUL, "1.5", "0.3"}, {"MUL", bin_MUL, "-2.5", "0.7"}, {"MUL", bin_MUL, "0.3", "-1.1"},
        {"DIV", bin_DIV, "1.5", "0.3"}, {"DIV", bin_DIV, "-2.5", "0.7"}, {"DIV", bin_DIV, "0.3", "-1.1"},
        {"POW", bin_POW, "2.5", "0.7"}, {"POW", bin_POW, "0.5", "1.5"}, {"POW", bin_POW, "1.5", "-2.5"},
        {"ATAN2", bin_ATAN2, "1.5", "2.5"}, {"ATAN2", bin_ATAN2, "-1", "0.5"}, {"ATAN2", bin_ATAN2, "0.5", "-1.5"},
        {"MIN", bin_MIN, "1.5", "0.1"}, {"MIN", bin_MIN, "-2.5", "0.7"}, {"MIN", bin_MIN, "0.3", "1.1"},
        {"MAX", bin_MAX, "1.5", "0.1"}, {"MAX", bin_MAX, "-2.5", "0.7"}, {"MAX", bin_MAX, "0.3", "1.1"},
        {"DIM", bin_DIM, "2.5", "0.7"}, {"DIM", bin_DIM, "0.5", "1.5"}, {"DIM", bin_DIM, "-1.5", "0.5"},
        {"HYPOT", bin_HYPOT, "3", "4"}, {"HYPOT", bin_HYPOT, "1.5", "2.5"}, {"HYPOT", bin_HYPOT, "0.5", "1.25"},
        {"AGM", bin_AGM, "1.5", "2.5"}, {"AGM", bin_AGM, "0.75", "1.25"}, {"AGM", bin_AGM, "2", "3"},
        {"FMOD", bin_FMOD, "10.5", "3"}, {"FMOD", bin_FMOD, "-10.5", "3"}, {"FMOD", bin_FMOD, "5.5", "2"},
        {"REMAINDER", bin_REMAINDER, "10.5", "3"}, {"REMAINDER", bin_REMAINDER, "-10.5", "3"},
        {"REMAINDER", bin_REMAINDER, "5.5", "2"},
        {"RELDIFF", bin_RELDIFF, "1.5", "2.5"}, {"RELDIFF", bin_RELDIFF, "-1.5", "0.5"},
        {"RELDIFF", bin_RELDIFF, "3", "1"},
    };
    size_t i;
    for (i = 0; i < sizeof t / sizeof t[0]; i++) {
        int pi, ri;
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                mpfr_rnd_t r = core_rnds[ri];
                mpfr_t x, y, rop;
                char xa[BUFSZ], ya[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(y, p); mpfr_init2(rop, p);
                set_seed(x, t[i].x); set_seed(y, t[i].y);
                saved = flags_begin();
                t[i].fn(rop, x, y, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(y, ya); token_of(rop, va);
                check_literal(xa, x, t[i].op); check_literal(ya, y, t[i].op);
                check_literal(va, rop, t[i].op);
                row_id(F_BIN, id, sizeof id);
                of_row(F_BIN, "%s\t%s\tCORE\t%ld\t%ld\t%ld\t%s\t%s\t%s\tREG\t%s\t%d\t%u\tcore sweep",
                       id, t[i].op, (long)p, (long)p, (long)p, rnd_name(r), xa, ya, va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(rop); mpfr_clear(y); mpfr_clear(x);
            }
        }
    }
}

static void block_binary_special(void) {
    static const struct { const char* x; const char* y; } t[] = {
        {"@nan@", "1.5"}, {"1.5", "@nan@"},
        {"@inf+", "@inf+"}, {"@inf+", "@inf-"}, {"@inf-", "1.5"},
        {"@zero+", "@zero+"}, {"@zero+", "@zero-"}, {"@zero-", "1.5"},
        {"@maxfin@", "2"}, {"@maxfin@", "@maxfin@"},
        {"@tiniest@", "1.5"}, {"1.5", "@tiniest@"},
    };
    static const struct { const char* op; bin_fn fn; int allow_maxfin; } ops[] = {
        {"ADD", bin_ADD, 1}, {"SUB", bin_SUB, 1}, {"MUL", bin_MUL, 1}, {"DIV", bin_DIV, 1},
        {"POW", bin_POW, 1}, {"ATAN2", bin_ATAN2, 1}, {"MIN", bin_MIN, 1}, {"MAX", bin_MAX, 1},
        {"DIM", bin_DIM, 1}, {"HYPOT", bin_HYPOT, 0}, {"AGM", bin_AGM, 1}, {"FMOD", bin_FMOD, 1},
        {"REMAINDER", bin_REMAINDER, 1}, {"RELDIFF", bin_RELDIFF, 1},
    };
    size_t oi, ti;
    int pass;
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (ti = 0; ti < sizeof t / sizeof t[0]; ti++) {
            if (!ops[oi].allow_maxfin &&
                (strcmp(t[ti].x, "@maxfin@") == 0 || strcmp(t[ti].y, "@maxfin@") == 0))
                continue; /* hypot: keep inputs inside the audit-cleared domain */
            for (pass = 0; pass < 2; pass++) {
                mpfr_prec_t p = (mpfr_prec_t)spec_pass_prec[pass];
                mpfr_rnd_t r = pass == 0 ? MPFR_RNDN : MPFR_RNDZ;
                mpfr_t x, y, rop;
                char xa[BUFSZ], ya[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(y, p); mpfr_init2(rop, p);
                set_seed(x, t[ti].x); set_seed(y, t[ti].y);
                saved = flags_begin();
                ops[oi].fn(rop, x, y, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(y, ya); token_of(rop, va);
                check_literal(xa, x, ops[oi].op); check_literal(ya, y, ops[oi].op);
                check_literal(va, rop, ops[oi].op);
                row_id(F_BIN, id, sizeof id);
                of_row(F_BIN, "%s\t%s\tSPECIAL\t%ld\t%ld\t%ld\t%s\t%s\t%s\tREG\t%s\t%d\t%u\tspecials",
                       id, ops[oi].op, (long)p, (long)p, (long)p, rnd_name(r), xa, ya, va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(rop); mpfr_clear(y); mpfr_clear(x);
            }
        }
    }
}

static void block_fused(void) {
    static const struct { const char* op; fused_fn fn; const char* x; const char* y; const char* z; } t[] = {
        {"FMA", fused_FMA, "1.5", "0.3", "0.7"}, {"FMA", fused_FMA, "-2.5", "1.1", "-0.5"},
        {"FMA", fused_FMA, "0.3", "-0.75", "2"},
        {"FMS", fused_FMS, "1.5", "0.3", "0.7"}, {"FMS", fused_FMS, "-2.5", "1.1", "-0.5"},
        {"FMS", fused_FMS, "0.3", "-0.75", "2"},
    };
    static const char* tri[][3] = {
        {"@nan@", "1.5", "0.5"}, {"1.5", "@nan@", "0.5"}, {"1.5", "0.5", "@nan@"},
        {"@inf+", "@inf+", "1.5"}, {"@inf+", "@inf-", "1.5"}, {"@zero+", "@zero-", "1.5"},
        {"@zero-", "1.5", "0.5"}, {"@maxfin@", "@maxfin@", "1.5"}, {"@tiniest@", "1.5", "0.5"},
        {"1.5", "@tiniest@", "0.5"}, {"1.5", "0.5", "@tiniest@"}, {"@maxfin@", "1.5", "2"},
    };
    static const struct { const char* op; fused_fn fn; } ops[] = {
        {"FMA", fused_FMA}, {"FMS", fused_FMS},
    };
    size_t i, oi, ti;
    int pass;
    for (i = 0; i < sizeof t / sizeof t[0]; i++) {
        int pi, ri;
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                mpfr_rnd_t r = core_rnds[ri];
                mpfr_t x, y, z, rop;
                char xa[BUFSZ], ya[BUFSZ], za[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(y, p); mpfr_init2(z, p); mpfr_init2(rop, p);
                set_seed(x, t[i].x); set_seed(y, t[i].y); set_seed(z, t[i].z);
                saved = flags_begin();
                t[i].fn(rop, x, y, z, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(y, ya); token_of(z, za); token_of(rop, va);
                check_literal(xa, x, t[i].op); check_literal(ya, y, t[i].op);
                check_literal(za, z, t[i].op); check_literal(va, rop, t[i].op);
                row_id(F_FUSED, id, sizeof id);
                of_row(F_FUSED, "%s\t%s\tCORE\t%ld\t%ld\t%ld\t%ld\t%s\t%s\t%s\t%s\tREG\t%s\t%d\t%u\tcore sweep",
                       id, t[i].op, (long)p, (long)p, (long)p, (long)p, rnd_name(r),
                       xa, ya, za, va, (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(rop); mpfr_clear(z); mpfr_clear(y); mpfr_clear(x);
            }
        }
    }
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (ti = 0; ti < sizeof tri / sizeof tri[0]; ti++) {
            for (pass = 0; pass < 2; pass++) {
                mpfr_prec_t p = (mpfr_prec_t)spec_pass_prec[pass];
                mpfr_rnd_t r = pass == 0 ? MPFR_RNDN : MPFR_RNDZ;
                mpfr_t x, y, z, rop;
                char xa[BUFSZ], ya[BUFSZ], za[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(y, p); mpfr_init2(z, p); mpfr_init2(rop, p);
                set_seed(x, tri[ti][0]); set_seed(y, tri[ti][1]); set_seed(z, tri[ti][2]);
                saved = flags_begin();
                ops[oi].fn(rop, x, y, z, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(y, ya); token_of(z, za); token_of(rop, va);
                check_literal(va, rop, ops[oi].op);
                row_id(F_FUSED, id, sizeof id);
                of_row(F_FUSED, "%s\t%s\tSPECIAL\t%ld\t%ld\t%ld\t%ld\t%s\t%s\t%s\t%s\tREG\t%s\t%d\t%u\tspecials",
                       id, ops[oi].op, (long)p, (long)p, (long)p, (long)p, rnd_name(r),
                       xa, ya, za, va, (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(rop); mpfr_clear(z); mpfr_clear(y); mpfr_clear(x);
            }
        }
    }
}

static void emit_unary(int idx, const char* op, const char* cat, unary_fn fn,
                       const char* seed, mpfr_rnd_t r, mpfr_prec_t p, const char* note) {
    mpfr_t x, rop;
    char xa[BUFSZ], va[BUFSZ], id[32];
    mpfr_flags_t saved, delta;
    mpfr_init2(x, p);
    mpfr_init2(rop, p);
    set_seed(x, seed);
    saved = flags_begin();
    mpfr_set(rop, x, MPFR_RNDN); /* rop is pre-copied from x, as in the shim */
    fn(rop, x, r);
    delta = flags_end(saved);
    token_of(x, xa);
    token_of(rop, va);
    check_literal(xa, x, op);
    check_literal(va, rop, op);
    row_id(idx, id, sizeof id);
    of_row(idx, "%s\t%s\t%s\t%ld\t%ld\t%s\t%s\tREG\t%s\t%d\t%u\t%s",
           id, op, cat, (long)p, (long)p, rnd_name(r), xa, va,
           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta, note);
    mpfr_clear(rop);
    mpfr_clear(x);
}

/* Huge-magnitude arguments are excluded for functions whose evaluation reduces
 * modulo pi or evaluates a huge-argument asymptotic series (evaluation cost
 * grows with the argument exponent; the upstream audit flagged the same
 * domain). */
static int huge_arg_forbidden(const char* op) {
    static const char* ops[] = {
        "SIN", "COS", "TAN", "SINPI", "COSPI", "TANPI", "GAMMA", "LNGAMMA", "ZETA",
    };
    size_t i;
    for (i = 0; i < sizeof ops / sizeof ops[0]; i++)
        if (strcmp(op, ops[i]) == 0) return 1;
    return 0;
}

static void block_unary_core(void) {
    static const struct { const char* op; unary_fn fn; const char* seed; } t[] = {
        {"SQR", unary_SQR, "1.5"}, {"SQR", unary_SQR, "0.75"}, {"SQR", unary_SQR, "2.25"},
        {"SQRT", unary_SQRT, "1.5"}, {"SQRT", unary_SQRT, "0.75"}, {"SQRT", unary_SQRT, "2.25"},
        {"CBRT", unary_CBRT, "1.5"}, {"CBRT", unary_CBRT, "0.75"}, {"CBRT", unary_CBRT, "2.25"},
        {"REC_SQRT", unary_REC_SQRT, "1.5"}, {"REC_SQRT", unary_REC_SQRT, "0.75"}, {"REC_SQRT", unary_REC_SQRT, "2.25"},
        {"EXP", unary_EXP, "0.5"}, {"EXP", unary_EXP, "-1.5"}, {"EXP", unary_EXP, "2.25"},
        {"EXP2", unary_EXP2, "0.5"}, {"EXP2", unary_EXP2, "-1.5"}, {"EXP2", unary_EXP2, "2.25"},
        {"EXP10", unary_EXP10, "0.5"}, {"EXP10", unary_EXP10, "-1.5"}, {"EXP10", unary_EXP10, "2.25"},
        {"EXPM1", unary_EXPM1, "0.5"}, {"EXPM1", unary_EXPM1, "-0.25"}, {"EXPM1", unary_EXPM1, "0.1"},
        {"EXP2M1", unary_EXP2M1, "0.5"}, {"EXP2M1", unary_EXP2M1, "-0.25"}, {"EXP2M1", unary_EXP2M1, "0.1"},
        {"EXP10M1", unary_EXP10M1, "0.5"}, {"EXP10M1", unary_EXP10M1, "-0.25"}, {"EXP10M1", unary_EXP10M1, "0.1"},
        {"LOG", unary_LOG, "1.5"}, {"LOG", unary_LOG, "0.75"}, {"LOG", unary_LOG, "3.125"},
        {"LOG2", unary_LOG2, "1.5"}, {"LOG2", unary_LOG2, "0.75"}, {"LOG2", unary_LOG2, "3.125"},
        {"LOG10", unary_LOG10, "1.5"}, {"LOG10", unary_LOG10, "0.75"}, {"LOG10", unary_LOG10, "3.125"},
        {"LOG1P", unary_LOG1P, "0.5"}, {"LOG1P", unary_LOG1P, "-0.25"}, {"LOG1P", unary_LOG1P, "0.1"},
        {"LOG2P1", unary_LOG2P1, "0.5"}, {"LOG2P1", unary_LOG2P1, "-0.25"}, {"LOG2P1", unary_LOG2P1, "0.1"},
        {"LOG10P1", unary_LOG10P1, "0.5"}, {"LOG10P1", unary_LOG10P1, "-0.25"}, {"LOG10P1", unary_LOG10P1, "0.1"},
        {"SIN", unary_SIN, "0.5"}, {"SIN", unary_SIN, "1.25"}, {"SIN", unary_SIN, "-0.75"},
        {"COS", unary_COS, "0.5"}, {"COS", unary_COS, "1.25"}, {"COS", unary_COS, "-0.75"},
        {"TAN", unary_TAN, "0.5"}, {"TAN", unary_TAN, "1.25"}, {"TAN", unary_TAN, "-0.75"},
        {"ASIN", unary_ASIN, "0.5"}, {"ASIN", unary_ASIN, "-0.75"}, {"ASIN", unary_ASIN, "0.2"},
        {"ACOS", unary_ACOS, "0.5"}, {"ACOS", unary_ACOS, "-0.75"}, {"ACOS", unary_ACOS, "0.2"},
        {"ATAN", unary_ATAN, "0.5"}, {"ATAN", unary_ATAN, "-1.25"}, {"ATAN", unary_ATAN, "2.5"},
        {"SINH", unary_SINH, "0.5"}, {"SINH", unary_SINH, "-1.25"}, {"SINH", unary_SINH, "2.5"},
        {"COSH", unary_COSH, "0.5"}, {"COSH", unary_COSH, "-1.25"}, {"COSH", unary_COSH, "2.5"},
        {"TANH", unary_TANH, "0.5"}, {"TANH", unary_TANH, "-1.25"}, {"TANH", unary_TANH, "2.5"},
        {"ASINH", unary_ASINH, "0.5"}, {"ASINH", unary_ASINH, "-1.25"}, {"ASINH", unary_ASINH, "2.5"},
        {"ACOSH", unary_ACOSH, "1.5"}, {"ACOSH", unary_ACOSH, "2.75"}, {"ACOSH", unary_ACOSH, "1.1"},
        {"ATANH", unary_ATANH, "0.5"}, {"ATANH", unary_ATANH, "-0.25"}, {"ATANH", unary_ATANH, "0.1"},
        {"SINPI", unary_SINPI, "0.25"}, {"SINPI", unary_SINPI, "0.5"}, {"SINPI", unary_SINPI, "-0.75"},
        {"COSPI", unary_COSPI, "0.25"}, {"COSPI", unary_COSPI, "0.5"}, {"COSPI", unary_COSPI, "-0.75"},
        {"TANPI", unary_TANPI, "0.25"}, {"TANPI", unary_TANPI, "0.5"}, {"TANPI", unary_TANPI, "-0.75"},
        {"ERF", unary_ERF, "0.5"}, {"ERF", unary_ERF, "-1.25"}, {"ERF", unary_ERF, "2.75"},
        {"ERFC", unary_ERFC, "0.5"}, {"ERFC", unary_ERFC, "-1.25"}, {"ERFC", unary_ERFC, "2.75"},
        {"GAMMA", unary_GAMMA, "1.5"}, {"GAMMA", unary_GAMMA, "2.75"}, {"GAMMA", unary_GAMMA, "0.5"},
        {"LNGAMMA", unary_LNGAMMA, "1.5"}, {"LNGAMMA", unary_LNGAMMA, "2.75"}, {"LNGAMMA", unary_LNGAMMA, "0.5"},
        {"DIGAMMA", unary_DIGAMMA, "1.5"}, {"DIGAMMA", unary_DIGAMMA, "2.75"}, {"DIGAMMA", unary_DIGAMMA, "0.5"},
        {"ZETA", unary_ZETA, "2.5"}, {"ZETA", unary_ZETA, "3.5"}, {"ZETA", unary_ZETA, "-1.5"},
        {"FRAC", unary_FRAC, "1.5"}, {"FRAC", unary_FRAC, "-2.75"}, {"FRAC", unary_FRAC, "0.3"},
    };
    static const struct { const char* op; unary_fn fn; const char* seed; } rf[] = {
        {"TRUNC", unary_TRUNC, "1.5"}, {"TRUNC", unary_TRUNC, "-2.75"}, {"TRUNC", unary_TRUNC, "0.3"},
        {"FLOOR", unary_FLOOR, "1.5"}, {"FLOOR", unary_FLOOR, "-2.75"}, {"FLOOR", unary_FLOOR, "0.3"},
        {"CEIL", unary_CEIL, "1.5"}, {"CEIL", unary_CEIL, "-2.75"}, {"CEIL", unary_CEIL, "0.3"},
        {"ROUND", unary_ROUND, "1.5"}, {"ROUND", unary_ROUND, "-2.75"}, {"ROUND", unary_ROUND, "0.3"},
        {"ROUND_EVEN", unary_ROUND_EVEN, "1.5"}, {"ROUND_EVEN", unary_ROUND_EVEN, "-2.75"}, {"ROUND_EVEN", unary_ROUND_EVEN, "0.3"},
        {"NEXTUP", unary_NEXTUP, "1.5"}, {"NEXTUP", unary_NEXTUP, "-2.75"}, {"NEXTUP", unary_NEXTUP, "0.3"},
        {"NEXTDOWN", unary_NEXTDOWN, "1.5"}, {"NEXTDOWN", unary_NEXTDOWN, "-2.75"}, {"NEXTDOWN", unary_NEXTDOWN, "0.3"},
    };
    static const char* vals[] = {
        "@nan@", "@inf+", "@inf-", "@zero+", "@zero-", "@maxfin@",
        "@tiniest@", "@eminpow2@", "1.5", "0.5", "-1.5", "0.1",
    };
    size_t i, vi;
    int pi, ri, pass;
    for (i = 0; i < sizeof t / sizeof t[0]; i++)
        for (pi = 0; pi < 6; pi++)
            for (ri = 0; ri < 5; ri++)
                emit_unary(F_UNARY, t[i].op, "CORE", t[i].fn, t[i].seed,
                           core_rnds[ri], (mpfr_prec_t)core_precs[pi], "core sweep");
    for (i = 0; i < sizeof rf / sizeof rf[0]; i++)
        for (pi = 0; pi < 6; pi++)
            emit_unary(F_UNARY, rf[i].op, "CORE", rf[i].fn, rf[i].seed,
                       MPFR_RNDN, (mpfr_prec_t)core_precs[pi], "rnd ignored");
for (i = 0; i < sizeof t / sizeof t[0]; i++) {
        if (i > 0 && strcmp(t[i].op, t[i - 1].op) == 0)
            continue; /* one specials pass per operation, not per seed row */
        for (vi = 0; vi < sizeof vals / sizeof vals[0]; vi++) {
            if (huge_arg_forbidden(t[i].op) && strcmp(vals[vi], "@maxfin@") == 0)
                continue; /* huge-argument evaluation is out of scope (cost) */
            if (strcmp(t[i].op, "DIGAMMA") == 0 &&
                (strcmp(vals[vi], "@maxfin@") == 0 || strcmp(vals[vi], "@tiniest@") == 0 ||
                 strcmp(vals[vi], "@eminpow2@") == 0))
                continue; /* domain kept inside the audit-cleared range */
            for (pass = 0; pass < 2; pass++)
                emit_unary(F_UNARY, t[i].op, "SPECIAL", t[i].fn, vals[vi],
                           pass == 0 ? MPFR_RNDN : MPFR_RNDZ,
                           (mpfr_prec_t)spec_pass_prec[pass], "specials");
        }
    }
    for (i = 0; i < sizeof rf / sizeof rf[0]; i++) {
        if (i > 0 && strcmp(rf[i].op, rf[i - 1].op) == 0)
            continue; /* one specials pass per operation, not per seed row */
        for (vi = 0; vi < sizeof vals / sizeof vals[0]; vi++)
            emit_unary(F_UNARY, rf[i].op, "SPECIAL", rf[i].fn, vals[vi],
                       MPFR_RNDN, 53, "specials");
    }
}

/* Moderate large-argument trig grid: covers the reduction path without the
 * astronomically expensive huge-exponent domain. */
static void block_trig_grid(void) {
    static const struct { const char* op; unary_fn fn; } t3[] = {
        {"SIN", unary_SIN}, {"COS", unary_COS}, {"TAN", unary_TAN},
    };
    static const char* xs[3] = {"1234567.891", "0.7853981633974483", "3.141592653589793"};
    static const int precs[2] = {53, 113};
    size_t i;
    int pi, xi;
    for (i = 0; i < 3; i++)
        for (xi = 0; xi < 3; xi++)
            for (pi = 0; pi < 2; pi++)
                emit_unary(F_UNARY, t3[i].op, "GRID", t3[i].fn, xs[xi], MPFR_RNDN,
                           (mpfr_prec_t)precs[pi], "trig grid");
    for (xi = 0; xi < 3; xi++) {
        for (pi = 0; pi < 2; pi++) {
            mpfr_t x, rop, rop2;
            char xa[BUFSZ], v1[BUFSZ], v2[BUFSZ], id[32];
            mpfr_flags_t saved, delta;
            mpfr_init2(x, (mpfr_prec_t)precs[pi]);
            mpfr_init2(rop, (mpfr_prec_t)precs[pi]);
            mpfr_init2(rop2, (mpfr_prec_t)precs[pi]);
            set_seed(x, xs[xi]);
            saved = flags_begin();
            pair_SIN_COS(rop, rop2, x, MPFR_RNDN);
            delta = flags_end(saved);
            token_of(x, xa); token_of(rop, v1); token_of(rop2, v2);
            check_literal(xa, x, "SIN_COS"); check_literal(v1, rop, "SIN_COS");
            check_literal(v2, rop2, "SIN_COS");
            row_id(F_PAIR, id, sizeof id);
            of_row(F_PAIR, "%s\tSIN_COS\tGRID\t%ld\t%ld\tN\t%s\tREG\t%s\t%s\t%d\t%u\ttrig grid",
                   id, (long)precs[pi], (long)precs[pi], xa, v1, v2,
                   (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
            mpfr_clear(rop2); mpfr_clear(rop); mpfr_clear(x);
        }
    }
}

static void block_scalar(void) {
    static const struct { const char* op; scalar_fn fn; const char* x; long long k; } t[] = {
        {"ADD", scalar_ADD, "1.5", 3}, {"ADD", scalar_ADD, "-2.5", -7}, {"ADD", scalar_ADD, "0.3", 10},
        {"SUB", scalar_SUB, "1.5", 3}, {"SUB", scalar_SUB, "-2.5", -7}, {"SUB", scalar_SUB, "0.3", 10},
        {"MUL", scalar_MUL, "1.5", 3}, {"MUL", scalar_MUL, "-2.5", -7}, {"MUL", scalar_MUL, "0.3", 10},
        {"DIV", scalar_DIV, "1.5", 3}, {"DIV", scalar_DIV, "-2.5", 7}, {"DIV", scalar_DIV, "0.3", 10},
        {"MUL_2EXP", scalar_MUL_2EXP, "1.5", 0}, {"MUL_2EXP", scalar_MUL_2EXP, "-2.5", 5},
        {"MUL_2EXP", scalar_MUL_2EXP, "0.3", 129},
        {"DIV_2EXP", scalar_DIV_2EXP, "1.5", 0}, {"DIV_2EXP", scalar_DIV_2EXP, "-2.5", 5},
        {"DIV_2EXP", scalar_DIV_2EXP, "0.3", 129},
        {"ROOTN", scalar_ROOTN, "1.5", 2}, {"ROOTN", scalar_ROOTN, "2.25", 3}, {"ROOTN", scalar_ROOTN, "0.5", 7},
        {"COMPOUND", scalar_COMPOUND, "0.1", 0}, {"COMPOUND", scalar_COMPOUND, "0.5", 3},
        {"COMPOUND", scalar_COMPOUND, "1.5", 10},
    };
    static const struct { const char* x; long long k; } sp[] = {
        {"@nan@", 3}, {"@inf+", 3}, {"@inf-", 3}, {"@zero+", 3}, {"@zero-", 3},
        {"@maxfin@", 1}, {"@tiniest@", 1}, {"1.5", 0}, {"1.5", -2}, {"-1.5", 7},
    };
    size_t i;
    int pi, ri;
    for (i = 0; i < sizeof t / sizeof t[0]; i++) {
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                mpfr_rnd_t r = core_rnds[ri];
                mpfr_t x, rop, tmp;
                char xa[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(tmp, 65);
                set_seed(x, t[i].x);
                saved = flags_begin();
                t[i].fn(rop, x, t[i].k, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(rop, va);
                check_literal(xa, x, t[i].op); check_literal(va, rop, t[i].op);
                row_id(F_SCALAR, id, sizeof id);
                of_row(F_SCALAR, "%s\t%s\tCORE\t%ld\t%ld\t%s\t%s\t%lld\tREG\t%s\t%d\t%u\tcore sweep",
                       id, t[i].op, (long)p, (long)p, rnd_name(r), xa, t[i].k, va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                (void)tmp;
                mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
            }
        }
    }
    /* Scalar specials are emitted per operation to exercise every scalar macro. */
    {
        static const struct { const char* op; scalar_fn fn; } ops[] = {
            {"ADD", scalar_ADD}, {"SUB", scalar_SUB}, {"MUL", scalar_MUL}, {"DIV", scalar_DIV},
            {"MUL_2EXP", scalar_MUL_2EXP}, {"DIV_2EXP", scalar_DIV_2EXP},
            {"ROOTN", scalar_ROOTN}, {"COMPOUND", scalar_COMPOUND},
        };
        size_t oi, si;
        for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
            for (si = 0; si < sizeof sp / sizeof sp[0]; si++) {
                mpfr_prec_t p = (mpfr_prec_t)(si < 5 ? spec_pass_prec[0] : spec_pass_prec[1]);
                mpfr_rnd_t r = (si < 5) ? MPFR_RNDN : MPFR_RNDZ;
                long long k = sp[si].k;
                /* Keep k inside each operation's contract domain: scaling,
                 * compound and root take non-negative degrees (root >= 1). */
                if (k < 0 && (strcmp(ops[oi].op, "MUL_2EXP") == 0 ||
                              strcmp(ops[oi].op, "DIV_2EXP") == 0 ||
                              strcmp(ops[oi].op, "COMPOUND") == 0 ||
                              strcmp(ops[oi].op, "ROOTN") == 0))
                    k = -k;
                if (strcmp(ops[oi].op, "ROOTN") == 0 && k < 1) k = 1;
                mpfr_t x, rop, tmp;
                char xa[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(tmp, 65);
                set_seed(x, sp[si].x);
                saved = flags_begin();
                ops[oi].fn(rop, x, k, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(rop, va);
                row_id(F_SCALAR, id, sizeof id);
                of_row(F_SCALAR, "%s\t%s\tSPECIAL\t%ld\t%ld\t%s\t%s\t%lld\tREG\t%s\t%d\t%u\tspecials",
                       id, ops[oi].op, (long)p, (long)p, rnd_name(r), xa, k, va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
            }
        }
    }
}

static uint64_t dbl_bits(double d) {
    uint64_t u;
    memcpy(&u, &d, sizeof u);
    return u;
}

static uint32_t flt_bits(float f) {
    uint32_t u;
    memcpy(&u, &f, sizeof u);
    return u;
}

static void block_double(void) {
    static const struct { const char* op; double_fn fn; } ops[] = {
        {"ADD", double_ADD}, {"SUB", double_SUB}, {"MUL", double_MUL}, {"DIV", double_DIV},
    };
    static const double dv[3] = {0.1, -0.75, 2.5};
    static const char* xv[3] = {"1.5", "-0.3", "2.75"};
    static const char* spx[10] = {
        "@nan@", "@inf+", "@inf-", "@zero+", "@zero-",
        "@maxfin@", "@tiniest@", "1.5", "0.5", "-1.5",
    };
    static const double spd[6] = {0.1, -0.75, 2.5, 0.0, -0.0, 5e-324};
    size_t oi, i;
    int pi, ri, pass;
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                for (i = 0; i < 3; i++) {
                    mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                    mpfr_rnd_t r = core_rnds[ri];
                    mpfr_t x, rop, tmp;
                    char xa[BUFSZ], va[BUFSZ], id[32];
                    mpfr_flags_t saved, delta;
                    mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(tmp, 53);
                    set_seed(x, xv[i]);
                    saved = flags_begin();
                    ops[oi].fn(rop, x, dv[i], r);
                    delta = flags_end(saved);
                    token_of(x, xa); token_of(rop, va);
                    check_literal(xa, x, ops[oi].op); check_literal(va, rop, ops[oi].op);
                    row_id(F_DOUBLE, id, sizeof id);
                    of_row(F_DOUBLE, "%s\t%s\tCORE\t%ld\t%ld\t%s\t%s\t%016llx\tREG\t%s\t%d\t%u\tcore sweep",
                           id, ops[oi].op, (long)p, (long)p, rnd_name(r), xa,
                           (unsigned long long)dbl_bits(dv[i]), va,
                           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                    (void)tmp;
                    mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
                }
            }
        }
        for (i = 0; i < 10; i++) {
            for (pass = 0; pass < 2; pass++) {
                mpfr_prec_t p = (mpfr_prec_t)spec_pass_prec[pass];
                mpfr_rnd_t r = pass == 0 ? MPFR_RNDN : MPFR_RNDZ;
                mpfr_t x, rop, tmp;
                char xa[BUFSZ], va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(tmp, 53);
                set_seed(x, spx[i]);
                saved = flags_begin();
                ops[oi].fn(rop, x, 0.5, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(rop, va);
                check_literal(xa, x, ops[oi].op); check_literal(va, rop, ops[oi].op);
                row_id(F_DOUBLE, id, sizeof id);
                of_row(F_DOUBLE, "%s\t%s\tSPECIAL\t%ld\t%ld\t%s\t%s\t%016llx\tREG\t%s\t%d\t%u\tspecials",
                       id, ops[oi].op, (long)p, (long)p, rnd_name(r), xa,
                       (unsigned long long)dbl_bits(0.5), va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
            }
        }
        for (i = 0; i < 6; i++) {
            mpfr_t x, rop, tmp;
            char xa[BUFSZ], va[BUFSZ], id[32];
            mpfr_flags_t saved, delta;
            mpfr_init2(x, 53); mpfr_init2(rop, 53); mpfr_init2(tmp, 53);
            set_seed(x, "1.5");
            saved = flags_begin();
            ops[oi].fn(rop, x, spd[i], MPFR_RNDN);
            delta = flags_end(saved);
            token_of(x, xa); token_of(rop, va);
            row_id(F_DOUBLE, id, sizeof id);
            of_row(F_DOUBLE, "%s\t%s\tSPECIAL\t53\t53\tN\t%s\t%016llx\tREG\t%s\t%d\t%u\tspecials",
                   id, ops[oi].op, xa, (unsigned long long)dbl_bits(spd[i]), va,
                   (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
            mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
        }
    }
}

static void block_pair(void) {
    static const struct { const char* op; pair_fn fn; } ops[] = {
        {"MODF", pair_MODF}, {"SIN_COS", pair_SIN_COS},
    };
    static const char* xv[3] = {"1.5", "-2.75", "0.3"};
    static const char* spx[10] = {
        "@nan@", "@inf+", "@inf-", "@zero+", "@zero-",
        "@maxfin@", "@tiniest@", "1.5", "0.5", "-1.5",
    };
    size_t oi, i;
    int pi, ri, pass;
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                for (i = 0; i < 3; i++) {
                    mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                    mpfr_rnd_t r = core_rnds[ri];
                    mpfr_t x, rop, rop2;
                    char xa[BUFSZ], v1[BUFSZ], v2[BUFSZ], id[32];
                    mpfr_flags_t saved, delta;
                    mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(rop2, p);
                    set_seed(x, xv[i]);
                    saved = flags_begin();
                    ops[oi].fn(rop, rop2, x, r);
                    delta = flags_end(saved);
                    token_of(x, xa); token_of(rop, v1); token_of(rop2, v2);
                    check_literal(xa, x, ops[oi].op);
                    check_literal(v1, rop, ops[oi].op); check_literal(v2, rop2, ops[oi].op);
                    row_id(F_PAIR, id, sizeof id);
                    of_row(F_PAIR, "%s\t%s\tCORE\t%ld\t%ld\t%s\t%s\tREG\t%s\t%s\t%d\t%u\tcore sweep",
                           id, ops[oi].op, (long)p, (long)p, rnd_name(r), xa, v1, v2,
                           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                    mpfr_clear(rop2); mpfr_clear(rop); mpfr_clear(x);
                }
            }
        }
        for (i = 0; i < 10; i++) {
            if (strcmp(ops[oi].op, "SIN_COS") == 0 && strcmp(spx[i], "@maxfin@") == 0)
                continue; /* huge-argument reduction is out of scope (cost) */
            for (pass = 0; pass < 2; pass++) {
                mpfr_prec_t p = (mpfr_prec_t)spec_pass_prec[pass];
                mpfr_rnd_t r = pass == 0 ? MPFR_RNDN : MPFR_RNDZ;
                mpfr_t x, rop, rop2;
                char xa[BUFSZ], v1[BUFSZ], v2[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(rop, p); mpfr_init2(rop2, p);
                set_seed(x, spx[i]);
                saved = flags_begin();
                ops[oi].fn(rop, rop2, x, r);
                delta = flags_end(saved);
                token_of(x, xa); token_of(rop, v1); token_of(rop2, v2);
                check_literal(v1, rop, ops[oi].op); check_literal(v2, rop2, ops[oi].op);
                row_id(F_PAIR, id, sizeof id);
                of_row(F_PAIR, "%s\t%s\tSPECIAL\t%ld\t%ld\t%s\t%s\tREG\t%s\t%s\t%d\t%u\tspecials",
                       id, ops[oi].op, (long)p, (long)p, rnd_name(r), xa, v1, v2,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(rop2); mpfr_clear(rop); mpfr_clear(x);
            }
        }
    }
}

static void block_const(void) {
    static const struct { const char* op; const_fn fn; } ops[] = {
        {"PI", const_PI}, {"E", const_E},
    };
    size_t oi;
    int pi, ri;
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (pi = 0; pi < 6; pi++) {
            for (ri = 0; ri < 5; ri++) {
                mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
                mpfr_rnd_t r = core_rnds[ri];
                mpfr_t rop, tmp;
                char va[BUFSZ], id[32];
                mpfr_flags_t saved, delta;
                mpfr_init2(rop, p); mpfr_init2(tmp, 53);
                saved = flags_begin();
                ops[oi].fn(rop, r);
                delta = flags_end(saved);
                token_of(rop, va);
                check_literal(va, rop, ops[oi].op);
                row_id(F_CONST, id, sizeof id);
                of_row(F_CONST, "%s\t%s\tCORE\t%ld\t%s\tREG\t%s\t%d\t%u\tcore sweep",
                       id, ops[oi].op, (long)p, rnd_name(r), va,
                       (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
                mpfr_clear(tmp); mpfr_clear(rop);
            }
        }
    }
}

static void block_predicates(void) {
    static const struct { const char* x; const char* y; } pairs[16] = {
        {"1.5", "1.5"}, {"1.5", "2.5"}, {"2.5", "1.5"},
        {"@nan@", "1.5"}, {"1.5", "@nan@"}, {"@nan@", "@nan@"},
        {"@zero+", "@zero-"}, {"@zero-", "@zero+"},
        {"@inf+", "@inf+"}, {"@inf+", "@inf-"}, {"@inf-", "@inf+"},
        {"@zero+", "@zero+"}, {"@zero-", "@zero-"},
        {"2", "2.0000000000000001"}, {"@maxfin@", "@inf+"}, {"1.5", "-2.5"},
    };
    static const struct { const char* op; int (*fn)(mpfr_srcptr, mpfr_srcptr); } ops[] = {
#define K(name, id, call) { #name, pred_##name },
        KMP_PREDICATE_OPS(K)
#undef K
    };
    size_t oi, pi_;
    int pass;
    for (oi = 0; oi < sizeof ops / sizeof ops[0]; oi++) {
        for (pass = 0; pass < 2; pass++) {
            for (pi_ = 0; pi_ < 16; pi_++) {
                mpfr_prec_t p = pass == 0 ? 53 : 113;
                mpfr_t x, y;
                char xa[BUFSZ], ya[BUFSZ], id[32];
                int hit = 0;
                mpfr_flags_t saved, delta;
                mpfr_init2(x, p); mpfr_init2(y, p);
                set_seed(x, pairs[pi_].x); set_seed(y, pairs[pi_].y);
                saved = flags_begin();
                hit = ops[oi].fn(x, y);
                delta = flags_end(saved);
                token_of(x, xa); token_of(y, ya);
                check_literal(xa, x, ops[oi].op); check_literal(ya, y, ops[oi].op);
                row_id(F_PRED, id, sizeof id);
                of_row(F_PRED, "%s\t%s\tPRED\t%ld\t%ld\t%s\t%s\tBOOL\t%d\t0\t%u\tpredicates",
                       id, ops[oi].op, (long)p, (long)p, xa, ya, hit, (unsigned)delta);
                mpfr_clear(y); mpfr_clear(x);
            }
        }
    }
}

static void block_cmp(void) {
    static const struct { const char* x; const char* y; } pairs[14] = {
        {"1.5", "1.5"}, {"1.5", "2.5"}, {"2.5", "1.5"},
        {"@inf+", "@inf+"}, {"@inf+", "@inf-"}, {"@inf-", "@inf+"},
        {"@inf-", "1.5"}, {"1.5", "@inf+"},
        {"2", "2.0000000000000001"}, {"@maxfin@", "@inf+"}, {"@maxfin@", "1.5"},
        {"@tiniest@", "1.5"}, {"1.5", "-2.5"}, {"-2.5", "1.5"},
    };
    static const struct { const char* x; const char* y; long long k; } uq[12] = {
        {"1.5", "1.5", 0}, {"1.5", "1.5", 1}, {"1.4375", "1.5", 2}, {"1.4375", "1.5", 1},
        {"1.5", "1.75", 3}, {"@zero+", "@zero-", 0}, {"@zero+", "@zero-", 1},
        {"@zero-", "@zero+", 3}, {"@inf+", "@inf+", 0}, {"@inf+", "@inf+", 5},
        {"@inf+", "1.5", 0}, {"1.5", "@tiniest@", 0},
    };
    static const struct { const char* x; const char* y; } tp[16] = {
        {"1.5", "1.5"}, {"1.5", "2.5"}, {"2.5", "1.5"},
        {"@nan@", "1.5"}, {"1.5", "@nan@"}, {"@nan@", "@nan@"},
        {"@zero+", "@zero-"}, {"@zero-", "@zero+"},
        {"@inf+", "@inf+"}, {"@inf+", "@inf-"}, {"@inf-", "@inf+"},
        {"@zero+", "@zero+"}, {"@zero-", "@zero-"},
        {"2", "2.0000000000000001"}, {"@maxfin@", "@inf+"}, {"1.5", "@nan@"},
    };
    static const int precs[3] = {53, 64, 113};
    size_t i;
    int pass;
    for (pass = 0; pass < 3; pass++) {
        for (i = 0; i < 14; i++) {
            mpfr_prec_t p = (mpfr_prec_t)precs[pass];
            mpfr_t x, y;
            char xa[BUFSZ], ya[BUFSZ], id[32];
            int c, sign;
            mpfr_flags_t saved, delta;
            mpfr_init2(x, p); mpfr_init2(y, p);
            set_seed(x, pairs[i].x); set_seed(y, pairs[i].y);
            saved = flags_begin();
            c = mpfr_cmp(x, y);
            delta = flags_end(saved);
            sign = c > 0 ? 1 : (c < 0 ? -1 : 0);
            token_of(x, xa); token_of(y, ya);
            check_literal(xa, x, "CMP"); check_literal(ya, y, "CMP");
            row_id(F_CMP, id, sizeof id);
            of_row(F_CMP, "%s\tCMP\tCMP\t%ld\t%ld\t%s\t%s\t\tINT\t%d\t0\t%u\tcompare",
                   id, (long)p, (long)p, xa, ya, sign, (unsigned)delta);
            mpfr_clear(y); mpfr_clear(x);
        }
    }
    for (pass = 0; pass < 2; pass++) {
        for (i = 0; i < 12; i++) {
            mpfr_prec_t p = pass == 0 ? 53 : 113;
            mpfr_t x, y;
            char xa[BUFSZ], ya[BUFSZ], id[32];
            int hit;
            mpfr_flags_t saved, delta;
            mpfr_init2(x, p); mpfr_init2(y, p);
            set_seed(x, uq[i].x); set_seed(y, uq[i].y);
            saved = flags_begin();
            hit = mpfr_eq(x, y, (unsigned long)uq[i].k) ? 1 : 0;
            delta = flags_end(saved);
            token_of(x, xa); token_of(y, ya);
            check_literal(xa, x, "EQUAL_ULPS"); check_literal(ya, y, "EQUAL_ULPS");
            row_id(F_CMP, id, sizeof id);
            of_row(F_CMP, "%s\tEQUAL_ULPS\tCMP\t%ld\t%ld\t%s\t%s\t%lld\tBOOL\t%d\t0\t%u\tequalUlps",
                   id, (long)p, (long)p, xa, ya, uq[i].k, hit, (unsigned)delta);
            mpfr_clear(y); mpfr_clear(x);
        }
    }
    for (pass = 0; pass < 2; pass++) {
        for (i = 0; i < 16; i++) {
            mpfr_prec_t p = pass == 0 ? 53 : 113;
            mpfr_t x, y;
            char xa[BUFSZ], ya[BUFSZ], id[32];
            int hit;
            mpfr_flags_t saved, delta;
            mpfr_init2(x, p); mpfr_init2(y, p);
            set_seed(x, tp[i].x); set_seed(y, tp[i].y);
            saved = flags_begin();
            hit = mpfr_total_order_p(x, y) ? 1 : 0;
            delta = flags_end(saved);
            token_of(x, xa); token_of(y, ya);
            check_literal(xa, x, "TOTAL_ORDER"); check_literal(ya, y, "TOTAL_ORDER");
            row_id(F_CMP, id, sizeof id);
            of_row(F_CMP, "%s\tTOTAL_ORDER\tCMP\t%ld\t%ld\t%s\t%s\t\tBOOL\t%d\t0\t%u\ttotalOrder",
                   id, (long)p, (long)p, xa, ya, hit, (unsigned)delta);
            mpfr_clear(y); mpfr_clear(x);
        }
    }
}

/* ------------------------------------------------- dot, sum, factorial ---- */

#define MAXLIST 8
#define MAXLIST2 16

static void cell_of_values(mpfr_srcptr* vals, int n, char* cell, size_t cap) {
    size_t used = 0;
    int i;
    cell[0] = '\0';
    for (i = 0; i < n; i++) {
        char tok[BUFSZ];
        int w;
        token_of(vals[i], tok);
        w = snprintf(cell + used, cap - used, "%s%s", i == 0 ? "" : " ", tok);
        if (w < 0 || (size_t)w >= cap - used) {
            fprintf(stderr, "list cell overflow\n");
            exit(2);
        }
        used += (size_t)w;
    }
}

static void emit_dotsum_row(const char* op, const char* cat, mpfr_prec_t p, mpfr_rnd_t r,
                            const char** as, int na, const char** bs, int nb,
                            int call_it, const char* note) {
    mpfr_t vals[MAXLIST2];
    mpfr_srcptr vp[MAXLIST2];
    char acell[8192], bcell[8192], va[BUFSZ], id[32];
    mpfr_flags_t saved = 0, delta = 0;
    int i;
    if (na > MAXLIST || nb > MAXLIST) {
        fprintf(stderr, "list too long\n");
        exit(2);
    }
    for (i = 0; i < na; i++) {
        mpfr_init2(vals[i], p);
        set_seed(vals[i], as[i]);
        token_of(vals[i], va);
        check_literal(va, vals[i], op);
    }
    for (i = na; i < na + nb; i++) {
        mpfr_init2(vals[i], p);
        set_seed(vals[i], bs[i - na]);
        token_of(vals[i], va);
        check_literal(va, vals[i], op);
    }
    for (i = 0; i < na + nb; i++) vp[i] = vals[i];
    cell_of_values(vp, na, acell, sizeof acell);
    cell_of_values(vp + na, nb, bcell, sizeof bcell);
    row_id(F_DOTSUM, id, sizeof id);
    if (!call_it) {
        of_row(F_DOTSUM, "%s\t%s\t%s\t%ld\t%s\t%s\t%s\tIAE\t\t0\t0\t%s",
               id, op, cat, (long)p, rnd_name(r), acell, bcell, note);
    } else {
        mpfr_t rop;
        mpfr_init2(rop, p);
        saved = flags_begin();
        if (strcmp(op, "DOT") == 0) {
            mpfr_ptr ta[MAXLIST], tb[MAXLIST];
            for (i = 0; i < na; i++) { ta[i] = vals[i]; tb[i] = vals[na + i]; }
            mpfr_dot(rop, (const mpfr_ptr*)ta, (const mpfr_ptr*)tb, (unsigned long)na, r);
        } else {
            mpfr_ptr ta[MAXLIST];
            for (i = 0; i < na; i++) ta[i] = vals[i];
            mpfr_sum(rop, (const mpfr_ptr*)ta, (unsigned long)na, r);
        }
        delta = flags_end(saved);
        token_of(rop, va);
        check_literal(va, rop, op);
        of_row(F_DOTSUM, "%s\t%s\t%s\t%ld\t%s\t%s\t%s\tREG\t%s\t%d\t%u\t%s",
               id, op, cat, (long)p, rnd_name(r), acell, bcell, va,
               (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta, note);
        mpfr_clear(rop);
    }
    for (i = 0; i < na + nb; i++) mpfr_clear(vals[i]);
}

static void block_dotsum(void) {
    static const char* cycle_a[3] = {"1.5", "0.3", "-2.5"};
    static const char* cycle_b[3] = {"0.7", "-1.1", "0.9"};
    static const char* special_a[5][4] = {
        {"@nan@", "1.5", "", ""},
        {"@inf+", "1.5", "2", ""},
        {"@zero-", "@zero+", "", ""},
        {"@tiniest@", "1.5", "", ""},
        {"1.5", "-1.5", "", ""},
    };
    static const int special_n[5] = {2, 3, 2, 2, 2};
    static const char* special_b[5][4] = {
        {"1.5", "1.5", "", ""},
        {"1.5", "1.5", "1.5", ""},
        {"1.5", "@maxfin@", "", ""},
        {"1", "1", "", ""},
        {"2.5", "2.5", "", ""},
    };
    static const int dot_precs[4] = {24, 53, 113, 256};
    static const int ns[3] = {2, 3, 8};
    int ni, pi, ri, i, pass;
    for (ni = 0; ni < 3; ni++) {
        for (pi = 0; pi < 4; pi++) {
            for (ri = 0; ri < 5; ri++) {
                const char* as[MAXLIST];
                const char* bs[MAXLIST];
                for (i = 0; i < ns[ni]; i++) {
                    as[i] = cycle_a[i % 3];
                    bs[i] = cycle_b[(i + 1) % 3];
                }
                emit_dotsum_row("DOT", "CORE", (mpfr_prec_t)dot_precs[pi],
                                core_rnds[ri], as, ns[ni], bs, ns[ni], 1, "core sweep");
                emit_dotsum_row("SUM", "CORE", (mpfr_prec_t)dot_precs[pi],
                                core_rnds[ri], as, ns[ni], NULL, 0, 1, "core sweep");
            }
        }
    }
    /* Exponent-domain boundaries: EXP(a)+EXP(b) at and beyond the guard edges. */
    {
        static const int hi_ok[2] = {536870910, 536870911};
        static const int hi_bad[2] = {536870911, 536870911};
        static const int lo_ok[2] = {-536870912, -536870912};
        static const int lo_bad[2] = {-536870912, -536870913};
        static const char* names[4] = {"boundary hi legal", "boundary hi illegal",
                                       "boundary lo legal", "boundary lo illegal"};
        const int* sets[4] = {hi_ok, hi_bad, lo_ok, lo_bad};
        int si;
        for (si = 0; si < 4; si++) {
            for (pass = 0; pass < 2; pass++) {
                mpfr_t a, b;
                char atok[BUFSZ], btok[BUFSZ], acell[BUFSZ * 2], bcell[BUFSZ * 2];
                char va[BUFSZ], id[32];
                mpfr_rnd_t r = pass == 0 ? MPFR_RNDN : MPFR_RNDZ;
                int bad = (si == 1 || si == 3);
                mpfr_init2(a, 64);
                mpfr_init2(b, 64);
                mpfr_set_ui_2exp(a, 1, sets[si][0], MPFR_RNDN);
                mpfr_set_ui_2exp(b, 1, sets[si][1], MPFR_RNDN);
                token_of(a, atok);
                token_of(b, btok);
                snprintf(acell, sizeof acell, "%s", atok);
                snprintf(bcell, sizeof bcell, "%s", btok);
                row_id(F_DOTSUM, id, sizeof id);
                if (bad) {
                    of_row(F_DOTSUM, "%s\tDOT\tBOUNDARY\t64\t%s\t%s\t%s\tIAE\t\t0\t0\t%s",
                           id, rnd_name(r), acell, bcell, names[si]);
                } else {
                    mpfr_t rop;
                    mpfr_ptr ta[1], tb[1];
                    mpfr_flags_t saved, delta;
                    mpfr_init2(rop, 64);
                    ta[0] = a; tb[0] = b;
                    saved = flags_begin();
                    mpfr_dot(rop, (const mpfr_ptr*)ta, (const mpfr_ptr*)tb, 1, r);
                    delta = flags_end(saved);
                    token_of(rop, va);
                    check_literal(va, rop, "DOT");
                    of_row(F_DOTSUM, "%s\tDOT\tBOUNDARY\t64\t%s\t%s\t%s\tREG\t%s\t%d\t%u\t%s",
                           id, rnd_name(r), acell, bcell, va,
                           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta, names[si]);
                    mpfr_clear(rop);
                }
                mpfr_clear(b); mpfr_clear(a);
            }
        }
    }
    for (pass = 0; pass < 2; pass++) {
        for (i = 0; i < 5; i++) {
            const char* as[MAXLIST];
            const char* bs[MAXLIST];
            int j;
            for (j = 0; j < special_n[i]; j++) as[j] = special_a[i][j];
            for (j = 0; j < special_n[i]; j++) bs[j] = special_b[i][j];
            emit_dotsum_row("DOT", "SPECIAL", 64, pass == 0 ? MPFR_RNDN : MPFR_RNDZ,
                            as, special_n[i], bs, special_n[i], 1, "specials");
            emit_dotsum_row("SUM", "SPECIAL", 64, pass == 0 ? MPFR_RNDN : MPFR_RNDZ,
                            as, special_n[i], NULL, 0, 1, "specials");
        }
    }
}

static void block_factorial(void) {
    static const int ns[7] = {0, 1, 5, 20, 50, 100, 170};
    static const int precs[3] = {53, 113, 256};
    int i, pi;
    for (pi = 0; pi < 3; pi++) {
        for (i = 0; i < 7; i++) {
            mpfr_prec_t p = (mpfr_prec_t)precs[pi];
            mpfr_t rop;
            char va[BUFSZ], id[32];
            mpfr_flags_t saved, delta;
            mpfr_init2(rop, p);
            saved = flags_begin();
            mpfr_fac_ui(rop, (unsigned long)ns[i], MPFR_RNDN);
            delta = flags_end(saved);
            token_of(rop, va);
            check_literal(va, rop, "FACTORIAL");
            row_id(F_FACT, id, sizeof id);
            of_row(F_FACT, "%s\tFACTORIAL\tCORE\t%d\t%ld\tN\tREG\t%s\t%d\t%u\tfactorial",
                   id, ns[i], (long)p, va,
                   (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
            mpfr_clear(rop);
        }
    }
}

/* ------------------------------------------------------------ conversions -- */

static void conv_emit(const char* op, const char* cat, long prec, const char* rnd,
                      const char* input, const char* kind, const char* value,
                      mpfr_flags_t delta, const char* note) {
    char id[32];
    row_id(F_CONV, id, sizeof id);
    of_row(F_CONV, "%s\t%s\t%s\t%ld\t%s\t%s\t%s\t%s\t%d\t%u\t%s",
           id, op, cat, prec, rnd, input, kind, value,
           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta, note);
}

static void block_conversions(void) {
    static const char* longs[7] = {
        "0", "1", "-1", "9007199254740993", "-9223372036854775808",
        "9223372036854775807", "12345678901234",
    };
    static const int long_precs[4] = {24, 53, 64, 113};
    static const mpfr_rnd_t rnd3[3] = {MPFR_RNDN, MPFR_RNDZ, MPFR_RNDA};
    static const char* ulongs[4] = {
        "0", "1", "9223372036854775808", "18446744073709551615",
    };
    static const int ulong_precs[3] = {53, 64, 113};
    static const mpfr_rnd_t rnd2[2] = {MPFR_RNDN, MPFR_RNDZ};
    static const struct { unsigned long long bits; } dbls[9] = {
        {0x3FB999999999999AULL}, {0xC004000000000000ULL}, {0x7FE1CCF385EBC8A0ULL},
        {0x0000000000000001ULL}, {0x7FEFFFFFFFFFFFFFULL}, {0x7FF0000000000000ULL},
        {0x7FF8000000000000ULL}, {0x8000000000000000ULL}, {0x400921FB54442D18ULL},
    };
    static const unsigned int flts[6] = {
        0x3DCCCCCDu, 0xBFC00000u, 0x7F800000u, 0x7FC00000u, 0x80000000u, 0x7F7FFFFFu,
    };
    static const char* bigs[6] = {
        "0", "1", "-1", "1267650600228229401496703205377",
        "-100000000000000000000000000000000000000000000000007", "18446744073709551616",
    };
    static const int big_precs[3] = {53, 113, 256};
    static const char* to_pool[10] = {
        "1.5", "0.1", "@inf+", "@inf-", "@zero+", "@zero-",
        "@nan@", "@maxfin@", "@tiniest@", "2.5",
    };
    static const char* tolong_pool[12] = {
        "0", "1.5", "-2.5", "4611686018427387904", "-4611686018427387904",
        "9223372036854775807", "-9223372036854775808", "9223372036854775808",
        "1e30", "@nan@", "@inf+", "-1e300",
    };
    static const char* toint_pool[10] = {
        "0", "1.5", "-2.5", "2147483647", "2147483648",
        "-2147483648", "-2147483649", "1e30", "@nan@", "@inf+",
    };
    static const mpfr_rnd_t rnd4[4] = {MPFR_RNDN, MPFR_RNDZ, MPFR_RNDD, MPFR_RNDA};
    size_t i;
    int pi, ri;

    for (pi = 0; pi < 4; pi++) {
        for (ri = 0; ri < 3; ri++) {
            for (i = 0; i < 7; i++) {
                mpfr_t rop;
                char va[BUFSZ];
                mpfr_flags_t saved, delta;
                mpfr_init2(rop, (mpfr_prec_t)long_precs[pi]);
                saved = flags_begin();
                mpfr_set_sj(rop, (intmax_t)strtoll(longs[i], NULL, 10), rnd3[ri]);
                delta = flags_end(saved);
                token_of(rop, va);
                check_literal(va, rop, "OF_LONG");
                conv_emit("OF_LONG", "CONV", long_precs[pi], rnd_name(rnd3[ri]), longs[i],
                          "REG", va, delta, "of(Long)");
                mpfr_clear(rop);
            }
        }
    }
    for (pi = 0; pi < 3; pi++) {
        for (ri = 0; ri < 2; ri++) {
            for (i = 0; i < 4; i++) {
                mpfr_t rop;
                char va[BUFSZ];
                mpfr_flags_t saved, delta;
                mpfr_init2(rop, (mpfr_prec_t)ulong_precs[pi]);
                saved = flags_begin();
                mpfr_set_uj(rop, (uintmax_t)strtoull(ulongs[i], NULL, 10), rnd2[ri]);
                delta = flags_end(saved);
                token_of(rop, va);
                check_literal(va, rop, "OF_ULONG");
                conv_emit("OF_ULONG", "CONV", ulong_precs[pi], rnd_name(rnd2[ri]), ulongs[i],
                          "REG", va, delta, "of(ULong)");
                mpfr_clear(rop);
            }
        }
    }
    for (pi = 0; pi < 4; pi++) {
        for (ri = 0; ri < 2; ri++) {
            for (i = 0; i < 9; i++) {
                mpfr_t rop;
                double d;
                char va[BUFSZ], in[32];
                mpfr_flags_t saved, delta;
                memcpy(&d, &dbls[i].bits, sizeof d);
                mpfr_init2(rop, (mpfr_prec_t)long_precs[pi]);
                saved = flags_begin();
                mpfr_set_d(rop, d, rnd2[ri]);
                delta = flags_end(saved);
                token_of(rop, va);
                check_literal(va, rop, "OF_DOUBLE");
                snprintf(in, sizeof in, "%016llx", dbls[i].bits);
                conv_emit("OF_DOUBLE", "CONV", long_precs[pi], rnd_name(rnd2[ri]),
                          in, "REG", va, delta, "of(Double)");
                mpfr_clear(rop);
            }
        }
    }
    for (pi = 0; pi < 3; pi++) {
        for (i = 0; i < 6; i++) {
            mpfr_t rop;
            float f;
            char va[BUFSZ], in[16];
            mpfr_flags_t saved, delta;
            memcpy(&f, &flts[i], sizeof f);
            mpfr_init2(rop, (mpfr_prec_t)ulong_precs[pi]);
            saved = flags_begin();
            mpfr_set_flt(rop, f, MPFR_RNDN);
            delta = flags_end(saved);
            token_of(rop, va);
            check_literal(va, rop, "OF_FLOAT");
            snprintf(in, sizeof in, "%08x", flts[i]);
            conv_emit("OF_FLOAT", "CONV", ulong_precs[pi], "N", in, "REG", va, delta, "of(Float)");
            mpfr_clear(rop);
        }
    }
    for (pi = 0; pi < 3; pi++) {
        for (ri = 0; ri < 2; ri++) {
            for (i = 0; i < 6; i++) {
                mpfr_t rop;
                mpz_t z;
                char va[BUFSZ];
                mpfr_flags_t saved, delta;
                mpz_init(z);
                mpz_set_str(z, bigs[i], 10);
                mpfr_init2(rop, (mpfr_prec_t)big_precs[pi]);
                saved = flags_begin();
                mpfr_set_z(rop, z, rnd2[ri]);
                delta = flags_end(saved);
                token_of(rop, va);
                check_literal(va, rop, "OF_BIGINT");
                conv_emit("OF_BIGINT", "CONV", big_precs[pi], rnd_name(rnd2[ri]), bigs[i],
                          "REG", va, delta, "of(BigInteger)");
                mpfr_clear(rop);
                mpz_clear(z);
            }
        }
    }
    for (ri = 0; ri < 5; ri++) {
        for (i = 0; i < 10; i++) {
            mpfr_t x;
            double d;
            char xa[BUFSZ], bits[32];
            mpfr_flags_t saved, delta;
            mpfr_init2(x, 53);
            set_seed(x, to_pool[i]);
            saved = flags_begin();
            d = mpfr_get_d(x, core_rnds[ri]);
            delta = flags_end(saved);
            token_of(x, xa);
            snprintf(bits, sizeof bits, "%016llx", (unsigned long long)dbl_bits(d));
            conv_emit("TO_DOUBLE", "CONV", 53, rnd_name(core_rnds[ri]), xa, "BITS64", bits,
                      delta, "toDouble");
            mpfr_clear(x);
        }
    }
    for (ri = 0; ri < 5; ri++) {
        for (i = 0; i < 10; i++) {
            mpfr_t x;
            float f;
            char xa[BUFSZ], bits[16];
            mpfr_flags_t saved, delta;
            mpfr_init2(x, 53);
            set_seed(x, to_pool[i]);
            saved = flags_begin();
            f = mpfr_get_flt(x, core_rnds[ri]);
            delta = flags_end(saved);
            token_of(x, xa);
            snprintf(bits, sizeof bits, "%08x", flt_bits(f));
            conv_emit("TO_FLOAT", "CONV", 53, rnd_name(core_rnds[ri]), xa, "BITS32", bits,
                      delta, "toFloat");
            mpfr_clear(x);
        }
    }
    for (ri = 0; ri < 4; ri++) {
        for (i = 0; i < 12; i++) {
            mpfr_t x;
            char xa[BUFSZ], val[64];
            mpfr_flags_t saved, delta;
            int fits;
            mpfr_init2(x, 64);
            set_seed(x, tolong_pool[i]);
            saved = flags_begin();
            fits = mpfr_fits_intmax_p(x, rnd4[ri]);
            delta = flags_end(saved);
            token_of(x, xa);
            if (fits) {
                snprintf(val, sizeof val, "%lld",
                         (long long)mpfr_get_sj(x, rnd4[ri]));
                conv_emit("TO_LONG", "CONV", 64, rnd_name(rnd4[ri]), xa, "INT64", val, delta, "toLong");
            } else {
                conv_emit("TO_LONG", "CONV", 64, rnd_name(rnd4[ri]), xa, "RANGE_EX", "", delta, "toLong");
            }
            mpfr_clear(x);
        }
    }
    for (ri = 0; ri < 4; ri++) {
        for (i = 0; i < 10; i++) {
            mpfr_t x;
            char xa[BUFSZ], val[64];
            mpfr_flags_t saved, delta;
            int ok;
            intmax_t got = 0;
            mpfr_init2(x, 64);
            set_seed(x, toint_pool[i]);
            saved = flags_begin();
            ok = mpfr_fits_intmax_p(x, rnd4[ri]);
            if (ok) got = mpfr_get_sj(x, rnd4[ri]);
            delta = flags_end(saved);
            token_of(x, xa);
            if (ok && got >= -2147483648LL && got <= 2147483647LL) {
                snprintf(val, sizeof val, "%lld", (long long)got);
                conv_emit("TO_INT", "CONV", 64, rnd_name(rnd4[ri]), xa, "INT32", val, delta, "toInt");
            } else {
                conv_emit("TO_INT", "CONV", 64, rnd_name(rnd4[ri]), xa, "RANGE_EX", "", delta, "toInt");
            }
            mpfr_clear(x);
        }
    }
    for (ri = 0; ri < 4; ri++) {
        for (i = 0; i < 10; i++) {
            mpfr_t x;
            char xa[BUFSZ];
            mpfr_flags_t saved, delta;
            int ok;
            intmax_t got = 0;
            mpfr_init2(x, 64);
            set_seed(x, to_pool[i]);
            saved = flags_begin();
            ok = mpfr_fits_intmax_p(x, rnd4[ri]);
            if (ok) got = mpfr_get_sj(x, rnd4[ri]);
            delta = flags_end(saved);
            token_of(x, xa);
            conv_emit("FITS_LONG", "CONV", 64, rnd_name(rnd4[ri]), xa, "BOOL",
                      ok ? "1" : "0", delta, "fitsLong");
            if (ok && got >= -2147483648LL && got <= 2147483647LL)
                conv_emit("FITS_INT", "CONV", 64, rnd_name(rnd4[ri]), xa, "BOOL", "1", delta, "fitsInt");
            else
                conv_emit("FITS_INT", "CONV", 64, rnd_name(rnd4[ri]), xa, "BOOL", "0", delta, "fitsInt");
            mpfr_clear(x);
        }
    }
    for (ri = 0; ri < 2; ri++) {
        for (i = 0; i < 5; i++) {
            static const char* bpool[5] = {"0", "1.5", "-2.5", "@nan@", "@inf+"};
            mpfr_t x;
            char xa[BUFSZ];
            mpfr_flags_t saved, delta;
            mpfr_init2(x, 113);
            set_seed(x, bpool[i]);
            token_of(x, xa);
            if (strcmp(bpool[i], "@nan@") == 0 || strcmp(bpool[i], "@inf+") == 0) {
                conv_emit("TO_BIGINT", "CONV", 113, rnd_name(rnd2[ri]), xa, "IAE", "", 0, "toBigInteger");
            } else {
                mpz_t z;
                char* ds;
                char val[256];
                mpz_init(z);
                saved = flags_begin();
                mpfr_get_z(z, x, rnd2[ri]);
                delta = flags_end(saved);
                ds = mpz_get_str(NULL, 10, z);
                snprintf(val, sizeof val, "%s", ds);
                mpfr_free_str(ds);
                conv_emit("TO_BIGINT", "CONV", 113, rnd_name(rnd2[ri]), xa, "BIGINT", val, delta, "toBigInteger");
                mpz_clear(z);
            }
            mpfr_clear(x);
        }
    }
}

/* ---------------------------------------------------------------- strings -- */

static int parse_status(const char* s, int base, mpfr_ptr rop, mpfr_rnd_t rnd) {
    char* ep = NULL;
    int st = -1;
    if (!(base == 0 || (base >= 2 && base <= 62))) return -2;
    (void)mpfr_strtofr(rop, s, &ep, base, rnd);
    if (ep != NULL && (const char*)ep > s) {
        st = 0;
        while (*ep == ' ' || *ep == '\t') ep++;
        if (*ep != '\0') {
            char c0 = *ep;
            st = (c0 == 'e' || c0 == 'E' || c0 == 'p' || c0 == 'P') ? -3 : -1;
        }
    }
    return st;
}

static void str_emit(const char* op, const char* cat, long prec, int base, int digits,
                     const char* style, const char* rnd, const char* input, const char* kind,
                     const char* value, const char* value2, mpfr_flags_t delta,
                     const char* note) {
    char id[32];
    row_id(F_STR, id, sizeof id);
    of_row(F_STR, "%s\t%s\t%s\t%ld\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%u\t%s",
           id, op, cat, prec, base, digits, style, rnd, input, kind, value, value2,
           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta, note);
}

static void to_decimal_string(mpfr_srcptr x, int digits, mpfr_rnd_t rnd, char* outbuf) {
    if (mpfr_nan_p(x)) { strcpy(outbuf, "NaN"); return; }
    if (mpfr_inf_p(x)) { strcpy(outbuf, mpfr_signbit(x) ? "-Infinity" : "Infinity"); return; }
    if (mpfr_zero_p(x)) { strcpy(outbuf, mpfr_signbit(x) ? "-0" : "0"); return; }
    {
        mpfr_exp_t e = 0;
        char* s = mpfr_get_str(NULL, &e, 10, (size_t)digits, x, rnd);
        const char* d;
        int neg;
        if (s == NULL) {
            fprintf(stderr, "to_decimal_string: get_str failed\n");
            exit(2);
        }
        neg = mpfr_sgn(x) < 0;
        d = (s[0] == '-') ? s + 1 : s;
        if (d[1] == '\0')
            snprintf(outbuf, BUFSZ, "%s%c.0e%lld", neg ? "-" : "", d[0], (long long)e - 1);
        else
            snprintf(outbuf, BUFSZ, "%s%c.%se%lld", neg ? "-" : "", d[0], d + 1, (long long)e - 1);
        mpfr_free_str(s);
    }
}

static void block_strings(void) {
    /* parse rows: source, base, precision, rounding */
    static const struct { const char* src; int base; int prec; mpfr_rnd_t rnd; } pr[] = {
        {"1.5", 0, 53, MPFR_RNDN}, {"3.14159", 0, 53, MPFR_RNDN}, {"-0.75", 0, 53, MPFR_RNDN},
        {"1e10", 0, 53, MPFR_RNDN}, {"0x1.8p3", 0, 53, MPFR_RNDN}, {"0x1.8p3", 0, 113, MPFR_RNDN},
        {"0b101.01", 0, 53, MPFR_RNDN}, {"0b101.01", 0, 113, MPFR_RNDN},
        {"nan", 0, 53, MPFR_RNDN}, {"inf", 0, 53, MPFR_RNDN}, {"-inf", 0, 53, MPFR_RNDN},
        {"2.5", 0, 53, MPFR_RNDZ}, {"1e999999999999999999999", 0, 53, MPFR_RNDN},
        {"abc", 0, 53, MPFR_RNDN}, {"12e", 0, 53, MPFR_RNDN}, {"0x1.8p", 0, 53, MPFR_RNDN},
        {"1.5x", 0, 53, MPFR_RNDN}, {"1.5 ", 0, 53, MPFR_RNDN},
        {"1101.01", 2, 53, MPFR_RNDN}, {"210.1", 3, 53, MPFR_RNDN},
        {"1a.2", 16, 53, MPFR_RNDN}, {"v.8", 32, 53, MPFR_RNDN},
        {"Z.5", 62, 53, MPFR_RNDN}, {"zzz", 62, 113, MPFR_RNDN},
    };
    /* rounding tokens cycled over the table for extra width */
    size_t i;
    for (i = 0; i < sizeof pr / sizeof pr[0]; i++) {
        mpfr_t rop;
        char va[BUFSZ];
        int st;
        mpfr_init2(rop, (mpfr_prec_t)pr[i].prec);
        st = parse_status(pr[i].src, pr[i].base, rop, pr[i].rnd);
        {
            const char* kind;
            const char* val = "";
            if (st == 0) { token_of(rop, va); val = va; kind = "REG"; }
            else if (st == -2) kind = "PARSE_BASE";
            else if (st == -3) kind = "PARSE_EXP";
            else kind = "PARSE_MALFORMED";
            str_emit("PARSE", "STR", pr[i].prec, pr[i].base, -1, "", rnd_name(pr[i].rnd),
                     pr[i].src, kind, val, "", 0, "parse");
        }
        mpfr_clear(rop);
    }
    /* pre-guard rows mirrored from the wrapper (no FFI call in the wrapper) */
    str_emit("PARSE", "STR", 53, 0, -1, "", "N", "", "PARSE_MALFORMED", "", "", 0, "empty input");
    str_emit("PARSE", "STR", 53, 63, -1, "", "N", "1.5", "PARSE_BASE", "", "", 0, "unsupported base");
    str_emit("PARSE", "STR", 53, 1, -1, "", "N", "1.5", "PARSE_BASE", "", "", 0, "unsupported base");
    /* get_str sweep over bases 2..62 */
    {
        static const char* gv[3] = {"1.5", "10.75", "-0.75"};
        int base, vi;
        for (base = 2; base <= 62; base++) {
            for (vi = 0; vi < 3; vi++) {
                mpfr_t x;
                mpfr_exp_t e = 0;
                char* s;
                char signed_digits[BUFSZ];
                char exps[32], id[32];
                const char* d;
                int neg;
                mpfr_init2(x, 53);
                set_seed(x, gv[vi]);
                s = mpfr_get_str(NULL, &e, base, 0, x, MPFR_RNDN);
                if (s == NULL) { fprintf(stderr, "get_str null\n"); exit(2); }
                neg = mpfr_sgn(x) < 0;
                d = (s[0] == '-') ? s + 1 : s;
                snprintf(signed_digits, sizeof signed_digits, "%s%s", neg ? "-" : "", d);
                snprintf(exps, sizeof exps, "%lld", (long long)e);
                row_id(F_STR, id, sizeof id);
                of_row(F_STR, "%s\tGET_STR\tSTR\t53\t%d\t0\t\tN\t%s\tRAW\t%s\t%s\t0\t0\tget_str sweep",
                       id, base, gv[vi], signed_digits, exps);
                (void)x;
                mpfr_free_str(s);
                mpfr_clear(x);
            }
        }
    }
    /* get_str with fixed digit counts and zero handling */
    {
        static const char* gv[2] = {"1.5", "1234.5"};
        static const int bases[2] = {10, 16};
        static const int digs[4] = {0, 1, 5, 17};
        int bi, vi, di;
        for (bi = 0; bi < 2; bi++) {
            for (vi = 0; vi < 2; vi++) {
                for (di = 0; di < 4; di++) {
                    mpfr_t x;
                    mpfr_exp_t e = 0;
                    char* s;
                    char signed_digits[BUFSZ], exps[32], id[32];
                    const char* d;
                    int neg;
                    mpfr_init2(x, 113);
                    set_seed(x, gv[vi]);
                    s = mpfr_get_str(NULL, &e, bases[bi], (size_t)digs[di], x, MPFR_RNDN);
                    if (s == NULL) { fprintf(stderr, "get_str null\n"); exit(2); }
                    neg = mpfr_sgn(x) < 0;
                    d = (s[0] == '-') ? s + 1 : s;
                    snprintf(signed_digits, sizeof signed_digits, "%s%s", neg ? "-" : "", d);
                    snprintf(exps, sizeof exps, "%lld", (long long)e);
                    row_id(F_STR, id, sizeof id);
                    of_row(F_STR, "%s\tGET_STR\tSTR\t113\t%d\t%d\t\tN\t%s\tRAW\t%s\t%s\t0\t0\tget_str digits",
                           id, bases[bi], digs[di], gv[vi], signed_digits, exps);
                    mpfr_free_str(s);
                    mpfr_clear(x);
                }
            }
        }
        for (bi = 0; bi < 2; bi++) {
            int zi;
            for (zi = 0; zi < 2; zi++) {
                mpfr_t x;
                mpfr_exp_t e = 0;
                char* s;
                char signed_digits[BUFSZ], exps[32], id[32];
                const char* d;
                int neg;
                mpfr_init2(x, 53);
                mpfr_set_zero(x, zi == 0 ? 1 : -1);
                s = mpfr_get_str(NULL, &e, bases[bi], 0, x, MPFR_RNDN);
                if (s == NULL) { fprintf(stderr, "get_str null\n"); exit(2); }
                neg = mpfr_sgn(x) < 0;
                d = (s[0] == '-') ? s + 1 : s;
                snprintf(signed_digits, sizeof signed_digits, "%s%s", neg ? "-" : "", d);
                snprintf(exps, sizeof exps, "%lld", (long long)e);
                row_id(F_STR, id, sizeof id);
                of_row(F_STR, "%s\tGET_STR\tSTR\t53\t%d\t0\t\tN\t@zero%c\tRAW\t%s\t%s\t0\t0\tget_str zero",
                       id, bases[bi], zi == 0 ? '+' : '-', signed_digits, exps);
                mpfr_free_str(s);
                mpfr_clear(x);
            }
        }
    }
    /* format rows: %R<e|f|g> rendered through mpfr_asprintf */
    {
        static const char* fv[8] = {
            "1.5", "123456.789", "-0.001", "@nan@", "@inf+", "@inf-", "@zero+", "@zero-",
        };
#define X(c) c,
        static const char flet[3] = {KMP_FORMAT_LETTERS};
#undef X
        static const int fd[3] = {0, 5, 17};
        int vi2, si, di;
        for (vi2 = 0; vi2 < 8; vi2++) {
            for (si = 0; si < 3; si++) {
                for (di = 0; di < 3; di++) {
                    mpfr_t x;
                    char* buf = NULL;
                    char fmtbuf[16], id[32];
                    int need;
                    mpfr_init2(x, 53);
                    set_seed(x, fv[vi2]);
                    if (fd[di] > 0) {
                        snprintf(fmtbuf, sizeof fmtbuf, "%%.*R%c", flet[si]);
                        need = mpfr_asprintf(&buf, fmtbuf, fd[di], x);
                    } else {
                        snprintf(fmtbuf, sizeof fmtbuf, "%%R%c", flet[si]);
                        need = mpfr_asprintf(&buf, fmtbuf, x);
                    }
                    if (need < 0 || buf == NULL) { fprintf(stderr, "asprintf failed\n"); exit(2); }
                    row_id(F_STR, id, sizeof id);
                    of_row(F_STR, "%s\tFORMAT\tSTR\t53\t0\t%d\t%c\tN\t%s\tSTR\t%s\t\t0\t0\tformat",
                           id, fd[di], flet[si], fv[vi2], buf);
                    mpfr_free_str(buf);
                    mpfr_clear(x);
                }
            }
        }
    }
    /* toString rows: decimal get_str + the wrapper's canonical rendering */
    {
        static const char* tv[11] = {
            "1.5", "123456.789", "-0.001", "0.1", "-2.5", "100",
            "@nan@", "@inf+", "@inf-", "@zero+", "@zero-",
        };
        static const int td[4] = {0, 1, 5, 17};
        int vi3, di;
        for (vi3 = 0; vi3 < 11; vi3++) {
            for (di = 0; di < 4; di++) {
                mpfr_t x;
                char rendered[BUFSZ], id[32];
                mpfr_init2(x, 53);
                set_seed(x, tv[vi3]);
                to_decimal_string(x, td[di], MPFR_RNDN, rendered);
                row_id(F_STR, id, sizeof id);
                of_row(F_STR, "%s\tTO_STRING\tSTR\t53\t0\t%d\t\tN\t%s\tSTR\t%s\t\t0\t0\ttoString",
                       id, td[di], tv[vi3], rendered);
                mpfr_clear(x);
            }
        }
    }
}

/* -------------------------------------------------------- ties & high prec -- */

static void tie_binary_emit(const char* op, mpfr_prec_t p, mpfr_rnd_t r, int which) {
    /* which: 0 ADD, 1 SUB, 2 MUL, 3 DIV, 4 FMA, 5 FMS */
    mpfr_t x, y, z, rop, t;
    char xa[BUFSZ], ya[BUFSZ], za[BUFSZ], va[BUFSZ], id[32];
    mpfr_flags_t saved, delta;
    mpfr_prec_t pa = (mpfr_prec_t)(which == 0 ? 2 : p + 1);
    mpfr_prec_t pb = 2;
    mpfr_init2(x, pa);
    mpfr_init2(y, pb);
    mpfr_init2(z, 2);
    mpfr_init2(t, p + 1);
    if (which == 0) {
        mpfr_set_ui(x, 1, MPFR_RNDN);
        mpfr_set_ui_2exp(y, 1, -(mpfr_exp_t)p, MPFR_RNDN);
    } else if (which == 1) {
        mpfr_set_ui(x, 1, MPFR_RNDN);
        mpfr_set_ui_2exp(t, 1, 1 - (mpfr_exp_t)p, MPFR_RNDN);
        mpfr_add(x, x, t, MPFR_RNDN); /* exact at p + 1 bits */
        mpfr_set_ui_2exp(y, 1, -(mpfr_exp_t)p, MPFR_RNDN);
    } else {
        mpfr_set_ui(x, 1, MPFR_RNDN);
        mpfr_set_ui_2exp(t, 1, -(mpfr_exp_t)p, MPFR_RNDN);
        mpfr_add(x, x, t, MPFR_RNDN); /* 1 + 2^-p at p + 1 bits */
        if (which == 2 || which == 4 || which == 5) mpfr_set_ui(y, 1, MPFR_RNDN);
        else mpfr_set_ui(y, 2, MPFR_RNDN);
    }
    mpfr_set_zero(z, 1);
    mpfr_init2(rop, p);
    saved = flags_begin();
    switch (which) {
        case 0: bin_ADD(rop, x, y, r); break;
        case 1: bin_SUB(rop, x, y, r); break;
        case 2: bin_MUL(rop, x, y, r); break;
        case 3: bin_DIV(rop, x, y, r); break;
        case 4: fused_FMA(rop, x, y, z, r); break;
        case 5: fused_FMS(rop, x, y, z, r); break;
        default: break;
    }
    delta = flags_end(saved);
    token_of(x, xa); token_of(y, ya); token_of(z, za); token_of(rop, va);
    check_literal(xa, x, op); check_literal(ya, y, op); check_literal(va, rop, op);
    row_id((which == 4 || which == 5) ? F_FUSED : F_BIN, id, sizeof id);
    if (which == 4 || which == 5)
        of_row(F_FUSED, "%s\t%s\tTIE\t%ld\t%ld\t2\t%ld\t%s\t%s\t%s\t%s\tREG\t%s\t%d\t%u\texact midpoint",
               id, op, (long)pa, (long)pb, (long)p, rnd_name(r), xa, ya, za, va,
               (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
    else
        of_row(F_BIN, "%s\t%s\tTIE\t%ld\t%ld\t%ld\t%s\t%s\t%s\tREG\t%s\t%d\t%u\texact midpoint",
               id, op, (long)pa, (long)pb, (long)p, rnd_name(r), xa, ya, va,
               (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
    mpfr_clear(rop); mpfr_clear(t); mpfr_clear(z); mpfr_clear(y); mpfr_clear(x);
}

static void tie_double_emit(const char* op, int which, mpfr_prec_t p, mpfr_rnd_t r) {
    /* which: 0 ADD (d = +2^-p), 1 SUB (d = -2^-p) */
    mpfr_t x, rop, tmp;
    char xa[BUFSZ], va[BUFSZ], id[32];
    mpfr_flags_t saved, delta;
    double d = which == 0 ? ldexp(1.0, -(int)p) : -ldexp(1.0, -(int)p);
    mpfr_init2(x, p);
    mpfr_init2(rop, p);
    mpfr_init2(tmp, 53);
    mpfr_set_ui(x, 1, MPFR_RNDN);
    saved = flags_begin();
    if (which == 0) double_ADD(rop, x, d, r);
    else double_SUB(rop, x, d, r);
    delta = flags_end(saved);
    token_of(x, xa); token_of(rop, va);
    check_literal(xa, x, op); check_literal(va, rop, op);
    row_id(F_DOUBLE, id, sizeof id);
    of_row(F_DOUBLE, "%s\t%s\tTIE\t%ld\t%ld\t%s\t%s\t%016llx\tREG\t%s\t%d\t%u\texact midpoint",
           id, op, (long)p, (long)p, rnd_name(r), xa, (unsigned long long)dbl_bits(d), va,
           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
    mpfr_clear(tmp); mpfr_clear(rop); mpfr_clear(x);
}

static void block_ties(void) {
    int pi, ri;
    for (pi = 0; pi < 6; pi++) {
        mpfr_prec_t p = (mpfr_prec_t)core_precs[pi];
        for (ri = 0; ri < 5; ri++) {
            mpfr_rnd_t r = core_rnds[ri];
            tie_binary_emit("ADD", p, r, 0);
            tie_binary_emit("SUB", p, r, 1);
            tie_binary_emit("MUL", p, r, 2);
            tie_binary_emit("DIV", p, r, 3);
            tie_binary_emit("FMA", p, r, 4);
            tie_binary_emit("FMS", p, r, 5);
            tie_double_emit("ADD", 0, p, r);
            tie_double_emit("SUB", 1, p, r);
        }
    }
}

static void highprec_binary(const char* op, bin_fn fn, const char* xs, const char* ys,
                            mpfr_prec_t p, mpfr_rnd_t r) {
    mpfr_t x, y, rop;
    char xa[BUFSZ], ya[BUFSZ], va[BUFSZ], id[32];
    mpfr_flags_t saved, delta;
    mpfr_init2(x, p); mpfr_init2(y, p); mpfr_init2(rop, p);
    set_seed(x, xs); set_seed(y, ys);
    saved = flags_begin();
    fn(rop, x, y, r);
    delta = flags_end(saved);
    token_of(x, xa); token_of(y, ya); token_of(rop, va);
    check_literal(xa, x, op); check_literal(ya, y, op); check_literal(va, rop, op);
    row_id(F_BIN, id, sizeof id);
    of_row(F_BIN, "%s\t%s\tHIGHPREC\t%ld\t%ld\t%ld\t%s\t%s\t%s\tREG\t%s\t%d\t%u\thigh precision",
           id, op, (long)p, (long)p, (long)p, rnd_name(r), xa, ya, va,
           (delta & MPFR_FLAGS_INEXACT) ? 1 : 0, (unsigned)delta);
    mpfr_clear(rop); mpfr_clear(y); mpfr_clear(x);
}

static void block_highprec(void) {
    static const mpfr_prec_t precs[2] = {1000, 4096};
    static const struct { const char* x; const char* y; } md[3] = {
        {"1.5", "0.3"}, {"-2.5", "0.7"}, {"0.3", "-1.1"},
    };
    static const struct { const char* x; const char* y; } pw[3] = {
        {"2.5", "0.7"}, {"0.5", "1.5"}, {"1.5", "-2.5"},
    };
    static const char* sq[3] = {"1.5", "0.75", "2.25"};
    int pi, ri, i;
    for (pi = 0; pi < 2; pi++) {
        for (ri = 0; ri < 5; ri++) {
            for (i = 0; i < 3; i++) {
                highprec_binary("MUL", bin_MUL, md[i].x, md[i].y, precs[pi], core_rnds[ri]);
                highprec_binary("DIV", bin_DIV, md[i].x, md[i].y, precs[pi], core_rnds[ri]);
                highprec_binary("POW", bin_POW, pw[i].x, pw[i].y, precs[pi], core_rnds[ri]);
                emit_unary(F_UNARY, "SQRT", "HIGHPREC", unary_SQRT, sq[i],
                           core_rnds[ri], precs[pi], "high precision");
            }
        }
    }
}

int main(int argc, char** argv) {
    int i;
    setlocale(LC_ALL, "C");
    if (argc > 1) out_dir = argv[1];
    for (i = 0; i < F_COUNT; i++) of_open(i);
    block_binary_core();
    block_binary_special();
    block_fused();
    block_unary_core();
    block_trig_grid();
    block_scalar();
    block_double();
    block_pair();
    block_const();
    block_predicates();
    block_cmp();
    block_dotsum();
    block_factorial();
    block_conversions();
    block_strings();
    block_ties();
    block_highprec();
    of_close_all();
    return 0;
}